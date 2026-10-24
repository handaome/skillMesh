package com.skillmesh.api.org;

import static org.assertj.core.api.Assertions.assertThat;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.QueryCounter;
import jakarta.persistence.EntityManagerFactory;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 成员列表别把 N+1 放回来（远程库一次往返几十毫秒，N+1 直接等于用户等待时间）。
 *
 * 关联数据批量查齐之后，页面发起的查询条数应该跟条目数无关。
 */
@SpringBootTest
@ActiveProfiles("test")
class OrgMemberQueryCountTest {

    @Autowired private OrgService orgs;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private CurrentUser owner;

    @BeforeEach
    void 准备() {
        cleaner.clean();
        owner = insertUser("owner");
    }

    /**
     * 成员多起来时，查询条数不该跟着涨。
     *
     * 一个成员时查 2 条（成员行 + 批量查用户），六个成员还是 2 条——
     * 涨就说明有人又把逐个查用户写回来了。
     */
    @Test
    void 成员列表一次查齐用户() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        for (String name : new String[] {"beta", "gamma", "delta"}) {
            CurrentUser member = insertUser(name);
            orgs.addOrgMember(owner, orgId, member.id(), "member");
            orgs.addTeamMember(owner, teamId, member.id(), "member");
        }

        QueryCounter counter = new QueryCounter(entityManagerFactory);
        // 组织成员 4 个（owner + 三个），团队成员 3 个（owner 没进团队）。
        long orgQueries = counter.count(() ->
            assertThat(orgs.orgMembers(orgId)).as("组织成员都在列表里").hasSize(4));
        long teamQueries = counter.count(() ->
            assertThat(orgs.teamMembers(teamId)).as("团队成员都在列表里").hasSize(3));

        // 成员行一次 + 用户批量一次 = 2 条，与成员数无关。
        assertThat(orgQueries).as("成员多了，组织成员列表的查询条数不该跟着涨")
            .isEqualTo(2);
        assertThat(teamQueries).as("成员多了，团队成员列表的查询条数不该跟着涨")
            .isEqualTo(2);
    }

    /** /me 返回的组织列表也别把 N+1 放回来。 */
    @Test
    void 我的组织列表一次查齐组织() throws Exception {
        orgs.createOrganization(owner, "acme", "Acme").getId();
        orgs.createOrganization(owner, "beta", "Beta").getId();

        QueryCounter counter = new QueryCounter(entityManagerFactory);
        long queries = counter.count(() ->
            assertThat(orgs.myOrganizations(owner.id())).as("两个组织都在列表里").hasSize(2));

        assertThat(queries).as("组织多了，查询条数不该跟着涨").isEqualTo(2);
    }

    /** 造一个真实用户行——测试里只有这一个用户时，N+1 藏不住。 */
    private CurrentUser insertUser(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, ?, 'active')", id, name + "-" + id, name);
        return new CurrentUser(id, name + "-" + id, name, null);
    }
}
