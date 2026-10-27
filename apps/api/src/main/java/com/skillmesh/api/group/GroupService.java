package com.skillmesh.api.group;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.group.dto.GroupRequest;
import com.skillmesh.api.group.dto.GroupResponse;
import com.skillmesh.api.install.InstallationRepository;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 技能集合：命名的一组 Skill。
 *
 * 集合的价值是「这批东西该一起用」——团队把一套规范收成一个集合，
 * 新人一键装齐。集合本身不引入新的可见性口径：成员是 Skill，
 * 可见性由每个 Skill 自己管；徽标只算「我装了几个」，是本人可见的数据。
 */
@Service
public class GroupService {

    private static final List<String> VISIBILITIES =
        List.of("private", "public", "team", "organization");

    private final GroupRepository groups;
    private final SkillRepository skills;
    private final SkillService skillService;
    private final InstallationRepository installations;
    private final OrgService orgs;
    private final AuditService audit;

    public GroupService(GroupRepository groups, SkillRepository skills, SkillService skillService,
                        InstallationRepository installations, OrgService orgs, AuditService audit) {
        this.groups = groups;
        this.skills = skills;
        this.skillService = skillService;
        this.installations = installations;
        this.orgs = orgs;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<GroupResponse> list(CurrentUser user) {
        return groups.findVisible(user.id()).stream().map(entity -> toResponse(user, entity)).toList();
    }

    @Transactional(readOnly = true)
    public GroupResponse get(CurrentUser user, UUID id) {
        return toResponse(user, requireVisible(user, id));
    }

    /** 按 slug 取集合：CLI 与脚本按 slug 引用，跟 Skill 一个道理。 */
    @Transactional(readOnly = true)
    public GroupResponse getBySlug(CurrentUser user, String slug) {
        GroupEntity entity = groups.findVisibleBySlug(user.id(), slug)
            .orElseThrow(() -> ApiException.notFound("集合"));
        return toResponse(user, entity);
    }

    @Transactional
    public GroupResponse create(CurrentUser user, GroupRequest req) {
        String visibility = requireSupportedVisibility(req.visibility());
        if (!VISIBILITIES.contains(visibility)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "visibility 只能是 private / public / team / organization");
        }
        UUID organizationId = req.organization_id();
        UUID teamId = req.team_id();
        if ("team".equals(visibility)) {
            requirePublishRight(user, organizationId, teamId);
        } else if ("organization".equals(visibility)) {
            requirePublishRight(user, organizationId, null);
        } else {
            organizationId = null;
            teamId = null;
        }
        // 个人空间与组织空间各是一个命名空间，同一个 slug 不能各有一个。
        boolean taken = organizationId == null
            ? groups.existsPersonalSlug(user.id(), req.slug())
            : groups.existsOrganizationSlug(organizationId, req.slug());
        if (taken) {
            throw new ApiException(409, ErrorCodes.SLUG_TAKEN, "集合 slug 已被占用: " + req.slug());
        }
        UUID[] members = resolveMembers(user, req.skill_ids());

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        GroupEntity entity = new GroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(req.slug());
        entity.setName(req.name());
        entity.setDescription(req.description() == null ? "" : req.description());
        entity.setOwnerUserId(user.id());
        entity.setOrganizationId(organizationId);
        entity.setOwnerTeamId(teamId);
        entity.setVisibility(visibility);
        entity.setStatus("active");
        entity.setMemberIds(members);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        groups.save(entity);
        audit.record(user, "group.created", "group", entity.getId(), "success",
            Map.of("slug", entity.getSlug(), "members", members.length));
        return toResponse(user, entity);
    }

    @Transactional
    public GroupResponse update(CurrentUser user, UUID id, GroupRequest req) {
        GroupEntity entity = requireOwned(user, id);
        if (req.name() != null && !req.name().isBlank()) {
            entity.setName(req.name());
        }
        if (req.description() != null) {
            entity.setDescription(req.description());
        }
        if (req.skill_ids() != null) {
            entity.setMemberIds(resolveMembers(user, req.skill_ids()));
        }
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        groups.save(entity);
        audit.record(user, "group.updated", "group", entity.getId(), "success",
            Map.of("slug", entity.getSlug()));
        return toResponse(user, entity);
    }

