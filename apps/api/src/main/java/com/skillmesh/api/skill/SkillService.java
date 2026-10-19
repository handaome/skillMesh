package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.SkillPage;
import com.skillmesh.api.skill.dto.SkillResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillService {

    private static final int MAX_LIMIT = 100;

    private final SkillRepository skills;

    private final OrgService orgs;

    public SkillService(SkillRepository skills, OrgService orgs) {
        this.skills = skills;
        this.orgs = orgs;
    }

    @Transactional
    public SkillResponse create(CurrentUser user, CreateSkillRequest req) {
        requireVisibilitySupported(user, req);
        if (skills.existsByOwnerUserIdAndOrganizationIdIsNullAndSlug(user.id(), req.slug())) {
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN, "slug 已被占用: " + req.slug());
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(req.slug());
        entity.setName(req.name());
        entity.setDescription(req.description());
        entity.setOwnerUserId(user.id());
        entity.setOrganizationId(req.organization_id());
        entity.setOwnerTeamId(req.owner_team_id());
        entity.setVisibility(req.visibility());
        entity.setStatus("active");
        entity.setTags(req.tags() == null ? new String[0] : req.tags().toArray(new String[0]));
        entity.setMetadataJson(req.metadata() == null ? Map.of() : req.metadata());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toResponse(skills.save(entity));
    }

    @Transactional(readOnly = true)
    public SkillResponse get(CurrentUser user, UUID id) {
        SkillEntity entity = skills.findById(id).orElseThrow(() -> ApiException.notFound("Skill"));
        if (!canView(user, entity)) {
            throw ApiException.notFound("Skill");
        }
        return toResponse(entity);
    }

    @Transactional(readOnly = true)
    public SkillPage search(CurrentUser user, String q, String visibility, String tag,
                            boolean mine, Integer limit, String cursor) {
        int size = limit == null ? 20 : Math.clamp(limit, 1, MAX_LIMIT);
        OffsetDateTime cursorTime = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = new String(decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            cursorTime = OffsetDateTime.parse(parts[0]);
            cursorId = UUID.fromString(parts[1]);
        }
        // 团队/组织可见的 Skill：把「我加入的团队与组织」交给 SQL 过滤，
        // 别把不该看到的查出来再在内存里筛——那等于没权限控制。
        List<SkillEntity> rows = skills.search(user.id(), normalize(q), visibility, tag,
            mine, uuidArray(orgs.myOrganizationIds(user.id())),
            uuidArray(orgs.myTeamIds(user.id())),
            cursorTime, cursorId, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<SkillEntity> page = hasMore ? rows.subList(0, size) : rows;
        List<SkillResponse> items = new ArrayList<>(page.size());
        for (SkillEntity entity : page) {
            items.add(toResponse(entity));
        }
        String next = null;
        if (hasMore && !page.isEmpty()) {
            SkillEntity last = page.get(page.size() - 1);
            next = encode(last.getUpdatedAt() + "|" + last.getId());
        }
        return new SkillPage(items, next, hasMore);
    }

    /**
     * 可见性判定（设计文档 §10 团队管理）。
     *
     * public 谁都能看；private 只有属主；team 要团队成员；organization 要组织成员。
     * 外部调用方（版本、制品、审计）都走这里，所以一处判就够。
     */
    public boolean canView(CurrentUser user, SkillEntity entity) {
        if ("public".equals(entity.getVisibility())
            || user.id().equals(entity.getOwnerUserId())) {
            return true;
        }
        if (entity.getOrganizationId() == null) {
            return false;
        }
        return switch (entity.getVisibility()) {
            case "team" -> entity.getOwnerTeamId() != null
                && orgs.isTeamMember(entity.getOwnerTeamId(), user.id());
            case "organization" -> orgs.isOrgMember(entity.getOrganizationId(), user.id());
            default -> false;
        };
    }

    /**
     * 创建时的可见性校验：team 必须挂到组织下的某个团队，
     * 组织内可见需要组织成员身份（不能凭空建一个没人看得到的团队 Skill）。
     */
    private void requireVisibilitySupported(CurrentUser user, CreateSkillRequest req) {
        String visibility = req.visibility();
        if (!Set.of("private", "public", "team", "organization").contains(visibility)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "可见性只能是 private / public / team / organization");
        }
        if ("private".equals(visibility) || "public".equals(visibility)) {
            return;
        }
        if (req.organization_id() == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                visibility + " 可见必须指定 organization_id");
        }
        if (!orgs.isOrgMember(req.organization_id(), user.id())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "你不是该组织的成员");
        }
        if ("team".equals(visibility) && req.owner_team_id() == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "team 可见必须指定 owner_team_id");
        }
    }

    /** PostgreSQL 的 uuid[] 字面量。一个都没加入就传空数组，= any 匹配不到任何行。 */
    private static String uuidArray(List<UUID> ids) {
        if (ids.isEmpty()) {
            return "{}";
        }
        return "{" + ids.stream().map(UUID::toString)
            .reduce((left, right) -> left + "," + right).orElse("") + "}";
    }

    private static String normalize(String q) {
        return q == null || q.isBlank() ? null : "%" + q.toLowerCase() + "%";
    }

    public static SkillResponse toResponse(SkillEntity entity) {
        return new SkillResponse(entity.getId(), entity.getSlug(), entity.getName(),
            entity.getDescription(), entity.getVisibility(), entity.getStatus(),
            entity.getOwnerUserId(), entity.getOrganizationId(), entity.getOwnerTeamId(),
            List.of(entity.getTags() == null ? new String[0] : entity.getTags()),
            entity.getMetadataJson(), entity.getLatestVersion(),
            entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private static byte[] decode(String cursor) {
        return Base64.getUrlDecoder().decode(cursor);
    }

    private static String encode(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
