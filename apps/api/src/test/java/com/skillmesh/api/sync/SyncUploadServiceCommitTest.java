package com.skillmesh.api.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.skillmesh.api.artifact.ArtifactService;
import com.skillmesh.api.artifact.ZipManifest;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.auth.UserStatus;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.PathOrder;
import com.skillmesh.api.common.Visibility;
import com.skillmesh.api.device.DeviceService;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillStatus;
import com.skillmesh.api.sync.dto.SyncChangesetRequest;
import com.skillmesh.api.sync.dto.SyncChangesetResponse;
import com.skillmesh.api.upload.UploadService;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import com.skillmesh.api.version.VersionStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 逐文件增量上传的落地：只传变了的文件，服务端拿基线补齐其余的。
 *
 * 合成内容对不上本地声明时必须报错——发布一份缺文件的版本，
 * 用户装下来才发现内容不对，比同步失败糟糕得多。
 */
@SpringBootTest
class SyncUploadServiceCommitTest {

    private static final String SKILL_MD = "---\nname: probe\ndescription: 探针技能\n---\n\n# probe\n";
    private static final String SKILL_MD_V2 =
        "---\nname: probe\ndescription: 探针技能\n---\n\n# probe\n\n改了。\n";
    private static final String GUIDE_V1 = "参考资料第一版\n";
    private static final String GUIDE_V2 = "参考资料第二版\n";

    @Autowired
    private SyncUploadService syncUploads;

    @Autowired
    private SyncService syncService;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private ArtifactService artifacts;

    @Autowired
    private SkillVersionRepository versions;

    @Autowired
    private SkillRepository skills;

    @Autowired
    private UserRepository users;

