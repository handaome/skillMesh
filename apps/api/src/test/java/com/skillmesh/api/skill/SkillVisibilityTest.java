package com.skillmesh.api.skill;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserEntity;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.org.OrgRepository;
import com.skillmesh.api.org.OrganizationEntity;
import com.skillmesh.api.org.Roles;
import com.skillmesh.api.org.TeamEntity;
import com.skillmesh.api.org.TeamRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 可见性的两份口径必须一致。
 *
 * 可见性写了两遍：SQL 片段（{@link VisibleSkills#WHERE}，搜索与审计流水共用）
 * 与 Java 方法（{@link SkillService#canView}，单条读取走它）。
 * 两份各写各的迟早漂移——而且漂的方向通常是漏权限：
 * 搜索里能看见，直读却 404，或者反过来。
 *
 * 这个测试造出各种可见性与成员关系，对每个用户分别用两种口径算一遍，
 * 要求结果完全相同，且等于写死的期望。改了一处而没改另一处，当场就红。
 */
@SpringBootTest
@Transactional
class SkillVisibilityTest {

    @Autowired
    private SkillRepository skills;

    @Autowired
    private SkillService skillService;

    @Autowired
    private UserRepository users;

    @Autowired
    private OrgRepository organizations;

    @Autowired
    private TeamRepository teams;

    @Autowired
    private JdbcTemplate jdbc;

    /** 属主：组织的 owner，几个 Skill 的属主。 */
    private CurrentUser owner;

    /** 组织管理员：不是团队成员，但团队可见的 Skill 对他放行。 */
    private CurrentUser orgAdmin;

    /** 组织普通成员：看得到组织可见的，看不到别的团队的。 */
    private CurrentUser orgMember;

    /** 团队成员：看得到本团队的。 */
    private CurrentUser teamMember;

    /** 外人：什么关系都没有。 */
    private CurrentUser outsider;

    private UUID organizationId;
    private UUID teamId;

    private UUID publicSkill;
    private UUID ownPrivateSkill;
    private UUID otherPrivateSkill;
    private UUID orgSkill;
    private UUID teamSkill;

    @BeforeEach
    void setUp() {
        owner = newUser("属主");
        orgAdmin = newUser("组织管理员");
        orgMember = newUser("组织成员");
        teamMember = newUser("团队成员");
        outsider = newUser("外人");

        organizationId = newOrganization();
        teamId = newTeam();
        // 组织管理员：owner/admin 这一档，团队可见的 Skill 对他放行。
        addOrgMember(orgAdmin, Roles.ADMIN_ROLE);
        addOrgMember(orgMember, Roles.DEFAULT_ROLE);
        // 团队成员同时也是组织成员：团队挂在组织里，两层关系都在。
        addOrgMember(teamMember, Roles.DEFAULT_ROLE);
        addTeamMember(teamMember, Roles.DEFAULT_ROLE);

        publicSkill = newSkill(owner, "public", null, null, "public");
        ownPrivateSkill = newSkill(owner, "own-private", null, null, "private");
        otherPrivateSkill = newSkill(outsider, "other-private", null, null, "private");
        orgSkill = newSkill(owner, "org-visible", organizationId, null, "organization");
        teamSkill = newSkill(owner, "team-visible", organizationId, teamId, "team");
    }

    @Test
    void 两份口径对每个用户都一致() {
        for (CurrentUser user : List.of(owner, orgAdmin, orgMember, teamMember, outsider)) {
            Set<UUID> fromSql = visibleBySql(user);
            Set<UUID> fromJava = visibleByJava(user);
            assertThat(fromSql)
                .as("用户 %s 的 SQL 口径与 Java 口径不一致", user.displayName())
                .isEqualTo(fromJava);
        }
    }

    @Test
    void 可见范围就是写死的那个() {
        // 属主：自己的私有的、公开的、组织可见的、团队的。
        assertThat(visibleByJava(owner)).containsExactlyInAnyOrder(
            publicSkill, ownPrivateSkill, orgSkill, teamSkill);
        // 组织管理员：团队可见的对他放行（他们建团队时没有团队成员行）。
        assertThat(visibleByJava(orgAdmin)).containsExactlyInAnyOrder(
            publicSkill, orgSkill, teamSkill);
        // 组织普通成员：组织可见的能看，别的团队的不行。
        assertThat(visibleByJava(orgMember)).containsExactlyInAnyOrder(
            publicSkill, orgSkill);
        // 团队成员：本团队的能看。
        assertThat(visibleByJava(teamMember)).containsExactlyInAnyOrder(
            publicSkill, orgSkill, teamSkill);
        // 外人：公开的，加上自己那个私有的（属主当然看得见自己的）。
        assertThat(visibleByJava(outsider)).containsExactlyInAnyOrder(
            publicSkill, otherPrivateSkill);
    }

    @Test
    void 别人的私有技能谁也看不见() {
        assertThat(visibleByJava(owner)).doesNotContain(otherPrivateSkill);
        assertThat(visibleByJava(orgAdmin)).doesNotContain(otherPrivateSkill);
        assertThat(visibleByJava(orgMember)).doesNotContain(otherPrivateSkill);
        assertThat(visibleByJava(teamMember)).doesNotContain(otherPrivateSkill);
        assertThat(visibleByJava(outsider)).contains(otherPrivateSkill);
    }

    /**
     * 「有关系」与「看得见」不是一回事。
     *
     * 看得见：公开的谁都能看。有关系：属主、组织成员、团队成员。
     * 往审计流水里写东西要的是后者——公开 Skill 谁都看得见，
     * 光看得见就能往里塞条目，审计台就分不出真假。
     */
    @Test
    void 有关系与看得见分开判() {
        // 外人看得见公开的，但跟它没关系。
        assertThat(skillService.canView(outsider, findSkill(publicSkill))).isTrue();
        assertThat(skillService.isRelated(outsider, findSkill(publicSkill))).isFalse();

        // 属主有双重身份。
        assertThat(skillService.isRelated(owner, findSkill(ownPrivateSkill))).isTrue();
        // 组织成员算有关系。
        assertThat(skillService.isRelated(orgMember, findSkill(orgSkill))).isTrue();
        // 团队成员算有关系。
        assertThat(skillService.isRelated(teamMember, findSkill(teamSkill))).isTrue();
        // 组织管理员不是团队成员，但对团队可见的 Skill 有关系（组织范围内）。
        assertThat(skillService.isRelated(orgAdmin, findSkill(teamSkill))).isTrue();
        // 外人与别人的私有 Skill 既看不见也没关系。
        assertThat(skillService.canView(outsider, findSkill(ownPrivateSkill))).isFalse();
        assertThat(skillService.isRelated(outsider, findSkill(ownPrivateSkill))).isFalse();
    }

    /** SQL 口径：走搜索用的那条查询，可见的就是查得到的。 */
    private Set<UUID> visibleBySql(CurrentUser user) {
        List<SkillEntity> rows = skills.search(user.id(), null, null, null, false,
            null, null, PageRequest.of(0, 1000));
        Set<UUID> all = Set.of(publicSkill, ownPrivateSkill, otherPrivateSkill, orgSkill, teamSkill);
        return rows.stream().map(SkillEntity::getId).filter(all::contains)
            .collect(java.util.stream.Collectors.toSet());
    }

    /** Java 口径：逐个问 {@link SkillService#canView}。 */
    private Set<UUID> visibleByJava(CurrentUser user) {
        Map<UUID, SkillEntity> all = new LinkedHashMap<>();
        for (UUID id : List.of(publicSkill, ownPrivateSkill, otherPrivateSkill, orgSkill, teamSkill)) {
            all.put(id, findSkill(id));
        }
        return all.entrySet().stream()
            .filter(entry -> skillService.canView(user, entry.getValue()))
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
    }

    private SkillEntity findSkill(UUID id) {
        return skills.findById(id).orElseThrow();
    }

    private CurrentUser newUser(String name) {
        UUID id = UUID.randomUUID();
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setOidcIssuer("https://probe.local");
        user.setOidcSubject("probe-" + id);
        user.setDisplayName(name);
        user.setStatus("active");
        user.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        user.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        users.save(user);
        return new CurrentUser(id, user.getOidcSubject(), name, null, "active");
    }

    /**
     * 造一个组织，创建者自动成为 owner。
     *
     * 自己插而不是走 OrgService：它记审计用的是独立事务（REQUIRES_NEW），
     * 独立事务看不见测试事务里还没提交的组织行，审计写失败还会把
     * 外层事务标记成 rollback-only。
     */
    private UUID newOrganization() {
        UUID id = UUID.randomUUID();
        OrganizationEntity org = new OrganizationEntity();
        org.setId(id);
        org.setSlug("probe-org-" + id);
        org.setName("探针组织");
        org.setStatus("active");
        org.setPolicyJson(Map.of());
        org.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        org.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        // 必须 flush：JPA 的 INSERT 是延迟发的，不落库的话
        // 下面插成员行时外键检查找不到这个组织。
        organizations.saveAndFlush(org);
        organizationId = id;
        // 建组织的人自动是 owner。
        addOrgMember(owner, Roles.OWNER_ROLE);
        return id;
    }

    private UUID newTeam() {
        UUID id = UUID.randomUUID();
        TeamEntity team = new TeamEntity();
        team.setId(id);
        team.setOrganizationId(organizationId);
        team.setSlug("probe-team-" + id);
        team.setName("探针团队");
        team.setDescription("测试用");
        team.setStatus("active");
        team.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        team.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        teams.save(team);
        // 落库：插团队成员行时外键检查要能看到这个团队。
        teams.flush();
        teamId = id;
        return id;
    }

    /**
     * 插一行组织成员关系。
     *
     * 直接写 SQL 而不走 OrgService：成员实体是包内私有的，
     * 而 OrgService 记审计走独立事务（REQUIRES_NEW），在测试事务里
     * 审计行写不进去还会把外层事务标记成 rollback-only。
     *
     * JdbcTemplate 拿的是当前事务的连接，所以看得到同一事务里
     * 尚未提交的行——插之前要把用户与组织 flush 出去，
     * 否则外键检查找不到它们。
     */
    private void addOrgMember(CurrentUser user, String role) {
        users.flush();
        organizations.flush();
        jdbc.update("insert into organization_memberships (organization_id, user_id, role, joined_at) "
            + "values (?, ?, ?, now())", organizationId, user.id(), role);
    }

    private void addTeamMember(CurrentUser user, String role) {
        users.flush();
        teams.flush();
        jdbc.update("insert into team_memberships (team_id, user_id, role, joined_at) "
            + "values (?, ?, ?, now())", teamId, user.id(), role);
    }

    private UUID newSkill(CurrentUser skillOwner, String slug, UUID orgId, UUID teamId,
            String visibility) {
        SkillEntity entity = new SkillEntity();
        entity.setId(UUID.randomUUID());
        entity.setSlug(slug + "-" + UUID.randomUUID());
        entity.setName(slug);
        entity.setDescription("测试用");
        entity.setOwnerUserId(skillOwner.id());
        entity.setOrganizationId(orgId);
        entity.setOwnerTeamId(teamId);
        entity.setVisibility(visibility);
        entity.setStatus(SkillStatus.ACTIVE);
        entity.setTags(new String[0]);
        entity.setMetadataJson(Map.of());
        entity.setCreatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));
        return skills.save(entity).getId();
    }
}
