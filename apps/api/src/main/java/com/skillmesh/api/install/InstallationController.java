package com.skillmesh.api.install;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安装实例登记（设计文档 §11 installations、§12）。
 *
 * 回答一个问题：我在哪些机器上装了什么、装的是哪一版。
 * 客户端装/卸时登记，服务端只存事实，不替客户端决定装不装。
 */
@RestController
@RequestMapping("/api/v1/installations")
public class InstallationController {

    private static final List<String> SCOPES = List.of("user", "project", "custom");
    private static final List<String> POLICIES = List.of("manual", "notify", "auto_safe");
    private static final List<String> STATUSES =
        List.of("active", "missing", "conflict", "uninstalled", "blocked");

    private final InstallationRepository installations;
    private final SkillRepository skills;
    private final SkillService skillService;
    private final CurrentUserService currentUserService;
    private final AuditService audit;

    public InstallationController(InstallationRepository installations, SkillRepository skills,
                                 SkillService skillService, CurrentUserService currentUserService,
                                 AuditService audit) {
        this.installations = installations;
        this.skills = skills;
        this.skillService = skillService;
        this.currentUserService = currentUserService;
        this.audit = audit;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> register(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody RegisterInstallationRequest req) {
        CurrentUser user = currentUserService.current(jwt);
        SkillEntity skill = skills.findById(req.skill_id())
            .orElseThrow(() -> ApiException.notFound("Skill"));
        if (!skillService.canView(user, skill)) {
            throw ApiException.notFound("Skill");
        }
        requireOneOf("install_scope", req.install_scope(), SCOPES);
        requireOneOf("agent", req.agent(), List.of("claude-code", "codex", "cursor", "generic"));

        // 同一台机器同一个目录装同一个 Skill 只有一条：重复登记 = 更新
        InstallationEntity entity = installations
            .findByUserIdAndDeviceIdAndTargetPathAndSkillId(
                user.id(), req.device_id(), req.target_path(), req.skill_id())
            .orElseGet(InstallationEntity::new);
        boolean isNew = entity.getId() == null;
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        entity.setId(isNew ? UUID.randomUUID() : entity.getId());
        entity.setUserId(user.id());
        entity.setDeviceId(req.device_id());
        entity.setSkillId(req.skill_id());
        entity.setAgent(req.agent());
        entity.setInstallScope(req.install_scope());
        entity.setProjectRoot(req.project_root());
        entity.setTargetPath(req.target_path());
        entity.setInstalledVersion(req.installed_version());
        entity.setInstalledCommit(req.installed_commit());
        entity.setInstalledSha256(blankToNull(req.installed_sha256()));
        entity.setUpdatePolicy(req.update_policy() == null ? "notify" : req.update_policy());
        entity.setStatus(req.status() == null ? "active" : req.status());
        entity.setLocalModified(req.local_modified() != null && req.local_modified());
        if (isNew) {
            entity.setInstalledAt(now);
        }
        entity.setLastUpdatedAt(now);
        entity.setLastCheckedAt(now);
        installations.save(entity);

        audit.record(user, isNew ? "installation.register" : "installation.refresh",
            "skill", req.skill_id(), "success",
            Map.of("target_path", req.target_path(), "version",
                req.installed_version() == null ? "" : req.installed_version()));
        return ResponseEntity.created(URI.create("/api/v1/installations/" + entity.getId()))
            .body(toResponse(entity));
    }

    @GetMapping
    public List<Map<String, Object>> list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) String status) {
        CurrentUser user = currentUserService.current(jwt);
        List<InstallationEntity> rows = status == null || status.isBlank()
            ? installations.findByUserIdOrderByLastUpdatedAtDesc(user.id())
            : installations.findByUserIdAndStatusOrderByLastUpdatedAtDesc(user.id(), status);
        return rows.stream().map(InstallationController::toResponse).toList();
    }

    @PatchMapping("/{installationId}")
    public Map<String, Object> update(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID installationId,
                                       @Valid @RequestBody UpdateInstallationRequest req) {
        CurrentUser user = currentUserService.current(jwt);
        InstallationEntity entity = require(user, installationId);
        if (req.update_policy() != null) {
            requireOneOf("update_policy", req.update_policy(), POLICIES);
            entity.setUpdatePolicy(req.update_policy());
        }
        if (req.status() != null) {
            requireOneOf("status", req.status(), STATUSES);
            entity.setStatus(req.status());
        }
        if (req.local_modified() != null) {
            entity.setLocalModified(req.local_modified());
        }
        if (req.installed_version() != null) {
            entity.setInstalledVersion(req.installed_version());
        }
        entity.setLastUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        installations.save(entity);
        audit.record(user, "installation.update", "skill", entity.getSkillId(), "success",
            Map.of("installation_id", entity.getId().toString(),
                "status", entity.getStatus(), "policy", entity.getUpdatePolicy()));
        return toResponse(entity);
    }

    @DeleteMapping("/{installationId}")
    public ResponseEntity<Void> unregister(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable UUID installationId) {
        CurrentUser user = currentUserService.current(jwt);
        InstallationEntity entity = require(user, installationId);
        installations.delete(entity);
        audit.record(user, "installation.unregister", "skill", entity.getSkillId(), "success",
            Map.of("target_path", entity.getTargetPath()));
        return ResponseEntity.noContent().build();
    }

    private InstallationEntity require(CurrentUser user, UUID installationId) {
        InstallationEntity entity = installations.findByIdAndUserId(installationId, user.id())
            .orElseThrow(() -> ApiException.notFound("安装记录"));
        return entity;
    }

    private void requireOneOf(String field, String value, List<String> allowed) {
        if (value == null || !allowed.contains(value)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                field + " 只能是 " + String.join(" / ", allowed));
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Map.of 最多 10 对，这里字段更多，用 LinkedHashMap 顺序写更清楚。 */
    private static Map<String, Object> toResponse(InstallationEntity entity) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("id", entity.getId().toString());
        body.put("skill_id", entity.getSkillId().toString());
        body.put("device_id", entity.getDeviceId() == null ? "" : entity.getDeviceId().toString());
        body.put("agent", entity.getAgent());
        body.put("install_scope", entity.getInstallScope());
        body.put("target_path", entity.getTargetPath());
        body.put("installed_version", orEmpty(entity.getInstalledVersion()));
        body.put("installed_sha256", orEmpty(entity.getInstalledSha256()));
        body.put("update_policy", entity.getUpdatePolicy());
        body.put("local_modified", entity.isLocalModified());
        body.put("status", entity.getStatus());
        body.put("installed_at", entity.getInstalledAt() == null ? ""
            : entity.getInstalledAt().toString());
        body.put("last_updated_at", entity.getLastUpdatedAt() == null ? ""
            : entity.getLastUpdatedAt().toString());
        return body;
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    public record RegisterInstallationRequest(
        @NotNull UUID skill_id,
        UUID device_id,
        @NotBlank String agent,
        @NotBlank @Pattern(regexp = "user|project|custom") String install_scope,
        String project_root,
        @NotBlank String target_path,
        String installed_version,
        String installed_commit,
        String installed_sha256,
        @Pattern(regexp = "manual|notify|auto_safe") String update_policy,
        Boolean local_modified,
        @Pattern(regexp = "active|missing|conflict|uninstalled|blocked") String status) {}

    public record UpdateInstallationRequest(
        @Pattern(regexp = "manual|notify|auto_safe") String update_policy,
        @Pattern(regexp = "active|missing|conflict|uninstalled|blocked") String status,
        Boolean local_modified,
        String installed_version) {}
}
