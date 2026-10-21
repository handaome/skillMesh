package com.skillmesh.api.skill;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.skill.dto.SkillSourceInput;
import com.skillmesh.api.skill.dto.UpdateSkillRequest;
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
    private final SourceRepository sources;

    public SkillService(SkillRepository skills, OrgService orgs, SourceRepository sources) {
        this.skills = skills;
        this.orgs = orgs;
        this.sources = sources;
    }

    @Transactional
    public SkillResponse create(CurrentUser user, CreateSkillRequest req) {
        requireVisibilitySupported(user, req);
        // 个人空间与组织空间是两套命名空间：只有建个人 Skill 时才查个人名下。
        // 原来不区分——用户自己有个个人 demo 时，在组织里建 demo 会被误挡，
        // 而唯一索引 uq_skills_org_slug 本来允许（它只约束组织内）。
        if (req.organization_id() == null
                && skills.existsByOwnerUserIdAndOrganizationIdIsNullAndSlug(user.id(), req.slug())) {
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN, "slug 已被占用: " + req.slug());
        }
        if (req.organization_id() != null
                && skills.existsByOrganizationIdAndSlug(req.organization_id(), req.slug())) {
            // 组织内的 slug 唯一（uq_skills_org_slug），团队之间也共用一个命名空间。
            // 预检拦住才回 409；撞唯一索引会变成 500，客户端看不出是重名。
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN,
                "slug 在该组织下已被占用: " + req.slug());
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
        SkillEntity saved = skills.save(entity);
        recordSource(user, saved, req.source());
        return toResponse(saved);
    }

    /**
     * 记一条来源（设计文档 §7.1）。
     * 客户端没给就记 local_import——来源不明也要留痕，不能等于没有来源。
     */
    private void recordSource(CurrentUser user, SkillEntity skill, SkillSourceInput source) {
        SourceEntity row = new SourceEntity();
        row.setId(UUID.randomUUID());
        row.setSkillId(skill.getId());
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(user.id());

        if (source == null) {
            row.setSourceType("local_import");
            row.setProvider("local");
            row.setLicenseStatus("unknown");
            sources.save(row);
            return;
        }
        row.setSourceType(source.sourceType() == null ? "local_import" : source.sourceType());
        row.setProvider(source.provider() == null ? "local" : source.provider());
        row.setRepositoryUrl(source.repositoryUrl());
        row.setSubpath(source.subpath());
        row.setRefType(source.refType());
        row.setRefValue(source.refValue());
        row.setResolvedCommit(source.resolvedCommit());
        row.setLicenseExpression(source.licenseExpression());
        row.setLicenseStatus(source.licenseStatus() == null ? "unknown" : source.licenseStatus());
        sources.save(row);
    }

    @Transactional
    public SkillResponse update(CurrentUser user, UUID id, UpdateSkillRequest req) {
        SkillEntity entity = skills.findById(id).orElseThrow(() -> ApiException.notFound("Skill"));
        if (!user.id().equals(entity.getOwnerUserId())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有属主能改这个 Skill");
        }
        if (req.name() != null) {
            entity.setName(req.name());
        }
        if (req.description() != null) {
            entity.setDescription(req.description());
        }
        if (req.tags() != null) {
            entity.setTags(req.tags().toArray(new String[0]));
        }
        if (req.metadata() != null) {
            entity.setMetadataJson(req.metadata());
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
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
        // 团队/组织可见的 Skill：可见性条件交给 SQL 过滤，
        // 别把不该看到的查出来再在内存里筛——那等于没权限控制。
        List<SkillEntity> rows = skills.search(user.id(), normalize(q), visibility, tag,
            mine, cursorTime, cursorId, PageRequest.of(0, size + 1));
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
     * public 谁都能看；private 只有属主；organization 要组织成员；
     * team 要该团队成员，或该组织的 owner/admin。
     * 外部调用方（版本、制品、审计）都走这里，所以一处判就够。
     *
     * 读与审的口径刻意不同，别混：
     *   读/搜/装 —— 该团队成员；该组织的 owner/admin（{@link Roles#ORG_ADMIN_ROLES}）。
     *   审/批    —— 团队角色 reviewer+，没有团队成员行才回落组织角色（{@link OrgService#roleIn}）。
     *
     * 团队可见的内容归团队管：组织里的普通成员看不到别的团队的 Skill。
     * 但组织的 owner/admin 放行——他们建团队时没有团队成员行，
     * 却在审核队列里看得见并能批准它，「能批不能看」说不通。
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
            case "team" -> (entity.getOwnerTeamId() != null
                    && orgs.isTeamMember(entity.getOwnerTeamId(), user.id()))
                || isOrgAdmin(entity.getOrganizationId(), user.id());
            case "organization" -> orgs.isOrgMember(entity.getOrganizationId(), user.id());
            default -> false;
        };
    }

    /**
     * 我是不是这个组织的管理员（owner/admin）？团队可见的 Skill 对组织管理员放行。
     *
     * 看的是组织角色本身，不看 {@code roleIn}——那个优先取团队角色，
     * 这里要的是「组织里我是不是管理员」这一条。
     * 角色可能为空（不是组织成员），判之前先挡一下：
     * {@code Set.of} 的 contains(null) 会抛 NPE。
     */
    private boolean isOrgAdmin(UUID organizationId, UUID userId) {
        String role = orgs.orgRoleOf(organizationId, userId);
        return role != null && Roles.ORG_ADMIN_ROLES.contains(role);
    }

    /**
     * 创建时的可见性校验：team 必须挂到组织下的某个团队，
     * 组织内可见需要组织成员身份（不能凭空建一个没人看得到的团队 Skill）。
     *
     * 团队/组织可见还要 publisher 及以上——CLI 的检查只是建议，
     * 直接打 API 也得有服务端这道闸。
     */
    private void requireVisibilitySupported(CurrentUser user, CreateSkillRequest req) {
        String visibility = req.visibility();
        if (!Set.of("private", "public", "team", "organization").contains(visibility)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "可见性只能是 private / public / team / organization");
        }
        if ("private".equals(visibility) || "public".equals(visibility)) {
            // private/public 不属于任何组织或团队：带着 organization_id 建出来的
            // Skill 会落进审核队列（队列按 organization_id is not null 过滤），
            // 私有内容就混进组织的审核页里了。
            if (req.organization_id() != null || req.owner_team_id() != null) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "private/public 可见的 Skill 不属于任何组织或团队，别带 organization_id 或 owner_team_id");
            }
            return;
        }
        if (req.organization_id() == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                visibility + " 可见必须指定 organization_id");
        }
        if (!orgs.isOrgMember(req.organization_id(), user.id())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "你不是该组织的成员");
        }
        if ("team".equals(visibility)) {
            if (req.owner_team_id() == null) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "team 可见必须指定 owner_team_id");
            }
            // 团队得挂在同一个组织下，否则「组织角色」与「团队角色」是两回事，
            // 下面的 publisher 判断就无从谈起。
            if (!orgs.teamInOrg(req.organization_id(), req.owner_team_id())) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                    "owner_team_id 必须属于 organization_id");
            }
        }
        // 团队与组织可见都要 publisher 及以上——发到组织是组织范围内的动作。
        requireOrgPublisher(user, req.organization_id());
    }

    /**
     * 发布权：组织角色 publisher 及以上。
     *
     * 判的是组织角色——与 CLI、手册一致。teamId 传 null 是刻意的：
     * roleIn 优先取团队角色，而接受邀请会写一行 team_role=member，
     * 把组织角色永久遮住，手册给的解法（提组织角色）在服务端就不生效了。
     */
    private void requireOrgPublisher(CurrentUser user, UUID organizationId) {
        String role = orgs.roleIn(organizationId, null, user.id());
        if (!OrgService.roleAtLeast(role, Roles.PUBLISHER_ROLE)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN,
                "在该组织里需要 publisher 及以上角色才能发布");
        }
    }

    /**
     * 给已有的 Skill 发新版本要有的发布权。
     *
     * 建 Skill 时按可见性判过一次发布权，但发新版本是另一次发布动作——
     * 只判可见性的话，团队的普通成员也能往团队的待审队列里塞一版内容。
     *
     * private 与 public：只有属主能发。private 的可见性本来就要求属主；
     * public 谁都看得见，若也放开发版，任何登录用户都能往别人的公开 Skill 上
     * 塞一版并顶掉 latest_version——而改名字（PATCH）与撤回都锁属主，
     * 只有发版不锁，契约自相矛盾。
     */
    public void requirePublishRight(CurrentUser user, SkillEntity skill) {
        if ("team".equals(skill.getVisibility())
            || "organization".equals(skill.getVisibility())) {
            requireOrgPublisher(user, skill.getOrganizationId());
            return;
        }
        if (!skill.getOwnerUserId().equals(user.id())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有属主能发新版本");
        }
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
