package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
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
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SkillService {

    private static final int MAX_LIMIT = 100;

    private final SkillRepository skills;

    public SkillService(SkillRepository skills) {
        this.skills = skills;
    }

    @Transactional
    public SkillResponse create(CurrentUser user, CreateSkillRequest req) {
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
                            Integer limit, String cursor) {
        int size = limit == null ? 20 : Math.clamp(limit, 1, MAX_LIMIT);
        OffsetDateTime cursorTime = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            String[] parts = new String(decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            cursorTime = OffsetDateTime.parse(parts[0]);
            cursorId = UUID.fromString(parts[1]);
        }
        List<SkillEntity> rows = skills.search(user.id(), normalize(q), visibility, tag,
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

    /** 可见性判定：公开的谁都能看，私有的只有属主能看。 */
    public static boolean canView(CurrentUser user, SkillEntity entity) {
        return "public".equals(entity.getVisibility()) || user.id().equals(entity.getOwnerUserId());
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
