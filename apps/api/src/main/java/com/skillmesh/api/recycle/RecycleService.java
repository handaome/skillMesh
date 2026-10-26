package com.skillmesh.api.recycle;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件级回收站（设计文档 §6.4：删除传播走软删除、回收期、支持恢复）。
 *
 * 删掉一个文件时不是把它抹掉：它进回收站，回收期内能找回来。
 * 回收期到了才真清。
 *
 * 这里只管「哪些文件被删了」这件事。删除本身是**发版**带来的：
 * 一版比上一版少的文件就是被删的。版本不可变，所以那份内容一直还在
 * 旧版本的制品里——恢复要做的只是把它放回新一版。
 */
@Service
public class RecycleService {

    /** 回收期：默认 30 天。 */
    public static final Duration DEFAULT_RETENTION = Duration.ofDays(30);

    private final RecycleFileRepository files;
    private final SkillVersionRepository versions;
    private final SkillService skillService;

    public RecycleService(RecycleFileRepository files, SkillVersionRepository versions,
                          SkillService skillService) {
        this.files = files;
        this.versions = versions;
        this.skillService = skillService;
    }

    /**
     * 记下这一版比上一版少掉的文件。
     *
     * 只在**已发布**的版本之间比：草稿和待审版本还没算数，
     * 拿它们比会把还没定稿的东西记成删除。
     */
    @Transactional
    public void recordRemoved(UUID skillId, String newVersion) {
        String previousVersion = previousPublishedVersion(skillId, newVersion);
        if (previousVersion == null) {
            return;
        }
        Map<String, String> before = manifestOf(skillId, previousVersion);
        Map<String, String> after = manifestOf(skillId, newVersion);
        if (before.isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime expires = now.plus(DEFAULT_RETENTION);
        for (Map.Entry<String, String> item : before.entrySet()) {
            if (after.containsKey(item.getKey())) {
                continue;
            }
            files.findBySkillIdAndPath(skillId, item.getKey()).ifPresentOrElse(existing -> {
                // 之前删过又删一次：刷新回收期，来源版本更新。
                existing.setSourceVersion(previousVersion);
                existing.setCreatedAt(now);
                existing.setExpiresAt(expires);
                files.save(existing);
            }, () -> {
                RecycleFileEntity entity = new RecycleFileEntity();
                entity.setId(UUID.randomUUID());
                entity.setSkillId(skillId);
                entity.setPath(item.getKey());
                entity.setSha256(item.getValue());
                entity.setSizeBytes(0);
                entity.setSourceVersion(previousVersion);
                entity.setCreatedAt(now);
                entity.setExpiresAt(expires);
                files.save(entity);
            });
        }
    }

    /**
     * 上一版已发布版本号：这一版比它少的文件就是被删的。
     *
     * 排除这一版自己：它已经是最新的一版了。
     */
    private String previousPublishedVersion(UUID skillId, String currentVersion) {
        for (SkillVersionEntity row : versions.findBySkillIdOrderByCreatedAtDescThenId(skillId)) {
            if (!"published".equals(row.getStatus()) || row.getVersion().equals(currentVersion)) {
                continue;
            }
            if (row.getManifestJson() == null || row.getManifestJson().isEmpty()) {
                continue;
            }
            return row.getVersion();
        }
        return null;
    }

    /** 回收站里的文件（还没过期的）。 */
    @Transactional
    public List<RecycleFile> list(CurrentUser user, UUID skillId) {
        skillService.requireVisibleSkill(user, skillId);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // 顺手清掉过了回收期的：没有定时任务，看回收站的时候正是清理的时候。
        files.deleteExpired(now);
        List<RecycleFile> out = new ArrayList<>();
        for (RecycleFileEntity entity : files.findActive(skillId, now)) {
            out.add(new RecycleFile(entity.getPath(), entity.getSha256(),
                entity.getSourceVersion(), entity.getCreatedAt(), entity.getExpiresAt()));
        }
        return out;
    }

    /**
     * 查回收站里的一条：它被删时最后出现在哪一版。
     *
     * 恢复本身在 {@code VersionEditService.restore}：内容从这一版里取，
     * 放回当前版本，别的文件保持现状。
     */
    @Transactional(readOnly = true)
    public RecycleFile require(CurrentUser user, UUID skillId, String path) {
        skillService.requireVisibleSkill(user, skillId);
        RecycleFileEntity entity = files.findBySkillIdAndPath(skillId, path)
            .orElseThrow(() -> ApiException.notFound("回收站里的文件 " + path));
        if (entity.getExpiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
            // 过了回收期就是「没了」：回收站只留 30 天，
            // 期满的条目会被清掉，恢复不了就是恢复不了。
            throw new ApiException(404, ErrorCodes.NOT_FOUND,
                "文件已过回收期，不能再恢复：" + path);
        }
        return new RecycleFile(entity.getPath(), entity.getSha256(),
            entity.getSourceVersion(), entity.getCreatedAt(), entity.getExpiresAt());
    }

    /**
     * 恢复过了就把回收站里的这条标掉：文件回到版本里了，再恢复一次是多余的。
     */
    @Transactional
    public void markRestored(UUID skillId, String path) {
        files.findBySkillIdAndPath(skillId, path).ifPresent(entity -> {
            entity.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC));
            files.save(entity);
        });
    }

    /** 某一版的逐文件摘要：路径 → sha256。 */
    private Map<String, String> manifestOf(UUID skillId, String version) {
        return manifestOf(versions.findBySkillIdAndVersion(skillId, version).orElse(null));
    }

    /** 一版的逐文件摘要：路径 → sha256。 */
    private Map<String, String> manifestOf(SkillVersionEntity entity) {
        if (entity == null || entity.getManifestJson() == null) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> item : entity.getManifestJson()) {
            out.put(String.valueOf(item.get("path")), String.valueOf(item.get("sha256")));
        }
        return out;
    }

    /** 回收站里的一条记录。 */
    public record RecycleFile(String path, String sha256,
                               @com.fasterxml.jackson.annotation.JsonProperty("source_version")
                               String sourceVersion,
                               @com.fasterxml.jackson.annotation.JsonProperty("deleted_at")
                               OffsetDateTime deletedAt,
                               @com.fasterxml.jackson.annotation.JsonProperty("expires_at")
                               OffsetDateTime expiresAt) {}
}