    /**
     * 归档集合。
     *
     * 软删除：集合是别人也在用的东西，直接抹掉会让人手里的引用断掉。
     * 归档后 slug 让出来，可以重建一个同名的。
     */
    @Transactional
    public void archive(CurrentUser user, UUID id) {
        GroupEntity entity = requireOwned(user, id);
        entity.setStatus("archived");
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        groups.save(entity);
        audit.record(user, "group.archived", "group", entity.getId(), "success",
            Map.of("slug", entity.getSlug()));
    }

    /** 集合的成员 Skill：我装了几个。 */
    @Transactional(readOnly = true)
    public List<GroupResponse.Member> members(CurrentUser user, UUID id) {
        return toResponse(user, requireVisible(user, id)).members();
    }

    private GroupEntity requireVisible(CurrentUser user, UUID id) {
        GroupEntity entity = groups.findById(id).orElseThrow(() -> ApiException.notFound("集合"));
        // 看不见的集合按「不存在」报：说「存在但你无权」等于告诉外人这东西存在。
        if (!canView(user, entity)) {
            throw ApiException.notFound("集合");
        }
        return entity;
    }

    private GroupEntity requireOwned(CurrentUser user, UUID id) {
        GroupEntity entity = requireVisible(user, id);
        if (!user.id().equals(entity.getOwnerUserId())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有属主能改这个集合");
        }
        return entity;
    }

    /** 可见性判定，与 Skill 同一套口径。 */
    private boolean canView(CurrentUser user, GroupEntity entity) {
        if ("public".equals(entity.getVisibility())) {
            return true;
        }
        if (user.id().equals(entity.getOwnerUserId())) {
            return true;
        }
        if ("organization".equals(entity.getVisibility())) {
            return orgs.roleIn(entity.getOrganizationId(), null, user.id()) != null
                && !orgs.roleIn(entity.getOrganizationId(), null, user.id()).isEmpty();
        }
        if ("team".equals(entity.getVisibility())) {
            String role = orgs.roleIn(entity.getOrganizationId(), entity.getOwnerTeamId(), user.id());
            return role != null && !role.isEmpty();
        }
        return false;
    }

    /** 发集合要发布权：能往组织里塞东西的人，才能把集合挂到组织上。 */
    private void requirePublishRight(CurrentUser user, UUID organizationId, UUID teamId) {
        if (organizationId == null) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "团队集合要指定组织");
        }
        String role = orgs.roleIn(organizationId, teamId, user.id());
        if (role == null || role.isEmpty() || !OrgService.roleAtLeast(role, Roles.PUBLISHER_ROLE)) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "需要 publisher 及以上角色");
        }
    }

    private static String requireSupportedVisibility(String visibility) {
        return visibility == null ? "" : visibility.trim();
    }

    /**
     * 成员只能是当前用户可见的 Skill。
     *
     * 往集合里塞看不见的 Skill，等于借集合把它带出去——那是个越权入口。
     */
    private UUID[] resolveMembers(CurrentUser user, List<UUID> skillIds) {
        if (skillIds == null || skillIds.isEmpty()) {
            return new UUID[0];
        }
        Set<UUID> unique = new LinkedHashSet<>(skillIds);
        for (UUID skillId : unique) {
            skillService.requireVisibleSkill(user, skillId);
        }
        return unique.toArray(new UUID[0]);
    }

    private GroupResponse toResponse(CurrentUser user, GroupEntity entity) {
        UUID[] memberIds = entity.getMemberIds() == null ? new UUID[0] : entity.getMemberIds();
        Set<UUID> installed = installedSkillIds(user);
        List<GroupResponse.Member> members = new ArrayList<>();
        for (UUID skillId : memberIds) {
            SkillEntity skill = skills.findById(skillId).orElse(null);
            if (skill == null) {
                // 成员被删了：集合里留着 id 没用，展示时跳过。
                continue;
            }
            members.add(new GroupResponse.Member(
                skill.getId(), skill.getSlug(), skill.getName(),
                skill.getLatestVersion(), installed.contains(skillId)));
        }
        int installedCount = 0;
        for (GroupResponse.Member member : members) {
            if (member.installed()) {
                installedCount++;
            }
        }
        return new GroupResponse(entity.getId(), entity.getSlug(), entity.getName(),
            entity.getDescription(), entity.getVisibility(),
            members.size(), installedCount, members);
    }

    /** 我装了的 Skill：只看得到自己机器上的记录。 */
    private Set<UUID> installedSkillIds(CurrentUser user) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (var row : installations.findByUserIdOrderByLastUpdatedAtDesc(user.id())) {
            if ("active".equals(row.getStatus())) {
                ids.add(row.getSkillId());
            }
        }
        return ids;
    }
}
