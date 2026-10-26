package com.skillmesh.api.invite;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ContentDigest;
import com.skillmesh.api.common.CursorCodec;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.invite.dto.InvitationPage;
import com.skillmesh.api.invite.dto.InvitationSummary;
import com.skillmesh.api.org.OrgRepository;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.OrganizationEntity;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.org.TeamEntity;
import com.skillmesh.api.org.TeamRepository;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邀请：把第二个人拉进组织/团队（设计文档 §10 团队管理）。
 *
 * 明文 token 只在创建时返回一次，库里只存 SHA-256——
 * 拿到库的人不能凭它进团队。
 */
@Service
public class InvitationService {

    private static final int TOKEN_BYTES = 32;
    private static final long DEFAULT_TTL_DAYS = 7;
    /** 一页默认给多少条邀请。 */
    private static final int DEFAULT_LIMIT = 20;
    /** 上限：再大就是「一次把全量拉回来」，那不是分页。 */
    private static final int MAX_LIMIT = 100;

    private final InvitationRepository invitations;
    private final OrgService orgs;
    private final OrgRepository organizations;
    private final TeamRepository teams;
    private final AuditService audit;
    private final String webBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public InvitationService(InvitationRepository invitations, OrgService orgs,
                             OrgRepository organizations, TeamRepository teams,
                             AuditService audit,
                             @Value("${skillmesh.web.base-url:http://127.0.0.1:5174}") String webBaseUrl) {
        this.invitations = invitations;
        this.orgs = orgs;
        this.organizations = organizations;
        this.teams = teams;
        this.audit = audit;
        this.webBaseUrl = webBaseUrl;
    }

