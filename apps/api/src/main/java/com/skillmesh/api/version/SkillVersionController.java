package com.skillmesh.api.version;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.version.dto.RevokeVersionRequest;
import com.skillmesh.api.version.dto.SkillVersionResponse;
import com.skillmesh.api.version.dto.CreateVersionRequest;
import com.skillmesh.api.version.dto.CreateVersionResponse;
import com.skillmesh.api.version.dto.EditFileRequest;
import com.skillmesh.api.version.dto.EditFileResponse;
import com.skillmesh.api.version.dto.VersionFileContentResponse;
import com.skillmesh.api.version.dto.VersionFilesResponse;
import com.skillmesh.api.version.dto.VersionPage;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class SkillVersionController {

    private final SkillVersionService versions;
    private final VersionFileService versionFiles;
    private final VersionEditService versionEdits;
    private final CurrentUserService currentUserService;

    public SkillVersionController(SkillVersionService versions,
                                  VersionFileService versionFiles,
                                  VersionEditService versionEdits,
                                  CurrentUserService currentUserService) {
        this.versions = versions;
        this.versionFiles = versionFiles;
        this.versionEdits = versionEdits;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/skills/{skillId}/versions")
    public ResponseEntity<CreateVersionResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID skillId,
            @Valid @RequestBody CreateVersionRequest req) {
        CreateVersionResponse body = versions.create(currentUserService.current(jwt), skillId, req);
        return ResponseEntity.created(
            URI.create("/api/v1/skills/" + skillId + "/versions/" + body.versionId()))
            .body(body);
    }

    @GetMapping("/skills/{skillId}/versions")
    public VersionPage list(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID skillId) {
        return versions.list(currentUserService.current(jwt), skillId);
    }

    /**
     * 撤回一个版本（设计文档 §10 撤回）。
     * 只改可用状态、留下记录；已经装在别人机器上的内容不无痕删除。
     */
    @GetMapping("/skills/{skillId}/versions/{version}")
    public SkillVersionResponse detail(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID skillId,
                                      @PathVariable String version) {
        return versions.detail(currentUserService.current(jwt), skillId, version);
    }

    @PostMapping("/skills/{skillId}/versions/{version}/revoke")
    public ResponseEntity<Map<String, Object>> revoke(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID skillId,
            @PathVariable String version,
            @RequestBody(required = false) RevokeVersionRequest req) {
        return ResponseEntity.ok(versions.revoke(currentUserService.current(jwt), skillId,
            version, req == null ? "" : req.reason()));
    }

    /**
     * 浏览版本里的文件（在线看 Skill 的内部结构）。
     *
     * 清单取服务端从真 zip 算出来的那份，不是发布方自填的。
     */
    @GetMapping("/skills/{skillId}/versions/{version}/files")
    public VersionFilesResponse files(@AuthenticationPrincipal Jwt jwt,
                                      @PathVariable UUID skillId,
                                      @PathVariable String version) {
        return versionFiles.list(currentUserService.current(jwt), skillId, version);
    }

    /** 读某个文件的正文。路径用查询参数：制品内的路径带斜杠，走路径段会被拆开。 */
    @GetMapping("/skills/{skillId}/versions/{version}/file")
    public VersionFileContentResponse file(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID skillId,
                                           @PathVariable String version,
                                           @RequestParam String path) {
        return versionFiles.read(currentUserService.current(jwt), skillId, version, path);
    }

    /**
     * 改一个文件，产出新版本。
     *
     * 版本不可变，所以这不是就地改：服务端基于源版本重打包出一个新版本，
     * 再走一遍正常的发布流程（扫描 → 发布或送审）。
     */
    @PutMapping("/skills/{skillId}/versions/{version}/file")
    public EditFileResponse editFile(@AuthenticationPrincipal Jwt jwt,
                                     @PathVariable UUID skillId,
                                     @PathVariable String version,
                                     @Valid @RequestBody EditFileRequest req) {
        return versionEdits.edit(currentUserService.current(jwt), skillId, version, req);
    }
}
