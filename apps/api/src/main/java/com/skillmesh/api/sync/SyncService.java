package com.skillmesh.api.sync;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.device.DeviceEntity;
import com.skillmesh.api.device.DeviceService;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.sync.dto.SyncChangesetRequest;
import com.skillmesh.api.sync.dto.SyncChangesetResponse;
import com.skillmesh.api.version.PublishedDigest;
import com.skillmesh.api.version.SkillVersionRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同步变更集（详细设计 §12、§6.3）。
 *
 * 客户端负责算差异，服务端负责回答「你基于这个基线的提交，现在还成立吗」。
 * 两边都偏离基线、且内容不同才判冲突，不自动覆盖——这正是 §6.3 要的那条线。
 */
@Service
public class SyncService {

    private final SyncChangesetRepository changesets;
    private final SyncConflictRepository conflicts;
    private final SyncStateRepository states;
    private final SkillService skillService;
    private final SkillVersionRepository versions;
    private final DeviceService devices;

    public SyncService(SyncChangesetRepository changesets, SyncConflictRepository conflicts,
                       SyncStateRepository states,
                       SkillVersionRepository versions, DeviceService devices,
                       SkillService skillService) {
        this.changesets = changesets;
        this.conflicts = conflicts;
        this.states = states;
        this.skillService = skillService;
        this.versions = versions;
        this.devices = devices;
    }

    @Transactional
    public SyncChangesetResponse submit(CurrentUser user, UUID deviceId, String idempotencyKey,
                                        SyncChangesetRequest req) {
        DeviceEntity device = devices.require(user, deviceId);
        if (device.getRevokedAt() != null) {
            throw new ApiException(403, com.skillmesh.api.common.ErrorCodes.FORBIDDEN,
                "该设备已注销，拒绝同步");
        }
        // 幂等：同一个键重复提交返回同一条，不产生第二次同步。
        var previous = changesets.findByDeviceIdAndIdempotencyKey(deviceId, idempotencyKey);
        if (previous.isPresent()) {
            return toResponse(previous.get());
        }

        skillService.requireVisibleSkill(user, req.skill_id());
        String cloud = cloudHash(req.skill_id());

        SyncChangesetEntity changeset = new SyncChangesetEntity();
        changeset.setId(UUID.randomUUID());
        changeset.setDeviceId(deviceId);
        changeset.setSkillId(req.skill_id());
        changeset.setIdempotencyKey(idempotencyKey);
        changeset.setDirection(req.direction());
        changeset.setBaseRevision(emptyToNull(req.base_hash()));
        changeset.setTargetRevision(req.local_hash());
        changeset.setManifestJson(toManifestJson(req.manifest()));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        changeset.setCreatedAt(now);
        changeset.setStartedAt(now);

        boolean conflict = isConflict(req.base_hash(), req.local_hash(), cloud);
        changeset.setStatus(conflict ? ChangesetStatus.conflict : ChangesetStatus.completed);
        changeset.setSummaryJson(summary(req, cloud, conflict));
        changeset.setCompletedAt(now);
        changesets.save(changeset);

        if (conflict) {
            conflicts.save(SyncConflictEntity.of(changeset.getId(), req.base_hash(),
                req.local_hash(), cloud));
        }
        updateState(deviceId, req, cloud, conflict);
        return toResponse(changeset);
    }

    @Transactional(readOnly = true)
    public SyncChangesetResponse get(CurrentUser user, UUID changesetId) {
        SyncChangesetEntity changeset = changesets.findById(changesetId)
            .orElseThrow(() -> ApiException.notFound("变更集"));
        devices.require(user, changeset.getDeviceId());
        return toResponse(changeset);
    }

    /**
     * 标记冲突已解决。resolution 对应 sync_conflicts.status：
     * keep-local / keep-cloud / keep-both / dismiss。
     */
    @Transactional
    public SyncChangesetResponse resolve(CurrentUser user, UUID changesetId, String resolution) {
        SyncChangesetEntity changeset = changesets.findById(changesetId)
            .orElseThrow(() -> ApiException.notFound("变更集"));
        devices.require(user, changeset.getDeviceId());
        ConflictStatus status = switch (resolution) {
            case "keep-local" -> ConflictStatus.resolved_local;
            case "keep-cloud" -> ConflictStatus.resolved_cloud;
            case "keep-both" -> ConflictStatus.resolved_merged;
            default -> ConflictStatus.dismissed;
        };
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        for (SyncConflictEntity conflict : conflicts.findByChangesetIdOrderByCreatedAtAsc(
            changesetId)) {
            conflict.setStatus(status);
            conflict.setResolvedAt(now);
            conflicts.save(conflict);
        }
        changeset.setStatus(ChangesetStatus.completed);
        changeset.setCompletedAt(now);
        changesets.save(changeset);
        return toResponse(changeset);
    }