    /** 创建邀请。返回的 token 是明文，只此一次。 */
    @Transactional
    public Created create(CurrentUser actor, UUID organizationId, UUID teamId,
                          String orgRole, String teamRole, String subject) {
        orgs.requireOrgAdmin(actor, organizationId);
        String role = orDefault(orgRole, Roles.DEFAULT_ROLE);
        if (!Roles.INVITABLE_ORG_ROLES.contains(role)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "owner 角色不能通过邀请授予，可授予的是 " + String.join(" / ", Roles.INVITABLE_ORG_RANK));
        }
        // 团队角色在服务层也要校验：DTO 的 @Pattern 只管 HTTP 这一道，
        // 别的调用方（内部调用、脚本）走不到那儿，脏值会撞 DDL 的 CHECK 变成 500。
        String teamRoleValue = orDefault(teamRole, Roles.DEFAULT_ROLE);
        if (!Roles.TEAM_ROLES.contains(teamRoleValue)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "团队角色只能是 " + String.join(" / ", Roles.TEAM_RANK));
        }
        if (teamId == null && teamRole != null && !teamRole.isBlank()) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "给了团队角色就必须给团队");
        }
        if (teamId != null) {
            TeamEntity team = teams.findById(teamId)
                .orElseThrow(() -> ApiException.notFound("团队"));
            if (!team.getOrganizationId().equals(organizationId)) {
                throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "团队不属于该组织");
            }
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        String token = newToken();

        InvitationEntity entity = new InvitationEntity();
        entity.setId(UUID.randomUUID());
        entity.setOrganizationId(organizationId);
        entity.setTeamId(teamId);
        entity.setOrgRole(role);
        entity.setTeamRole(teamRoleValue);
        entity.setTokenHash(ContentDigest.sha256(token));
        entity.setStatus("pending");
        entity.setInvitedBy(actor.id());
        entity.setSubject(subject);
        entity.setCreatedAt(now);
        entity.setExpiresAt(now.plusDays(DEFAULT_TTL_DAYS));
        invitations.save(entity);

        audit.record(actor, "invitation.create", "invitation", entity.getId(), "success",
            Map.of("organization_id", organizationId.toString(),
                "team_id", teamId == null ? "" : teamId.toString()));
        return new Created(entity.getId(), token, entity);
    }

    /** 接受邀请。同一个人重复接受是幂等的；别人再来是 409。 */
    @Transactional
    public Accepted accept(CurrentUser user, String rawToken) {
        // 锁住邀请行：接受是「读状态 → 写成员行 → 翻状态」三步，
        // 不串行化的话两个并发请求会同时读到 pending、同时写成员关系——
        // 一份单次令牌把两个人拉进组织。抢不到的一方在这里排队，
        // 等前面那个提交完再读到新状态，按下面的契约给 409 或幂等 200。
        InvitationEntity entity = requireForUpdate(rawToken);
        if ("accepted".equals(entity.getStatus())) {
            // 真正的幂等只有「同一个人」成立：别人拿着链接再来，
            // 他根本没进组，却会拿到一句「已加入」。
            if (user.id().equals(entity.getAcceptedBy())) {
                return toAccepted(entity, user.id());
            }
            throw new ApiException(409, ErrorCodes.INVITATION_INVALID, "邀请已被接受");
        }
        if (!"pending".equals(entity.getStatus())) {
            throw new ApiException(409, ErrorCodes.INVITATION_INVALID, "邀请已失效");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (isExpired(entity, now)) {
            throw new ApiException(409, ErrorCodes.INVITATION_INVALID, "邀请已过期");
        }
        if (entity.getSubject() != null && !entity.getSubject().isBlank()
            && !entity.getSubject().equals(user.subject())) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "这份邀请不是发给你的");
        }
        orgs.upsertOrgMember(user, entity.getOrganizationId(), user.id(), entity.getOrgRole());
        if (entity.getTeamId() != null) {
            orgs.upsertTeamMember(user, entity.getTeamId(), user.id(), entity.getTeamRole());
        }
        entity.setStatus("accepted");
        entity.setAcceptedBy(user.id());
        entity.setAcceptedAt(now);
        invitations.save(entity);
        audit.record(user, "invitation.accept", "invitation", entity.getId(), "success",
            Map.of("organization_id", entity.getOrganizationId().toString()));
        return toAccepted(entity, user.id());
    }

    /**
     * 这份邀请现在是什么状态。过期按时间算，不改库里的 status。
     * 摘要页与接受邀请用同一套口径。
     */
    public String effectiveStatus(InvitationEntity entity) {
        if ("pending".equals(entity.getStatus()) && isExpired(entity, OffsetDateTime.now(ZoneOffset.UTC))) {
            return "expired";
        }
        return entity.getStatus();
    }

    private static boolean isExpired(InvitationEntity entity, OffsetDateTime now) {
        return entity.getExpiresAt().isBefore(now);
    }

    /**
     * 组织下的邀请列表（一页）。
     *
     * 游标分页：按 (created_at, id) 倒序翻页，跟技能列表同一套口径。
     * 邀请只撤销不删，越攒越多——一次全查回来迟早把这个页面拖垮，
     * 而前端再 slice 一刀只是把「多」藏起来，不是解决。
     *
     * 组织与团队一次批量查齐：逐条查就是 2N 次往返，本机到远程库一次往返几十毫秒，
     * 一个组织攒下几十份邀请就够这个页面卡住。
     */
    @Transactional(readOnly = true)
    public InvitationPage listSummaries(UUID organizationId, Integer limit, String cursor) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        OffsetDateTime cursorTime = null;
        UUID cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            CursorCodec.Cursor decoded = CursorCodec.decode(cursor);
            cursorTime = decoded.time();
            cursorId = decoded.id();
        }
        List<InvitationEntity> rows = invitations.pageByOrganizationId(organizationId,
            cursorTime, cursorId, PageRequest.of(0, size + 1));
        boolean hasMore = rows.size() > size;
        List<InvitationEntity> page = hasMore ? rows.subList(0, size) : rows;
        Map<UUID, OrganizationEntity> orgById = organizations.findAllById(
                page.stream().map(InvitationEntity::getOrganizationId).toList())
            .stream().collect(Collectors.toMap(OrganizationEntity::getId, org -> org));
        Map<UUID, TeamEntity> teamById = teams.findAllById(
                page.stream().map(InvitationEntity::getTeamId).filter(Objects::nonNull).toList())
            .stream().collect(Collectors.toMap(TeamEntity::getId, team -> team));
        List<InvitationSummary> out = new ArrayList<>(page.size());
        for (InvitationEntity row : page) {
            out.add(toSummary(row, orgById.get(row.getOrganizationId()),
                row.getTeamId() == null ? null : teamById.get(row.getTeamId())));
        }
        String next = null;
        if (hasMore && !page.isEmpty()) {
            InvitationEntity last = page.get(page.size() - 1);
            next = CursorCodec.encode(last.getCreatedAt(), last.getId());
        }
        return new InvitationPage(out, next, hasMore);
    }

    /** 单份邀请的摘要（公开页用）。 */
    public InvitationSummary summarize(InvitationEntity entity) {
        return toSummary(entity, organizationOf(entity), teamOf(entity));
    }

    /** 邀请 → 摘要。列表与公开页共用一份映射，字段口径不会各说各话。 */
    private InvitationSummary toSummary(InvitationEntity entity, OrganizationEntity org,
                                        TeamEntity team) {
        return new InvitationSummary(entity.getId(),
            org == null ? null : org.getName(),
            team == null ? null : team.getName(),
            entity.getOrgRole(), entity.getTeamRole(),
            effectiveStatus(entity), entity.getExpiresAt(), entity.getCreatedAt());
    }

    @Transactional
    public void revoke(CurrentUser actor, UUID organizationId, UUID invitationId) {
        orgs.requireOrgAdmin(actor, organizationId);
        InvitationEntity entity = invitations.findById(invitationId)
            .orElseThrow(() -> ApiException.notFound("邀请"));
        if (!entity.getOrganizationId().equals(organizationId)) {
            throw ApiException.notFound("邀请");
        }
        entity.setStatus("revoked");
        invitations.save(entity);
        audit.record(actor, "invitation.revoke", "invitation", entity.getId(), "success", Map.of());
    }

    /** 公开摘要。不存在/已撤销都当 404，不告诉调用方到底是哪个原因。 */
    @Transactional(readOnly = true)
    public InvitationEntity summary(String rawToken) {
        InvitationEntity entity = require(rawToken);
        if ("revoked".equals(entity.getStatus())) {
            throw ApiException.notFound("邀请");
        }
        return entity;
    }

    /** 组织成员才能看这个组织的邀请列表。 */
    public void requireOrgMember(CurrentUser user, UUID organizationId) {
        if (!orgs.isOrgMember(organizationId, user.id())) {
            throw ApiException.notFound("组织");
        }
    }

    public OrganizationEntity organizationOf(InvitationEntity entity) {
        return organizations.findById(entity.getOrganizationId()).orElse(null);
    }

    public TeamEntity teamOf(InvitationEntity entity) {
        return entity.getTeamId() == null ? null : teams.findById(entity.getTeamId()).orElse(null);
    }

    public String inviteUrl(String token) {
        return webBaseUrl.replaceAll("/$", "") + "/invite/" + token;
    }

    private InvitationEntity require(String rawToken) {
        return invitations.findByTokenHash(ContentDigest.sha256(rawToken))
            .orElseThrow(() -> ApiException.notFound("邀请"));
    }

    /** 锁住邀请行再读——接受邀请必须走这条。 */
    private InvitationEntity requireForUpdate(String rawToken) {
        return invitations.findByTokenHashForUpdate(ContentDigest.sha256(rawToken))
            .orElseThrow(() -> ApiException.notFound("邀请"));
    }

    /**
     * 响应里的角色取落库后的实际角色，不是邀请上写的那一档。
     *
     * 邀请说 member，接受者本来就是 publisher——响应说 member，客户端就会显示错角色
     * （设计文档 §5.1：已是组织成员 → 200 直接返回当前角色）。
     */
    private Accepted toAccepted(InvitationEntity entity, UUID userId) {
        String orgRole = orgs.orgRoleOf(entity.getOrganizationId(), userId);
        String teamRole = entity.getTeamId() == null ? null
            : orgs.teamRoleOf(entity.getTeamId(), userId);
        return new Accepted(entity.getId(), entity.getOrganizationId(), entity.getTeamId(),
            orgRole == null ? entity.getOrgRole() : orgRole,
            teamRole == null ? entity.getTeamRole() : teamRole);
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Created(UUID id, String token, InvitationEntity invitation) {}

    public record Accepted(UUID invitationId, UUID organizationId, UUID teamId,
                           String orgRole, String teamRole) {}
}
