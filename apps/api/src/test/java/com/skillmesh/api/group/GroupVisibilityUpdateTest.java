package com.skillmesh.api.group;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.common.Visibility;
import com.skillmesh.api.group.dto.GroupResponse;
import com.skillmesh.api.group.dto.UpdateGroupRequest;
import com.skillmesh.api.install.InstallationRepository;
import com.skillmesh.api.org.AuthorizationService;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.Permission;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SkillService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 改集合可见性。
 *
 * 纯单测：不启 Spring 上下文，也不连库——这一段是纯判断，
 * 起上下文只会把「库连不上」混进失败原因里。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GroupVisibilityUpdateTest {

    @Mock
    private GroupRepository groups;

    @Mock
    private SkillRepository skills;

    @Mock
    private SkillService skillService;

    @Mock
    private InstallationRepository installations;

    @Mock
    private AuthorizationService auth;

    @Mock
    private OrgService orgs;

    @Mock
    private AuditService audit;

    @InjectMocks
    private GroupService service;

    private CurrentUser owner;
    private GroupEntity entity;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        owner = new CurrentUser(UUID.randomUUID(), "probe-subject", "探针属主", null, null, "user");
        organizationId = UUID.randomUUID();
        entity = new GroupEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug("probe-group");
        entity.setName("探针集合");
        entity.setDescription("");
        entity.setOwnerUserId(owner.id());
        entity.setVisibility(Visibility.PRIVATE);
        entity.setStatus(GroupStatus.active);
        entity.setMemberIds(new UUID[0]);
        when(groups.findById(entity.getId())).thenReturn(Optional.of(entity));
    }

    @Test
    void 有发布权才能把集合放到组织上() {
        GroupResponse response = service.update(owner, entity.getId(),
            new UpdateGroupRequest(null, null, Visibility.ORGANIZATION, organizationId, null, null));

        assertThat(response.visibility()).isEqualTo(Visibility.ORGANIZATION);
        assertThat(entity.getOrganizationId()).isEqualTo(organizationId);
        verify(auth).requirePermission(any(), eq(Permission.GROUP_PUBLISH), eq(organizationId), any());
    }

    @Test
    void 没有发布权就放不出去() {
        doThrow(ApiException.forbidden("缺少权限：发布集合"))
            .when(auth).requirePermission(any(), eq(Permission.GROUP_PUBLISH), any(), any());

        assertThatThrownBy(() -> service.update(owner, entity.getId(),
            new UpdateGroupRequest(null, null, Visibility.ORGANIZATION, organizationId, null, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCodes.FORBIDDEN));
    }

    @Test
    void 改成组织可见却没指定组织会被挡() {
        assertThatThrownBy(() -> service.update(owner, entity.getId(),
            new UpdateGroupRequest(null, null, Visibility.ORGANIZATION, null, null, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code())
                .isEqualTo(ErrorCodes.VALIDATION_FAILED));
    }

    @Test
    void 收回来不需要发布权() {
        entity.setVisibility(Visibility.ORGANIZATION);
        entity.setOrganizationId(organizationId);

        GroupResponse response = service.update(owner, entity.getId(),
            new UpdateGroupRequest(null, null, Visibility.PRIVATE, null, null, null));

        assertThat(response.visibility()).isEqualTo(Visibility.PRIVATE);
        // 可见范围变小就是往回收，归属一并撤掉。
        assertThat(entity.getOrganizationId()).isNull();
        assertThat(entity.getOwnerTeamId()).isNull();
        verify(auth, never()).requirePermission(any(), eq(Permission.GROUP_PUBLISH), any(), any());
    }

    @Test
    void 目标空间里有同名就放不出去() {
        when(groups.existsOrganizationSlug(eq(organizationId), eq("probe-group"), any()))
            .thenReturn(true);

        assertThatThrownBy(() -> service.update(owner, entity.getId(),
            new UpdateGroupRequest(null, null, Visibility.ORGANIZATION, organizationId, null, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo(ErrorCodes.SLUG_TAKEN));
    }

    @Test
    void 团队可见要挂在该组织下的团队上() {
        UUID teamId = UUID.randomUUID();
        when(orgs.teamInOrg(organizationId, teamId)).thenReturn(false);

        assertThatThrownBy(() -> service.update(owner, entity.getId(),
            new UpdateGroupRequest(null, null, Visibility.TEAM, organizationId, teamId, null)))
            .isInstanceOf(ApiException.class)
            .satisfies(e -> assertThat(((ApiException) e).code())
                .isEqualTo(ErrorCodes.VALIDATION_FAILED));
    }

    @Test
    void 不给可见性就只改名称() {
        service.update(owner, entity.getId(),
            new UpdateGroupRequest("新名字", null, null, null, null, List.of()));

        assertThat(entity.getName()).isEqualTo("新名字");
        assertThat(entity.getVisibility()).isEqualTo(Visibility.PRIVATE);
        verify(auth, never()).requirePermission(any(), eq(Permission.GROUP_PUBLISH), any(), any());
    }

    @Test
    void 请求体认得出可见性的取值() throws Exception {
        UpdateGroupRequest req = new ObjectMapper().readValue("""
            {"name":"新名字","visibility":"organization",
             "organization_id":"11111111-1111-1111-1111-111111111111"}
            """, UpdateGroupRequest.class);

        assertThat(req.visibility()).isEqualTo(Visibility.ORGANIZATION);
        assertThat(req.organization_id())
            .isEqualTo(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        assertThat(req.name()).isEqualTo("新名字");
        assertThat(req.skill_ids()).isNull();
    }
}
