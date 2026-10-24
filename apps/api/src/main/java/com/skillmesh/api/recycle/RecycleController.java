package com.skillmesh.api.recycle;

import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.recycle.dto.RestoreFileRequest;
import com.skillmesh.api.version.VersionEditService;
import com.skillmesh.api.version.dto.EditFileResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 回收站：被删掉的文件，回收期内能找回来（设计文档 §6.4）。 */
@RestController
@RequestMapping("/api/v1/skills/{skillId}/recycle")
public class RecycleController {

    private final RecycleService recycle;
    private final VersionEditService versionEdits;
    private final CurrentUserService currentUserService;

    public RecycleController(RecycleService recycle, VersionEditService versionEdits,
                             CurrentUserService currentUserService) {
        this.recycle = recycle;
        this.versionEdits = versionEdits;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<RecycleService.RecycleFile> list(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID skillId) {
        return recycle.list(currentUserService.current(jwt), skillId);
    }

    /** 恢复一个被删的文件，产出新版本。 */
    @PostMapping("/restore")
    public EditFileResponse restore(@AuthenticationPrincipal Jwt jwt,
                                    @PathVariable UUID skillId,
                                    @Valid @RequestBody RestoreFileRequest req) {
        return versionEdits.restore(currentUserService.current(jwt), skillId, req.path());
    }
}
