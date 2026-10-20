package com.skillmesh.api.invite;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.org.OrgRepository;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.OrganizationEntity;
import com.skillmesh.api.org.TeamEntity;
import com.skillmesh.api.org.TeamRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
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
        entity.setOrgRole(orDefault(orgRole, "member"));
        entity.setTeamRole(orDefault(teamRole, "member"));
        entity.setTokenHash(sha256Hex(token));
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

    /** 接受邀请。已接受过就是幂等返回，不报错。 */
    @Transactional
    public Accepted accept(CurrentUser user, String rawToken) {
        InvitationEntity entity = require(rawToken);
        if ("accepted".equals(entity.getStatus())) {
            return toAccepted(entity);
        }
        if (!"pending".equals(entity.getStatus())) {
            throw new ApiException(409, ErrorCodes.INVITATION_INVALID, "邀请已失效");
        }
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (entity.getExpiresAt().isBefore(now)) {
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
        return toAccepted(entity);
    }

    public List<InvitationEntity> list(UUID organizationId) {
        return invitations.findByOrganizationIdOrderByCreatedAtDesc(organizationId);
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
        return invitations.findByTokenHash(sha256Hex(rawToken))
            .orElseThrow(() -> ApiException.notFound("邀请"));
    }

    private static Accepted toAccepted(InvitationEntity entity) {
        return new Accepted(entity.getId(), entity.getOrganizationId(), entity.getTeamId(),
            entity.getOrgRole(), entity.getTeamRole());
    }

    private String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record Created(UUID id, String token, InvitationEntity invitation) {}

    public record Accepted(UUID invitationId, UUID organizationId, UUID teamId,
                           String orgRole, String teamRole) {}
}