    @Autowired
    private DeviceService devices;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate committed;
    private CurrentUser owner;
    private UUID skillId;
    private UUID deviceId;
    private String baseRevision;
    private final List<UUID> artifactIds = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            owner = newUser();
            skillId = newSkill();
        });
        deviceId = devices.register(owner, "probe-" + UUID.randomUUID(), "探针设备",
            "windows", "0.0.0").getId();

        // 基线：一份已发布的版本，里面两个文件。
        byte[] zip = zipOf(Map.of("SKILL.md", SKILL_MD, "guide.md", GUIDE_V1));
        List<Map<String, Object>> manifest = ZipManifest.of(zip);
        String sha = ContentDigest.sha256(zip);
        String key = artifacts.store(sha, zip);
        var artifact = artifacts.record(sha, key, zip.length, manifest.size(), manifest);
        artifactIds.add(artifact.getId());
        committed.executeWithoutResult(status -> {
            SkillVersionEntity version = new SkillVersionEntity();
            version.setId(UUID.randomUUID());
            version.setSkillId(skillId);
            version.setVersion("1.0.0");
            version.setChannel("stable");
            version.setChangelog("基线");
            version.setManifestJson(manifest);
            version.setArtifactId(artifact.getId());
            version.setStatus(VersionStatus.published);
            version.setCreatedBy(owner.id());
            version.setPublishedBy(owner.id());
            version.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
            version.setPublishedAt(OffsetDateTime.now(ZoneOffset.UTC));
            versions.saveAndFlush(version);
            SkillEntity skill = skills.findById(skillId).orElseThrow();
            skill.setLatestVersion("1.0.0");
            skills.saveAndFlush(skill);
        });
        baseRevision = ContentDigest.of(manifest);
    }

    @AfterEach
    void tearDown() {
        committed.executeWithoutResult(status -> {
            jdbc.update("delete from upload_sessions where changeset_id in "
                + "(select id from sync_changesets where skill_id = ?)", skillId);
            jdbc.update("delete from sync_conflicts where changeset_id in "
                + "(select id from sync_changesets where skill_id = ?)", skillId);
            jdbc.update("delete from sync_changesets where skill_id = ?", skillId);
            jdbc.update("delete from sync_states where skill_id = ?", skillId);
            jdbc.update("delete from skill_scans where skill_version_id in "
                + "(select id from skill_versions where skill_id = ?)", skillId);
            jdbc.update("delete from skill_versions where skill_id = ?", skillId);
            for (UUID artifactId : artifactIds) {
                // 同一份内容可能被别的 Skill 引用着，只删没人引用的。
                jdbc.update("delete from artifacts where id = ? and not exists "
                    + "(select 1 from skill_versions v where v.artifact_id = artifacts.id)",
                    artifactId);
            }
            jdbc.update("delete from audit_events where target_id in (?, ?)",
                skillId.toString(), deviceId.toString());
            jdbc.update("delete from skills where id = ?", skillId);
            jdbc.update("delete from devices where id = ?", deviceId);
            jdbc.update("delete from users where id = ?", owner.id());
        });
    }

    @Test
    void 只传变了的文件也能发布一份完整的版本() throws IOException {
        Map<String, byte[]> content = new LinkedHashMap<>();
        content.put("SKILL.md", SKILL_MD_V2.getBytes(StandardCharsets.UTF_8));
        content.put("guide.md", GUIDE_V1.getBytes(StandardCharsets.UTF_8));

        SyncChangesetResponse changeset = submit(content);
        assertThat(changeset.status()).isNotEqualTo(ChangesetStatus.conflict);

        // 只传变了的那个文件，没变的那个不传。
        byte[] changed = content.get("SKILL.md");
        var slots = syncUploads.openUploads(owner, changeset.changesetId(), List.of(
            new SyncUploadService.FileSpec("SKILL.md", ContentDigest.sha256(changed),
                changed.length, "text/markdown")));
        assertThat(slots).hasSize(1);
        UUID sessionId = UUID.fromString(slots.get(0).uploadId());
        uploadService.store(owner, sessionId, new ByteArrayInputStream(changed), changed.length,
            "text/markdown");
        uploadService.complete(owner, sessionId);

        Map<String, Object> result = syncUploads.commit(owner, changeset.changesetId(),
            baseRevision);

        assertThat(result.get("version_status")).isEqualTo(VersionStatus.published);
        assertThat(result.get("files")).isEqualTo(2);

        SkillVersionEntity version = versionOf("1.0.1");
        assertThat(version.getStatus()).isEqualTo(VersionStatus.published);
        // 变了的用新传的，没变的从基线补齐——两份文件都在。
        Map<String, String> published = readZip(
            artifacts.open(artifacts.require(version.getArtifactId())));
        assertThat(published).containsEntry("SKILL.md", SKILL_MD_V2);
        assertThat(published).containsEntry("guide.md", GUIDE_V1);
    }

    @Test
    void 扫出真凭据的版本进隔离而不是发布() throws IOException {
        // AWS Access Key 的形状：AKIA 加 16 位大写字母数字。
        String secret = "AKIAIOSFODNN7EXAMPLE";
        Map<String, byte[]> content = new LinkedHashMap<>();
        content.put("SKILL.md", (SKILL_MD_V2 + "\naws_key = " + secret + "\n")
            .getBytes(StandardCharsets.UTF_8));
        content.put("guide.md", GUIDE_V1.getBytes(StandardCharsets.UTF_8));

        SyncChangesetResponse changeset = submit(content);
        byte[] changed = content.get("SKILL.md");
        var slots = syncUploads.openUploads(owner, changeset.changesetId(), List.of(
            new SyncUploadService.FileSpec("SKILL.md", ContentDigest.sha256(changed),
                changed.length, "text/markdown")));
        UUID sessionId = UUID.fromString(slots.get(0).uploadId());
        uploadService.store(owner, sessionId, new ByteArrayInputStream(changed), changed.length,
            "text/markdown");
        uploadService.complete(owner, sessionId);

        Map<String, Object> result = syncUploads.commit(owner, changeset.changesetId(),
            baseRevision);

        // 扫出真凭据 → 隔离，不是发布（设计文档 §10.1、§4.3 SEC-001）。
        assertThat(result.get("version_status")).isEqualTo(VersionStatus.quarantined);
        SkillVersionEntity version = versionOf("1.0.1");
        assertThat(version.getStatus()).isEqualTo(VersionStatus.quarantined);
        // 扫描流水里不能出现密钥值：结论是「哪个文件第几行像 AWS Key」，不是密钥本身。
        String findings = jdbc.queryForObject(
            "select findings_json::text from skill_scans where skill_version_id = ?",
            String.class, version.getId());
        assertThat(findings).doesNotContain(secret);
    }

    @Test
    void 合出来的内容跟本地声明的不一致就报错() {
        Map<String, byte[]> content = new LinkedHashMap<>();
        content.put("SKILL.md", SKILL_MD_V2.getBytes(StandardCharsets.UTF_8));
        content.put("guide.md", GUIDE_V2.getBytes(StandardCharsets.UTF_8));

        SyncChangesetResponse changeset = submit(content);
        byte[] changed = content.get("SKILL.md");
        var slots = syncUploads.openUploads(owner, changeset.changesetId(), List.of(
            new SyncUploadService.FileSpec("SKILL.md", ContentDigest.sha256(changed),
                changed.length, "text/markdown")));
        UUID sessionId = UUID.fromString(slots.get(0).uploadId());
        uploadService.store(owner, sessionId, new ByteArrayInputStream(changed), changed.length,
            "text/markdown");
        uploadService.complete(owner, sessionId);

        // guide.md 声明改了却没传：合出来的内容与声明对不上，不能发布。
        ApiException error = catchThrowableOfType(
            () -> syncUploads.commit(owner, changeset.changesetId(), baseRevision),
            ApiException.class);

        assertThat(error).isNotNull();
        assertThat(error.code()).isEqualTo(ErrorCodes.VERSION_CONFLICT);
        assertThat(versions.findBySkillIdOrderByCreatedAtDescThenId(skillId))
            .extracting(SkillVersionEntity::getVersion).containsExactly("1.0.0");
    }

    /** 提交一次变更集：声明的是同步完成后的完整文件集。 */
    private SyncChangesetResponse submit(Map<String, byte[]> content) {
        List<Map<String, Object>> manifest = manifestOf(content);
        List<SyncChangesetRequest.ManifestEntry> entries = new ArrayList<>();
        for (Map<String, Object> item : manifest) {
            entries.add(new SyncChangesetRequest.ManifestEntry(
                String.valueOf(item.get("path")), String.valueOf(item.get("sha256")),
                ((Number) item.get("size_bytes")).longValue()));
        }
        return syncService.submit(owner, deviceId, "probe-" + UUID.randomUUID(),
            new SyncChangesetRequest(skillId, "upload", baseRevision,
                ContentDigest.of(manifest), "local/probe", entries));
    }

    private SkillVersionEntity versionOf(String version) {
        return versions.findBySkillIdOrderByCreatedAtDescThenId(skillId).stream()
            .filter(row -> version.equals(row.getVersion()))
            .findFirst().orElseThrow();
    }

    private CurrentUser newUser() {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName("探针属主");
        user.setStatus(UserStatus.active);
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.saveAndFlush(user);
        return new CurrentUser(id, user.getOidcSubject(), user.getDisplayName(), null,
            UserStatus.active, "user");
    }

    private UUID newSkill() {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug("probe-sync-skill-" + UUID.randomUUID());
        entity.setName("探针技能");
        entity.setDescription("测试用");
        entity.setOwnerUserId(owner.id());
        entity.setVisibility(Visibility.PRIVATE);
        entity.setStatus(SkillStatus.active);
        entity.setTags(new String[0]);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.saveAndFlush(entity).getId();
    }

    /** 清单：路径 + 内容摘要 + 字节数，按路径排序（与服务端同口径）。 */
    private List<Map<String, Object>> manifestOf(Map<String, byte[]> content) {
        List<Map<String, Object>> manifest = new ArrayList<>();
        content.forEach((path, data) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", path);
            item.put("sha256", ContentDigest.sha256(data));
            item.put("size_bytes", (long) data.length);
            manifest.add(item);
        });
        manifest.sort(Comparator.comparing(item -> String.valueOf(item.get("path")),
            PathOrder.BY_CODE_POINT));
        return manifest;
    }

    private byte[] zipOf(Map<String, String> content) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : new TreeMap<>(content).entrySet()) {
                ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0L);
                zos.putNextEntry(zipEntry);
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private Map<String, String> readZip(InputStream in) throws IOException {
        Map<String, String> content = new LinkedHashMap<>();
        try (var zis = new java.util.zip.ZipInputStream(in, StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    content.put(entry.getName(),
                        new String(zis.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return content;
    }
}