    /**
     * 冲突判定：基线为空（第一次同步）不冲突；云端为空（云端还没有内容）也不冲突；
     * 否则只要云端和基线不一样，就说明别的设备动过了。
     */
    /**
     * 冲突判定：本地与云端都偏离了共同基线、而且两边内容不同，才算冲突。
     *
     * 只有云端偏离基线（本地没动）是「云端领先」，该做的动作是拉取；
     * 只有本地偏离基线（云端没动）是「本地领先」，正常上传。
     * 两边都动过、动出来的东西还不一样，才是真冲突，交给人定夺。
     *
     * 基线为空（第一次同步，双方没有共同祖先）时分不清谁先谁后，
     * 按「有分歧即冲突」处理，避免盲目覆盖云端已有的内容。
     */
    static boolean isConflict(String base, String local, String cloud) {
        if (cloud == null || cloud.isBlank() || sameDigest(local, cloud)) {
            return false;
        }
        return !sameDigest(base, local) && !sameDigest(base, cloud);
    }

    /** 摘要比较：空值与空值算一样（基线还没记上时按「没有共同祖先」处理）。 */
    private static boolean sameDigest(String left, String right) {
        String a = left == null ? "" : left;
        String b = right == null ? "" : right;
        return a.equals(b);
    }

    /** 云端最新已发布版本的内容摘要（对「路径 + 文件摘要」排序后哈希，与 CLI 同口径）。 */
    private String cloudHash(UUID skillId) {
        // 口径统一在 PublishedDigest 一处：这里、版本建版本、版本转正各抄一份的话，
        // 迟早有一处抄歪——同一个 Skill，服务端一处说「云端有内容」、另一处说「云端没内容」。
        return PublishedDigest.of(versions.findBySkillIdOrderByCreatedAtDescThenId(skillId));
    }

    private void updateState(UUID deviceId, SyncChangesetRequest req, String cloud,
                             boolean conflict) {
        String localPath = req.local_path() == null || req.local_path().isBlank()
            ? req.skill_id().toString() : req.local_path();
        SyncStateEntity state = states
            .findByDeviceIdAndSkillIdAndLocalPath(deviceId, req.skill_id(), localPath)
            .orElseGet(SyncStateEntity::new);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        state.setId(state.getId() == null ? UUID.randomUUID() : state.getId());
        state.setDeviceId(deviceId);
        state.setSkillId(req.skill_id());
        state.setLocalPath(localPath);
        state.setLocalRevision(req.local_hash());
        state.setCloudRevision(cloud);
        state.setBaseRevision(emptyToNull(req.base_hash()));
        state.setLocalManifestHash(req.local_hash());
        state.setCloudManifestHash(emptyToNull(cloud));
        state.setStatus(conflict ? SyncStateStatus.conflict
            : cloud == null || cloud.equals(req.local_hash()) ? SyncStateStatus.in_sync
            : "upload" .equals(req.direction()) ? SyncStateStatus.local_ahead
            : SyncStateStatus.cloud_ahead);
        state.setUpdatedAt(now);
        if (!conflict) {
            state.setLastSyncedAt(now);
            state.setLastErrorMessage("");
        } else {
            state.setLastErrorCode("SYNC_CONFLICT");
            state.setLastErrorMessage("云端与本地都改过且内容不同，需要人工确认");
        }
        states.save(state);
    }

    private Map<String, Object> summary(SyncChangesetRequest req, String cloud, boolean conflict) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("base_hash", req.base_hash() == null ? "" : req.base_hash());
        summary.put("local_hash", req.local_hash());
        summary.put("cloud_hash", cloud == null ? "" : cloud);
        summary.put("conflict", conflict);
        summary.put("files", req.manifest() == null ? 0 : req.manifest().size());
        return summary;
    }

    private SyncChangesetResponse toResponse(SyncChangesetEntity changeset) {
        List<SyncChangesetResponse.Conflict> items = new ArrayList<>();
        for (SyncConflictEntity conflict : conflicts.findByChangesetIdOrderByCreatedAtAsc(
            changeset.getId())) {
            items.add(new SyncChangesetResponse.Conflict(conflict.getRelativePath(),
                conflict.getBaseSha256(), conflict.getLocalSha256(),
                conflict.getCloudSha256(), conflict.getConflictType()));
        }
        String cloud = String.valueOf(changeset.getSummaryJson().getOrDefault("cloud_hash", ""));
        String message = changeset.getStatus() == ChangesetStatus.conflict
            ? "云端与本地都改过且内容不同" : "已受理";
        return new SyncChangesetResponse(changeset.getId(), changeset.getSkillId(),
            changeset.getStatus(), cloud, message, items, changeset.getCreatedAt());
    }

    private List<Map<String, Object>> toManifestJson(List<SyncChangesetRequest.ManifestEntry> entries) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (entries == null) {
            return out;
        }
        for (SyncChangesetRequest.ManifestEntry entry : entries) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("path", entry.path());
            item.put("sha256", entry.sha256());
            item.put("size_bytes", entry.sizeBytes());
            out.add(item);
        }
        return out;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

}
