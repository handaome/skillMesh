# SkillMesh v2 团队控制台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 打通「两个人用 SkillMesh 协作」这条闭环——浏览器登录、邀请入组、团队发布、审核放行、成员安装。

**Architecture:** 服务端加一张 `org_invitations` 表与邀请/成员/审核队列三类端点，可见性条件抽成共用片段；Web 端加 OIDC 授权码 + PKCE 登录与四个管理页面（手写 PKCE，不引 `oidc-client-ts`）；CLI 只放开 `publish --team/--org`，其余命令不动。

**Tech Stack:** Java 21 · Spring Boot 3.5.3 · Spring Data JPA · Flyway · PostgreSQL 18.4 · MinIO · Go 1.24 + cobra · Vue 3 + Vite + TypeScript + vitest + vue-router

**上游 spec：** [docs/superpowers/specs/2026-10-20-v2-team-console-design.md](../specs/2026-10-20-v2-team-console-design.md)

## Global Constraints

以下约束适用于每个任务，不再逐条重复：

- **API 基址**：`http://localhost:8080/api/v1`，所有路由挂在此前缀下。JSON 字段 `snake_case`；错误用 problem+json，客户端只按 `code` 分支。
- **一个文件一个类**：Java 每文件一个类，DTO 用 record；Go 一个文件一个职责；Vue 一个组件一个文件。
- **测试库**：独立**数据库** `skillmesh_test`（不是 schema），与应用的 `skill_mesh` 库完全隔离；表在它的 `public` schema 里。测试 SQL 用裸表名（`truncate table artifacts, users cascade`）或 `current_schema()`，**不要写 `skillmesh_test.xxx`**——那是把库名当 schema 用了。
- **可见性只有一份**：`com.skillmesh.api.skill.VisibleSkills.WHERE` 是「我能不能看见这个 Skill」的唯一定义，**搜索与审计共用**，不要在别处另写一份。审核队列问的是另一件事——「我能不能**审**这个版本」，那是角色等级，走 `OrgService.roleAtLeast`，两者别混。
- **角色等级只有一份**：`OrgService.roleAtLeast(actual, required)`，等级序 `owner > admin > publisher > reviewer > member > viewer`。判断「reviewer 及以上」一律走它，不要在 SQL 里硬编码角色列表。
- **不推送**：`git add` / `git commit` 直接做，`git push` 必须用户明确授权。
- **端口**：API `127.0.0.1:8080`、MinIO `127.0.0.1:9100`、Keycloak `127.0.0.1:8180/kc`、Vite dev `127.0.0.1:5174`、CLI OAuth 回调 `127.0.0.1:53682`。
- **制品是 zip**：CLI 用 `archive/zip` 打包，`content_type` 是 `application/zip`，服务端用 `ZipInputStream` 读。别按 tar 处理。
- **diff 口径对齐 CLI**（`apps/cli/internal/cmd/diff.go`）：二进制判定＝前 8192 字节含 NUL；按 `\n` 切行；`旧行数 × 新行数 > 4_000_000` 时退化为只报文件级；增删行数之和 > 200 时标 `truncated`（**数字仍然给出**）。
- **前端组件库**：**Element Plus**（`element-plus` + `@element-plus/icons-vue`）。表格、表单、按钮、徽标、空态、告警条一律用 `el-*` 组件，不自己撸样式。不引 Tailwind/UnoCSS。
- **自有 CSS 只留布局与工具类**：放 `apps/web/src/styles/app.css` 一个文件（顶栏、内容区、间距工具类），不建多文件 CSS 库。需要改组件库外观时用 Element Plus 的 CSS 变量，不写 `!important`。

---

## File Structure

```text
apps/api/src/main/java/com/skillmesh/api/
├── invite/                          # 新增：邀请
│   ├── InvitationEntity.java        # org_invitations 映射
│   ├── InvitationRepository.java
│   ├── InvitationService.java       # 创建/接受/撤销，明文 token 只在创建时出现
│   ├── InvitationController.java    # 5 个端点
│   └── dto/
│       ├── CreateInvitationRequest.java
│       ├── InvitationResponse.java  # 创建响应（含 invite_url 与明文 token）
│       ├── InvitationSummary.java   # 列表/公开视图
│       └── AcceptInvitationResponse.java
├── diff/                            # 新增：版本间文件级差异
│   ├── FileChange.java              # record：path/kind/added/removed/truncated
│   ├── ManifestDiff.java            # 纯函数：两个 manifest → 文件级变更
│   ├── LineDiff.java                # 纯函数：LCS 行统计
│   └── ArtifactDiffService.java     # 取两个版本的制品、解包、比差异
├── org/dto/MemberResponse.java      # 新增
├── review/ReviewQueueService.java   # 新增
├── review/ReviewQueueController.java # 新增
├── review/dto/                      # 新增
│   ├── PendingReviewItem.java
│   ├── ReviewDetailResponse.java
│   ├── ReviewScanSummary.java
│   └── ReviewSourceSummary.java
├── skill/VisibleSkills.java         # 已有：可见性条件常量
└── common/ErrorCodes.java           # 改：加 INVITATION_INVALID

apps/web/src/
├── auth/
│   ├── pkce.ts                      # 新增：PKCE + token 交换（约 80 行，不引依赖）
│   └── session.ts                   # 新增：会话单例 + 静默续期
├── router.ts                        # 新增
├── styles/app.css                   # 新增：布局与工具类（组件外观交给 Element Plus）
├── pages/
│   ├── LoginPage.vue                # 新增
│   ├── InvitePage.vue               # 新增
│   ├── MarketPage.vue               # 新增（现 SearchPage + DetailPage 合并）
│   ├── TeamPage.vue                 # 新增
│   ├── ReviewQueuePage.vue          # 新增
│   └── ReviewDetailPage.vue         # 新增
└── App.vue                          # 改：布局 + router-view，删掉手贴 token 框
```

---

## Slice 1：服务端

### Task 1: `org_invitations` 表与实体

**Files:**

- Create: `db/migrations/V3__team_console.sql`
- Create: `apps/api/src/main/resources/db/migrations/V3__team_console.sql`（与 `db/` 那份逐字相同）
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/InvitationEntity.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/InvitationRepository.java`
- Modify: `apps/api/src/test/java/com/skillmesh/api/FlywayMigrationTest.java`（表数 19 → 20）

**Interfaces:**

- Consumes: 无（首个任务）
- Produces:
  - 表 `org_invitations`（20 张表里的第 20 张）
  - `InvitationEntity` — 字段 `id / organizationId / teamId / orgRole / teamRole / tokenHash / status / invitedBy / acceptedBy / subject / createdAt / expiresAt / acceptedAt`
  - `InvitationRepository extends JpaRepository<InvitationEntity, UUID>`，方法：
    - `Optional<InvitationEntity> findByTokenHash(String tokenHash)`
    - `List<InvitationEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId)`

- [ ] **Step 1: 写迁移 SQL**

`db/migrations/V3__team_console.sql`：

```sql
-- v2：团队控制台——邀请成员进组织/团队。

CREATE TABLE org_invitations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id uuid NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    team_id         uuid REFERENCES teams(id) ON DELETE CASCADE,
    org_role        text NOT NULL DEFAULT 'member'
                    CHECK (org_role IN ('owner', 'admin', 'publisher', 'reviewer', 'member', 'viewer')),
    team_role       text NOT NULL DEFAULT 'member'
                    CHECK (team_role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer')),
    token_hash      char(64) NOT NULL UNIQUE,
    status          text NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending', 'accepted', 'revoked', 'expired')),
    invited_by      uuid REFERENCES users(id) ON DELETE SET NULL,
    accepted_by     uuid REFERENCES users(id) ON DELETE SET NULL,
    subject         text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    expires_at      timestamptz NOT NULL,
    accepted_at     timestamptz
);
CREATE INDEX idx_org_invitations_org ON org_invitations(organization_id, status);
```

复制到 `apps/api/src/main/resources/db/migrations/V3__team_console.sql`：

```bash
cp db/migrations/V3__team_console.sql apps/api/src/main/resources/db/migrations/V3__team_console.sql
```

- [ ] **Step 2: 改迁移测试的表数断言**

`apps/api/src/test/java/com/skillmesh/api/FlywayMigrationTest.java` 里把 `hasSize(19)` 改成 `hasSize(20)`，并把 `"audit_events"` 后面加上 `"org_invitations"`：

```java
        assertThat(tables).contains(
            "users", "organizations", "organization_memberships", "teams", "team_memberships",
            "skills", "skill_sources", "artifacts", "skill_versions", "skill_scans",
            "skill_reviews", "skill_permissions", "devices", "installations",
            "sync_states", "sync_changesets", "sync_conflicts", "upload_sessions",
            "audit_events", "org_invitations");
        assertThat(tables).hasSize(20);
```

- [ ] **Step 3: 跑测试确认失败**

```bash
cd apps/api && ./mvnw -q -Dtest=FlywayMigrationTest test
```

Expected：FAIL——`org_invitations` 不在表列表里（迁移文件还没生效）或表数是 19。

- [ ] **Step 4: 写实体**

`apps/api/src/main/java/com/skillmesh/api/invite/InvitationEntity.java`：

```java
package com.skillmesh.api.invite;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * org_invitations 表：把第二个人拉进组织/团队的凭据。
 *
 * 只存 token 的 SHA-256——明文只在创建时返回一次。
 */
@Entity
@Table(name = "org_invitations")
public class InvitationEntity {

    @Id
    private UUID id;

    @Column(name = "organization_id", nullable = false)
    private UUID organizationId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "org_role", nullable = false)
    private String orgRole;

    @Column(name = "team_role", nullable = false)
    private String teamRole;

    // 显式指定 CHAR 才过得了 schema 校验。
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "token_hash", length = 64, nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private String status;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "accepted_by")
    private UUID acceptedBy;

    @Column
    private String subject;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "accepted_at")
    private OffsetDateTime acceptedAt;

    public UUID getId() { return id; }
    public void setId(UUID v) { this.id = v; }
    public UUID getOrganizationId() { return organizationId; }
    public void setOrganizationId(UUID v) { this.organizationId = v; }
    public UUID getTeamId() { return teamId; }
    public void setTeamId(UUID v) { this.teamId = v; }
    public String getOrgRole() { return orgRole; }
    public void setOrgRole(String v) { this.orgRole = v; }
    public String getTeamRole() { return teamRole; }
    public void setTeamRole(String v) { this.teamRole = v; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String v) { this.tokenHash = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public UUID getInvitedBy() { return invitedBy; }
    public void setInvitedBy(UUID v) { this.invitedBy = v; }
    public UUID getAcceptedBy() { return acceptedBy; }
    public void setAcceptedBy(UUID v) { this.acceptedBy = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(OffsetDateTime v) { this.createdAt = v; }
    public OffsetDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(OffsetDateTime v) { this.expiresAt = v; }
    public OffsetDateTime getAcceptedAt() { return acceptedAt; }
    public void setAcceptedAt(OffsetDateTime v) { this.acceptedAt = v; }
}
```

- [ ] **Step 5: 写仓储**

`apps/api/src/main/java/com/skillmesh/api/invite/InvitationRepository.java`：

```java
package com.skillmesh.api.invite;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvitationRepository extends JpaRepository<InvitationEntity, UUID> {

    Optional<InvitationEntity> findByTokenHash(String tokenHash);

    List<InvitationEntity> findByOrganizationIdOrderByCreatedAtDesc(UUID organizationId);
}
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd apps/api && ./mvnw -q -Dtest=FlywayMigrationTest test
```

Expected：PASS（20 张表）。

- [ ] **Step 7: 提交**

```bash
git add db/migrations apps/api/src/main/resources/db/migrations apps/api/src/main/java/com/skillmesh/api/invite apps/api/src/test/java/com/skillmesh/api/FlywayMigrationTest.java
git commit -m "feat(api): 邀请表与实体（v2 团队控制台）"
```

---

### Task 2: 邀请服务与端点

**Files:**

- Create: `apps/api/src/main/java/com/skillmesh/api/invite/InvitationService.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/InvitationController.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/dto/CreateInvitationRequest.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/dto/InvitationResponse.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/dto/InvitationSummary.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/invite/dto/AcceptInvitationResponse.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/common/ErrorCodes.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/config/SecurityConfig.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/org/OrgService.java`（加 `requireOrgAdmin` / `requireTeamAdmin` / `upsertOrgMember` / `upsertTeamMember`）
- Modify: `apps/api/src/main/resources/application.yml`（加 `skillmesh.web.base-url`）
- Test: `apps/api/src/test/java/com/skillmesh/api/invite/InvitationFlowTest.java`

**Interfaces:**

- Consumes: Task 1 的 `InvitationEntity` / `InvitationRepository`
- Produces:
  - `InvitationService.create(CurrentUser actor, UUID organizationId, UUID teamId, String orgRole, String teamRole, String subject) → InvitationService.Created`
    - `Created` 是 record：`(UUID id, String token, InvitationEntity invitation)`
  - `InvitationService.accept(CurrentUser user, String rawToken) → InvitationService.Accepted`
    - `Accepted` 是 record：`(UUID invitationId, UUID organizationId, UUID teamId, String orgRole, String teamRole)`
  - `InvitationService.list(UUID organizationId) → List<InvitationEntity>`
  - `InvitationService.revoke(CurrentUser actor, UUID organizationId, UUID invitationId)`
  - `InvitationService.summary(String rawToken) → InvitationEntity`（不抛「已撤销」以外的异常）
  - `InvitationService.requireOrgMember(CurrentUser user, UUID organizationId)`
  - `OrgService.requireOrgAdmin(CurrentUser user, UUID organizationId)`
  - `OrgService.requireTeamAdmin(CurrentUser user, UUID teamId)`
  - `OrgService.upsertOrgMember(CurrentUser actor, UUID organizationId, UUID memberId, String role)` — **授予 `owner` 角色时，操作者本人必须是该组织的 owner**。admin 能任命 owner 就等于能把自己的人提上去再把人踢走；owner 只能由 owner 任命。
  - `OrgService.upsertTeamMember(CurrentUser actor, UUID teamId, UUID memberId, String role)`
  - 新错误码 `ErrorCodes.INVITATION_INVALID` = `"INVITATION_INVALID"`

- [ ] **Step 1: 加错误码**

`apps/api/src/main/java/com/skillmesh/api/common/ErrorCodes.java` 里在 `SLUG_TAKEN` 后面加一行：

```java
    public static final String INVITATION_INVALID = "INVITATION_INVALID";
```

- [ ] **Step 2: 放行公开的邀请查看端点**

`apps/api/src/main/java/com/skillmesh/api/config/SecurityConfig.java` 的 `authorizeHttpRequests` 里，在 `.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()` 后面加一行：

```java
                // 邀请摘要页要给还没登录的人看。* 不跨 /，所以 /invitations/{token}/accept 仍需登录。
                .requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
```

- [ ] **Step 3: 加 Web 基址配置**

`apps/api/src/main/resources/application.yml` 的 `skillmesh:` 段下加：

```yaml
skillmesh:
  web:
    base-url: ${SM_WEB_BASE_URL:http://127.0.0.1:5174}
```

- [ ] **Step 4: 给 OrgService 加成员落位与鉴权方法**

`apps/api/src/main/java/com/skillmesh/api/org/OrgService.java` 末尾（`private static String` 之类之前）加：

```java
    /** 组织管理员以上才能管成员与邀请。 */
    public void requireOrgAdmin(CurrentUser user, UUID organizationId) {
        requireOrgRole(user, organizationId, Set.of("owner", "admin"));
    }

    /** 团队管理员：组织 owner/admin 或团队 admin 都能管团队成员。 */
    public void requireTeamAdmin(CurrentUser user, UUID teamId) {
        TeamEntity team = teams.findById(teamId).orElseThrow(() -> ApiException.notFound("团队"));
        if (hasOrgRole(user, team.getOrganizationId(), Set.of("owner", "admin"))) {
            return;
        }
        boolean teamAdmin = teamMembers.findById(new MembershipEntities.TeamKey(teamId, user.id()))
            .map(MembershipEntities.TeamMembership::getRole)
            .filter("admin"::equals)
            .isPresent();
        if (!teamAdmin) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织或团队管理员能管成员");
        }
    }

    /**
     * 落位成员关系：已存在就改角色，不报冲突。
     * 邀请可以被重复接受，所以这里必须是 upsert 而不是 insert。
     */
    @Transactional
    public void upsertOrgMember(CurrentUser actor, UUID organizationId, UUID memberId, String role) {
        requireRole(role, Set.of("owner", "admin", "publisher", "reviewer", "member", "viewer"));
        MembershipEntities.OrgKey key = new MembershipEntities.OrgKey(organizationId, memberId);
        MembershipEntities.OrgMembership membership = orgMembers.findById(key).orElseGet(() -> {
            MembershipEntities.OrgMembership created = new MembershipEntities.OrgMembership();
            created.setId(key);
            created.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return created;
        });
        membership.setRole(role);
        orgMembers.save(membership);
        audit.record(actor, "organization.membership.grant", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    @Transactional
    public void upsertTeamMember(CurrentUser actor, UUID teamId, UUID memberId, String role) {
        requireRole(role, Set.of("admin", "publisher", "reviewer", "member", "viewer"));
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        MembershipEntities.TeamMembership membership = teamMembers.findById(key).orElseGet(() -> {
            MembershipEntities.TeamMembership created = new MembershipEntities.TeamMembership();
            created.setId(key);
            created.setJoinedAt(OffsetDateTime.now(ZoneOffset.UTC));
            return created;
        });
        membership.setRole(role);
        teamMembers.save(membership);
        audit.record(actor, "team.membership.grant", "team", teamId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }
```

- [ ] **Step 5: 写 DTO**

`apps/api/src/main/java/com/skillmesh/api/invite/dto/CreateInvitationRequest.java`：

```java
package com.skillmesh.api.invite.dto;

import jakarta.validation.constraints.Pattern;
import java.util.UUID;

public record CreateInvitationRequest(
    UUID team_id,
    @Pattern(regexp = "owner|admin|publisher|reviewer|member|viewer") String org_role,
    @Pattern(regexp = "admin|publisher|reviewer|member|viewer") String team_role,
    String subject) {}
```

`apps/api/src/main/java/com/skillmesh/api/invite/dto/InvitationResponse.java`：

```java
package com.skillmesh.api.invite.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationResponse(
    UUID id,
    String organization_slug,
    String organization_name,
    String team_slug,
    String team_name,
    String org_role,
    String team_role,
    OffsetDateTime expires_at,
    String invite_url,
    String token) {}
```

`apps/api/src/main/java/com/skillmesh/api/invite/dto/InvitationSummary.java`：

```java
package com.skillmesh.api.invite.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record InvitationSummary(
    UUID id,
    String organization_name,
    String team_name,
    String org_role,
    String team_role,
    String status,
    OffsetDateTime expires_at,
    OffsetDateTime created_at) {}
```

`apps/api/src/main/java/com/skillmesh/api/invite/dto/AcceptInvitationResponse.java`：

```java
package com.skillmesh.api.invite.dto;

import java.util.UUID;

public record AcceptInvitationResponse(
    UUID invitation_id,
    UUID organization_id,
    UUID team_id,
    String org_role,
    String team_role,
    String message) {}
```

- [ ] **Step 6: 写失败的测试**

`apps/api/src/test/java/com/skillmesh/api/invite/InvitationFlowTest.java`：

```java
package com.skillmesh.api.invite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.support.DatabaseCleaner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 邀请流：创建 → 公开查看 → 接受 → 幂等 → 撤销（设计文档 §10 团队管理）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InvitationFlowTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private InvitationService invitations;
    @Autowired private JdbcTemplate jdbc;

    private String auth;
    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        owner = currentUser();
    }

    @Test
    void 管理员能建邀请且库里只存哈希() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");

        String body = mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "team_id", teamId.toString(), "org_role", "member", "team_role", "member"))))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.token").exists())
            .andExpect(jsonPath("$.invite_url").exists())
            .andExpect(jsonPath("$.organization_slug").value("acme"))
            .andExpect(jsonPath("$.team_slug").value("platform"))
            .andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("token").asText();
        assertThat(token.length()).isGreaterThanOrEqualTo(40);

        // 库里存的是哈希，不是明文
        String stored = jdbc.queryForObject(
            "select token_hash from org_invitations limit 1", String.class);
        assertThat(stored).isNotEqualTo(token);
        assertThat(stored).hasSize(64);
    }

    @Test
    void 公开端点不用登录就能看邀请摘要() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        String token = createInvitation(orgId, teamId);

        mockMvc.perform(get("/api/v1/invitations/{token}", token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.organization_name").value("acme"))
            .andExpect(jsonPath("$.team_name").value("platform"))
            .andExpect(jsonPath("$.status").value("pending"));
    }

    @Test
    void 接受邀请后落成员关系且可重复接受() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        String token = createInvitation(orgId, teamId);

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.org_role").value("member"))
            .andExpect(jsonPath("$.team_id").value(teamId.toString()));

        // 重复接受不报错
        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isOk());
    }

    @Test
    void 不存在的邀请是404() throws Exception {
        mockMvc.perform(get("/api/v1/invitations/{token}", "no-such-token"))
            .andExpect(status().isNotFound());
    }

    @Test
    void 撤销后不能接受() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        String token = createInvitation(orgId, teamId);

        mockMvc.perform(delete("/api/v1/organizations/{id}/invitations/{iid}", orgId, invitationId(token))
                .header("Authorization", auth))
            .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/invitations/{token}/accept", token)
                .header("Authorization", auth))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVITATION_INVALID"));
    }

    @Test
    void 非管理员不能建邀请() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        // 权限要在服务层断言：HTTP 层只有一个真实令牌，造不出「非属主」的身份
        assertThatThrownBy(() -> invitations.create(stranger(), orgId, teamId,
            "member", "member", null))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("权限不足");
    }

    private String createInvitation(UUID orgId, UUID teamId) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations/{id}/invitations", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "team_id", teamId.toString(), "org_role", "member", "team_role", "member"))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("token").asText();
    }

    private UUID invitationId(String token) throws Exception {
        String body = mockMvc.perform(get("/api/v1/invitations/{token}", token))
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createOrganization(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createTeam(UUID orgId, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations/{id}/teams", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    /** 造一个真实用户行——成员表对 users 有外键，光给个 UUID 插不进去。 */
    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }
}
```

- [ ] **Step 7: 跑测试确认失败**

```bash
cd apps/api && ./mvnw -q -Dtest=InvitationFlowTest test
```

Expected：编译失败——`InvitationService` / `InvitationController` 还不存在。

- [ ] **Step 8: 写 InvitationService**

`apps/api/src/main/java/com/skillmesh/api/invite/InvitationService.java`：

```java
package com.skillmesh.api.invite;

import com.skillmesh.api.audit.AuditService;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.common.ErrorCodes;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.org.TeamEntity;
import com.skillmesh.api.org.TeamRepository;
import com.skillmesh.api.org.OrganizationEntity;
import com.skillmesh.api.org.OrgRepository;
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
```

- [ ] **Step 9: 写 InvitationController**

`apps/api/src/main/java/com/skillmesh/api/invite/InvitationController.java`：

```java
package com.skillmesh.api.invite;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.invite.dto.AcceptInvitationResponse;
import com.skillmesh.api.invite.dto.CreateInvitationRequest;
import com.skillmesh.api.invite.dto.InvitationResponse;
import com.skillmesh.api.invite.dto.InvitationSummary;
import com.skillmesh.api.org.OrganizationEntity;
import com.skillmesh.api.org.TeamEntity;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 邀请端点（设计文档 §10 团队管理）。 */
@RestController
@RequestMapping("/api/v1")
public class InvitationController {

    private final InvitationService invitations;
    private final CurrentUserService currentUserService;

    public InvitationController(InvitationService invitations, CurrentUserService currentUserService) {
        this.invitations = invitations;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/organizations/{organizationId}/invitations")
    public ResponseEntity<InvitationResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID organizationId,
            @Valid @RequestBody(required = false) CreateInvitationRequest req) {
        CurrentUser user = currentUserService.current(jwt);
        CreateInvitationRequest body = req == null
            ? new CreateInvitationRequest(null, "member", "member", null) : req;
        InvitationService.Created created = invitations.create(user, organizationId,
            body.team_id(), body.org_role(), body.team_role(), body.subject());
        InvitationEntity entity = created.invitation();
        OrganizationEntity org = invitations.organizationOf(entity);
        TeamEntity team = invitations.teamOf(entity);
        return ResponseEntity.created(URI.create("/api/v1/invitations/" + created.id()))
            .body(new InvitationResponse(entity.getId(),
                org == null ? null : org.getSlug(),
                org == null ? null : org.getName(),
                team == null ? null : team.getSlug(),
                team == null ? null : team.getName(),
                entity.getOrgRole(), entity.getTeamRole(), entity.getExpiresAt(),
                invitations.inviteUrl(created.token()), created.token()));
    }

    @GetMapping("/organizations/{organizationId}/invitations")
    public List<InvitationSummary> list(@AuthenticationPrincipal Jwt jwt,
                                        @PathVariable UUID organizationId) {
        CurrentUser user = currentUserService.current(jwt);
        invitations.requireOrgMember(user, organizationId);
        return invitations.list(organizationId).stream().map(entity -> {
            OrganizationEntity org = invitations.organizationOf(entity);
            TeamEntity team = invitations.teamOf(entity);
            return new InvitationSummary(entity.getId(),
                org == null ? null : org.getName(),
                team == null ? null : team.getName(),
                entity.getOrgRole(), entity.getTeamRole(),
                effectiveStatus(entity), entity.getExpiresAt(), entity.getCreatedAt());
        }).toList();
    }

    @DeleteMapping("/organizations/{organizationId}/invitations/{invitationId}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID organizationId,
                                       @PathVariable UUID invitationId) {
        invitations.revoke(currentUserService.current(jwt), organizationId, invitationId);
        return ResponseEntity.noContent().build();
    }

    /** 公开：没登录的人也要看得懂这份邀请是什么。 */
    @GetMapping("/invitations/{token}")
    public InvitationSummary publicView(@PathVariable String token) {
        InvitationEntity entity = invitations.summary(token);
        OrganizationEntity org = invitations.organizationOf(entity);
        TeamEntity team = invitations.teamOf(entity);
        return new InvitationSummary(entity.getId(),
            org == null ? null : org.getName(),
            team == null ? null : team.getName(),
            entity.getOrgRole(), entity.getTeamRole(),
            effectiveStatus(entity), entity.getExpiresAt(), entity.getCreatedAt());
    }

    @PostMapping("/invitations/{token}/accept")
    public AcceptInvitationResponse accept(@AuthenticationPrincipal Jwt jwt,
                                           @PathVariable String token) {
        InvitationService.Accepted accepted = invitations.accept(currentUserService.current(jwt), token);
        return new AcceptInvitationResponse(accepted.invitationId(), accepted.organizationId(),
            accepted.teamId(), accepted.orgRole(), accepted.teamRole(), "已加入");
    }

    /** 过期不改库状态，按时间算。 */
    private static String effectiveStatus(InvitationEntity entity) {
        if ("pending".equals(entity.getStatus())
            && entity.getExpiresAt().isBefore(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC))) {
            return "expired";
        }
        return entity.getStatus();
    }
}
```

- [ ] **Step 10: 跑测试确认通过**

```bash
cd apps/api && ./mvnw -q -Dtest=InvitationFlowTest test
```

Expected：PASS。

- [ ] **Step 11: 跑全量测试确认没改坏别的**

```bash
cd apps/api && ./mvnw test 2>&1 | grep -E "Tests run:.*Failures|BUILD"
```

Expected：`Tests run: N, Failures: 0, Errors: 0` + `BUILD SUCCESS`。

- [ ] **Step 12: 提交**

```bash
git add apps/api/src/main/java/com/skillmesh/api/invite apps/api/src/main/java/com/skillmesh/api/org/OrgService.java apps/api/src/main/java/com/skillmesh/api/common/ErrorCodes.java apps/api/src/main/java/com/skillmesh/api/config/SecurityConfig.java apps/api/src/main/resources/application.yml apps/api/src/test/java/com/skillmesh/api/invite
git commit -m "feat(api): 邀请端点——创建、公开查看、接受、撤销"
```

---

### Task 3: 成员管理端点

**Files:**

- Create: `apps/api/src/main/java/com/skillmesh/api/org/dto/MemberResponse.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/org/OrgService.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/org/OrgController.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/org/OrgMembershipRepository.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/org/TeamMembershipRepository.java`
- Test: `apps/api/src/test/java/com/skillmesh/api/org/MemberManagementTest.java`

**Interfaces:**

- Consumes: Task 2 的 `OrgService.requireOrgAdmin` / `requireTeamAdmin` / `upsertOrgMember` / `upsertTeamMember`
- Produces:
  - `OrgService.orgMembers(UUID organizationId) → List<OrgService.Member>`
  - `OrgService.teamMembers(UUID teamId) → List<OrgService.Member>`
  - `OrgService.updateOrgRole(CurrentUser actor, UUID organizationId, UUID memberId, String role)` — 授予 `owner` 时要求操作者本人是该组织的 owner（`requireOwnerToGrantOwner`，Task 2 加的守卫，**需从 private 改成包内可见**才能被同包的 `OrgService` 调用）。**并且不得把组织里最后一个 owner 降级**（`requireNotLastOwner`）——降了组织就永久失去 owner。
  - `OrgService.updateTeamRole(CurrentUser actor, UUID teamId, UUID memberId, String role)`
  - `OrgService.removeTeamMember(CurrentUser actor, UUID teamId, UUID memberId)`
  - `OrgService.Member` 是 record：`(UUID userId, String displayName, String email, String role, OffsetDateTime joinedAt)`
  - 端点（5 个）：
    - `GET /api/v1/organizations/{organizationId}/members`
    - `GET /api/v1/organizations/{organizationId}/teams/{teamId}/members`
    - `PATCH /api/v1/organizations/{organizationId}/members/{userId}`
    - `PATCH /api/v1/organizations/{organizationId}/teams/{teamId}/members/{userId}`
    - `DELETE /api/v1/organizations/{organizationId}/teams/{teamId}/members/{userId}`

- [ ] **Step 1: 写 DTO**

`apps/api/src/main/java/com/skillmesh/api/org/dto/MemberResponse.java`：

```java
package com.skillmesh.api.org.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MemberResponse(
    UUID user_id,
    String display_name,
    String email,
    String role,
    OffsetDateTime joined_at) {}
```

- [ ] **Step 2: 给仓储加查询**

`apps/api/src/main/java/com/skillmesh/api/org/OrgMembershipRepository.java` 加：

```java
    List<MembershipEntities.OrgMembership> findByIdOrganizationId(UUID organizationId);
```

`apps/api/src/main/java/com/skillmesh/api/org/TeamMembershipRepository.java` 加：

```java
    List<MembershipEntities.TeamMembership> findByIdTeamId(UUID teamId);
```

- [ ] **Step 3: 给 OrgService 加成员管理方法**

`apps/api/src/main/java/com/skillmesh/api/org/OrgService.java` 的构造器加一个 `UserRepository users` 参数（`com.skillmesh.api.auth.UserRepository`），并加下面这些方法：

**另外要修既有的 `addOrgMember`**——它无条件 `setRole(role)`，所以能把组织里**最后一个 owner** 降成 member。一旦降了，`requireOwnerToGrantOwner` 就再也满足不了，组织永久失去 owner。

守卫的语义是两条，缺一不可：

- **动 owner 身份只能由 owner 动手**（升或降）。admin 能把 owner 降成 member，等于做了只有 owner 该做的事。
- **组织里最后一个 owner 不能被降级**。

```java
    /**
     * 改变 owner 身份（授予或剥夺）只能由 owner 动手；
     * 且组织里最后一个 owner 不能被降级——降了之后
     * requireOwnerToGrantOwner 再也满足不了，组织永久失去 owner。
     */
    private void requireOwnerChangeAllowed(CurrentUser actor, UUID organizationId,
                                           String currentRole, String newRole) {
        boolean touchesOwnership = "owner".equals(currentRole) || "owner".equals(newRole);
        if (!touchesOwnership) {
            return;
        }
        if (!hasOrgRole(actor, organizationId, Set.of("owner"))) {
            throw new ApiException(403, ErrorCodes.FORBIDDEN, "只有组织 owner 能改变 owner 身份");
        }
        if ("owner".equals(currentRole) && !"owner".equals(newRole)
            && ownerCount(organizationId) <= 1) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "不能降级组织里唯一的 owner");
        }
    }

    /** 组织里 owner 角色的成员数。 */
    private long ownerCount(UUID organizationId) {
        return orgMembers.findByIdOrganizationId(organizationId).stream()
            .filter(membership -> "owner".equals(membership.getRole()))
            .count();
    }
```

在 `addOrgMember` 与 `updateOrgRole` 落位前都调它（两者都改角色，都可能剥夺 owner 身份）。`updateOrgRole` 还要显式调 `requireOwnerToGrantOwner`——它不走 `upsertOrgMember`，那个守卫不会自动生效。

```java
    /** 组织成员列表，带上人的显示名。 */
    public List<Member> orgMembers(UUID organizationId) {
        return orgMembers.findByIdOrganizationId(organizationId).stream()
            .map(membership -> toMember(membership.getId().getUserId(),
                membership.getRole(), membership.getJoinedAt()))
            .toList();
    }

    /** 团队成员列表。 */
    public List<Member> teamMembers(UUID teamId) {
        return teamMembers.findByIdTeamId(teamId).stream()
            .map(membership -> toMember(membership.getId().getUserId(),
                membership.getRole(), membership.getJoinedAt()))
            .toList();
    }

    /** 改组织角色。 */
    @Transactional
    public void updateOrgRole(CurrentUser actor, UUID organizationId, UUID memberId, String role) {
        requireOrgAdmin(actor, organizationId);
        // 授予 owner 同样要现任 owner 在场——这个方法不走 upsertOrgMember，
        // 所以必须自己调一次守卫，不能指望上游覆盖。
        requireOwnerToGrantOwner(actor, organizationId, role);
        if (actor.id().equals(memberId)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED, "不能改自己的组织角色");
        }
        MembershipEntities.OrgKey key = new MembershipEntities.OrgKey(organizationId, memberId);
        MembershipEntities.OrgMembership membership = orgMembers.findById(key)
            .orElseThrow(() -> ApiException.notFound("成员"));
        if ("owner".equals(membership.getRole()) && !"owner".equals(role)) {
            throw new ApiException(400, ErrorCodes.VALIDATION_FAILED,
                "组织唯一 owner 不能降级——先指定新的 owner");
        }
        membership.setRole(role);
        orgMembers.save(membership);
        audit.record(actor, "organization.membership.update", "organization", organizationId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 改团队角色。 */
    @Transactional
    public void updateTeamRole(CurrentUser actor, UUID teamId, UUID memberId, String role) {
        requireTeamAdmin(actor, teamId);
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        MembershipEntities.TeamMembership membership = teamMembers.findById(key)
            .orElseThrow(() -> ApiException.notFound("成员"));
        membership.setRole(role);
        teamMembers.save(membership);
        audit.record(actor, "team.membership.update", "team", teamId,
            "success", Map.of("user_id", memberId.toString(), "role", role));
    }

    /** 移出团队。不动组织成员身份——两套身份是独立的。 */
    @Transactional
    public void removeTeamMember(CurrentUser actor, UUID teamId, UUID memberId) {
        requireTeamAdmin(actor, teamId);
        MembershipEntities.TeamKey key = new MembershipEntities.TeamKey(teamId, memberId);
        if (!teamMembers.findById(key).isPresent()) {
            throw ApiException.notFound("成员");
        }
        teamMembers.deleteById(key);
        audit.record(actor, "team.membership.remove", "team", teamId,
            "success", Map.of("user_id", memberId.toString()));
    }

    private Member toMember(UUID userId, String role, OffsetDateTime joinedAt) {
        String displayName = userId.toString();
        String email = null;
        var user = users.findById(userId).orElse(null);
        if (user != null) {
            displayName = user.getDisplayName();
            email = user.getEmail();
        }
        return new Member(userId, displayName, email, role, joinedAt);
    }

    /** 成员列表项。 */
    public record Member(UUID userId, String displayName, String email, String role,
                         OffsetDateTime joinedAt) {}
```

- [ ] **Step 4: 写失败的测试**

`apps/api/src/test/java/com/skillmesh/api/org/MemberManagementTest.java`：

```java
package com.skillmesh.api.org;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.support.DatabaseCleaner;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 成员管理：列、改角色、移除（设计文档 §10 团队管理）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberManagementTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private JdbcTemplate jdbc;

    private String auth;
    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        owner = currentUser();
    }

    @Test
    void 成员列表带上显示名和角色() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        CurrentUser member = stranger();
        orgs.upsertTeamMember(owner, teamId, member.id(), "member");

        mockMvc.perform(get("/api/v1/organizations/{id}/teams/{tid}/members", orgId, teamId)
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].user_id").value(member.id().toString()))
            .andExpect(jsonPath("$[0].display_name").value("Stranger"))
            .andExpect(jsonPath("$[0].role").value("member"));
    }

    @Test
    void 改角色与移除成员() throws Exception {
        UUID orgId = createOrganization("acme");
        UUID teamId = createTeam(orgId, "platform");
        CurrentUser member = stranger();
        orgs.upsertTeamMember(owner, teamId, member.id(), "member");

        mockMvc.perform(patch("/api/v1/organizations/{id}/teams/{tid}/members/{uid}",
                orgId, teamId, member.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"publisher\"}"))
            .andExpect(status().isOk());
        assertThat(orgs.teamMembers(teamId)).anyMatch(m -> "publisher".equals(m.role()));

        mockMvc.perform(delete("/api/v1/organizations/{id}/teams/{tid}/members/{uid}",
                orgId, teamId, member.id())
                .header("Authorization", auth))
            .andExpect(status().isNoContent());
        assertThat(orgs.teamMembers(teamId)).isEmpty();
    }

    @Test
    void 不能改自己的组织角色() throws Exception {
        UUID orgId = createOrganization("acme");
        mockMvc.perform(patch("/api/v1/organizations/{id}/members/{uid}", orgId, owner.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"member\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void 唯一owner不能被降级() throws Exception {
        UUID orgId = createOrganization("acme");
        mockMvc.perform(patch("/api/v1/organizations/{id}/members/{uid}", orgId, owner.id())
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"admin\"}"))
            .andExpect(status().isBadRequest());
    }

    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }

    private UUID createOrganization(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations").header("Authorization", auth)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private UUID createTeam(UUID orgId, String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/organizations/{id}/teams", orgId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("slug", slug, "name", slug))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("id").asText());
    }
}
```

- [ ] **Step 5: 跑测试确认失败**

```bash
cd apps/api && ./mvnw -q -Dtest=MemberManagementTest test
```

Expected：编译失败——`orgMembers` / `teamMembers` / `Member` 还不存在。

- [ ] **Step 6: 写端点**

`apps/api/src/main/java/com/skillmesh/api/org/OrgController.java` 里加：

```java
    @GetMapping("/organizations/{organizationId}/members")
    public List<MemberResponse> listOrgMembers(@AuthenticationPrincipal Jwt jwt,
                                               @PathVariable UUID organizationId) {
        CurrentUser user = currentUserService.current(jwt);
        if (!orgs.isOrgMember(organizationId, user.id())) {
            throw ApiException.notFound("组织");
        }
        return orgs.orgMembers(organizationId).stream().map(OrgController::toResponse).toList();
    }

    @GetMapping("/organizations/{organizationId}/teams/{teamId}/members")
    public List<MemberResponse> listTeamMembers(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID organizationId,
                                                @PathVariable UUID teamId) {
        CurrentUser user = currentUserService.current(jwt);
        if (!orgs.isTeamMember(teamId, user.id()) && !orgs.isOrgMember(organizationId, user.id())) {
            throw ApiException.notFound("团队");
        }
        return orgs.teamMembers(teamId).stream().map(OrgController::toResponse).toList();
    }

    @PatchMapping("/organizations/{organizationId}/members/{userId}")
    public ResponseEntity<Void> updateOrgMember(@AuthenticationPrincipal Jwt jwt,
                                                 @PathVariable UUID organizationId,
                                                 @PathVariable UUID userId,
                                                 @Valid @RequestBody RoleRequest req) {
        orgs.updateOrgRole(currentUserService.current(jwt), organizationId, userId, req.role());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/organizations/{organizationId}/teams/{teamId}/members/{userId}")
    public ResponseEntity<Void> updateTeamMember(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID organizationId,
                                                  @PathVariable UUID teamId,
                                                  @PathVariable UUID userId,
                                                  @Valid @RequestBody RoleRequest req) {
        // 写路径也要校验团队属于该组织——GET 校验了、写入不校验，
        // 同一资源路径会给出不一致的答案。
        orgs.requireTeamInOrg(organizationId, teamId);
        orgs.updateTeamRole(currentUserService.current(jwt), teamId, userId, req.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/organizations/{organizationId}/teams/{teamId}/members/{userId}")
    public ResponseEntity<Void> removeTeamMember(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable UUID organizationId,
                                                  @PathVariable UUID teamId,
                                                  @PathVariable UUID userId) {
        orgs.requireTeamInOrg(organizationId, teamId);
        orgs.removeTeamMember(currentUserService.current(jwt), teamId, userId);
        return ResponseEntity.noContent().build();
    }

    private static MemberResponse toResponse(OrgService.Member member) {
        return new MemberResponse(member.userId(), member.displayName(), member.email(),
            member.role(), member.joinedAt());
    }
```

`RoleRequest` 独立成文件 `apps/api/src/main/java/com/skillmesh/api/org/dto/RoleRequest.java`——项目约定是一个文件一个类型声明（`MemberResponse` 也在这个包里）：

```java
package com.skillmesh.api.org.dto;

import jakarta.validation.constraints.NotBlank;

public record RoleRequest(@NotBlank String role) {}
```

同时在 import 里补 `org.springframework.web.bind.annotation.PatchMapping`、
`org.springframework.web.bind.annotation.DeleteMapping`、
`com.skillmesh.api.org.dto.MemberResponse`、
`com.skillmesh.api.org.dto.RoleRequest`。

- [ ] **Step 7: 服务端强制团队发布权限**

`skillmesh publish --org/--team` 的 publisher 检查在 CLI 侧，但 CLI 只是建议——直接打 API 的 member 仍能建出 team 可见的 Skill。**服务端必须自己判**，否则这个权限形同虚设。

**判的是组织角色**（设计文档 §11 已知边界表：发布到团队的权限看的是组织角色）。CLI 的 `GET /me` 只有组织角色，CLI 那边也只查得到组织角色——两边必须同口径，否则 CLI 放行、服务端 403，用户按手册的指引提权也没用。

`apps/api/src/main/java/com/skillmesh/api/skill/SkillService.java` 的 `requireVisibilitySupported` 里，对 `visibility` 为 `team` 的情况，补一条：发起人在该组织里的角色要 ≥ publisher。

```java
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
            // 判的是组织角色——与 CLI、手册一致。别用 roleIn：那个优先取团队角色，
            // 而接受邀请会写一行 team_role=member，把组织角色永久遮住，
            // 手册给的解法（提组织角色）在服务端就不生效了。
            String role = orgs.roleIn(req.organization_id(), null, user.id());
            if (!OrgService.roleAtLeast(role, "publisher")) {
                throw new ApiException(403, ErrorCodes.FORBIDDEN,
                    "在该组织里需要 publisher 及以上角色才能发布");
            }
        }
```

`organization` 可见性同样要求发起人是该组织里 publisher 及以上的角色——判的也是组织角色。

在 `MemberManagementTest` 里补两个用例：
- 非 publisher 的组织成员建 team 可见的 Skill → 403 `FORBIDDEN`。
- **组织角色是 publisher、但团队角色是 member 的人，仍能建 team 可见的 Skill** ——
  这是本条的口径钉子：邀请接受会写 `team_role=member`，若服务端查团队角色，
  手册教的「提组织角色」就失效了。

- [ ] **Step 8: 跑测试确认通过**

```bash
cd apps/api && ./mvnw -q -Dtest=MemberManagementTest test
```

Expected：PASS。

- [ ] **Step 9: 提交**

```bash
git add apps/api/src/main/java/com/skillmesh/api/org apps/api/src/main/java/com/skillmesh/api/skill apps/api/src/test/java/com/skillmesh/api/org/MemberManagementTest.java
git commit -m "feat(api): 成员管理端点——列、改角色、移除"
```

---

### Task 4: 审核队列端点

**Files:**

- Create: `apps/api/src/main/java/com/skillmesh/api/review/ReviewQueueService.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/review/ReviewQueueController.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/review/dto/PendingReviewItem.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/review/dto/ReviewDetailResponse.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/review/dto/ReviewScanSummary.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/review/dto/ReviewSourceSummary.java`
- Modify: `apps/api/src/main/java/com/skillmesh/api/version/SkillVersionRepository.java`
- Test: `apps/api/src/test/java/com/skillmesh/api/review/ReviewQueueTest.java`

**Interfaces:**

- Consumes: Task 1–3 的表与服务；`OrgService.roleIn` / `roleAtLeast`
- Produces:
  - `ReviewQueueService.pending(CurrentUser user, int limit) → List<PendingReviewItem>`
  - `ReviewQueueService.detail(CurrentUser user, UUID skillId, String version) → ReviewDetailResponse`
  - 端点：
    - `GET /api/v1/reviews?status=pending_review&limit=50`
    - `GET /api/v1/reviews/{skillId}/{version}`
  - `PendingReviewItem` 是 record：`(UUID skill_id, String skill_slug, String skill_name, String version, String channel, String submitted_by, String submitted_at, ReviewScanSummary scan, ReviewSourceSummary source)`
  - `ReviewDetailResponse` 是 record：`(PendingReviewItem item, String skill_md, String changelog, List<ReviewFinding> findings, ArtifactDiffResult diff)`
  - `ReviewScanSummary` 是 record：`(String status, String summary)`
  - `ReviewSourceSummary` 是 record：`(String provider, String repository, String ref, String commit)`

- [ ] **Step 1: 加版本查询**

`apps/api/src/main/java/com/skillmesh/api/version/SkillVersionRepository.java` 加：

```java
    /** 待审版本。带出 Skill 所属的组织与团队，供可见性过滤。 */
    @Query(value = """
        select v.* from skill_versions v
        join skills s on s.id = v.skill_id
        where v.status = :status
          and (s.owner_user_id = :owner
               or (s.owner_team_id is not null and exists (
                     select 1 from team_memberships m
                     where m.team_id = s.owner_team_id and m.user_id = :owner))
               or (s.organization_id is not null and exists (
                     select 1 from organization_memberships m
                     where m.organization_id = s.organization_id and m.user_id = :owner)))
        order by v.created_at desc
        limit cast(:limit as int)
        """, nativeQuery = true)
    List<SkillVersionEntity> findPendingVisible(@Param("owner") UUID owner,
                                                 @Param("status") String status,
                                                 @Param("limit") int limit);
```

import 补 `org.springframework.data.jpa.repository.Query` 与 `org.springframework.data.repository.query.Param`。

- [ ] **Step 2: 写 DTO**

`apps/api/src/main/java/com/skillmesh/api/review/dto/ReviewScanSummary.java`：

```java
package com.skillmesh.api.review.dto;

public record ReviewScanSummary(String status, String summary) {}
```

`apps/api/src/main/java/com/skillmesh/api/review/dto/ReviewSourceSummary.java`：

```java
package com.skillmesh.api.review.dto;

public record ReviewSourceSummary(String provider, String repository, String ref, String commit) {}
```

`apps/api/src/main/java/com/skillmesh/api/review/dto/PendingReviewItem.java`：

```java
package com.skillmesh.api.review.dto;

import java.util.UUID;

public record PendingReviewItem(
    UUID skill_id,
    String skill_slug,
    String skill_name,
    String version,
    String channel,
    String submitted_by,
    String submitted_at,
    ReviewScanSummary scan,
    ReviewSourceSummary source) {}
```

`apps/api/src/main/java/com/skillmesh/api/review/dto/ReviewDetailResponse.java`：

```java
package com.skillmesh.api.review.dto;

import com.skillmesh.api.diff.ArtifactDiffResult;
import java.util.List;
import java.util.Map;

public record ReviewDetailResponse(
    PendingReviewItem item,
    String skill_md,
    String changelog,
    List<Map<String, Object>> findings,
    ArtifactDiffResult diff) {}
```

（`ArtifactDiffResult` 在 Task 5 定义；本任务先按 Task 5 的形状引用，编译顺序上 Task 5 要在本任务之前跑，或本任务与 Task 5 合并。**执行顺序：先做 Task 5（diff），再做 Task 4（审核队列）。**）

- [ ] **Step 3: 写失败的测试**

`apps/api/src/test/java/com/skillmesh/api/review/ReviewQueueTest.java`：

```java
package com.skillmesh.api.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.TestTokens;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.skill.SkillService;
import com.skillmesh.api.skill.dto.CreateSkillRequest;
import com.skillmesh.api.support.DatabaseCleaner;
import com.skillmesh.api.support.TestArtifacts;
import com.skillmesh.api.version.VersionFlowTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 审核队列：只列我有权限审的（设计文档 §10.1）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewQueueTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private TestTokens tokens;
    @Autowired private DatabaseCleaner cleaner;
    @Autowired private ObjectMapper json;
    @Autowired private OrgService orgs;
    @Autowired private SkillService skills;
    @Autowired private JdbcTemplate jdbc;

    private String auth;
    private CurrentUser owner;

    @BeforeEach
    void 准备() throws Exception {
        cleaner.clean();
        auth = "Bearer " + tokens.accessToken();
        owner = currentUser();
    }

    @Test
    void 团队待审版本出现在审核队列里() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        publish(skillId, "1.0.0");

        mockMvc.perform(get("/api/v1/reviews").param("status", "pending_review")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].skill_slug").value("runbook"))
            .andExpect(jsonPath("$[0].version").value("1.0.0"))
            .andExpect(jsonPath("$[0].scan.status").exists());
    }

    @Test
    void 非成员看不到别人的待审版本() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        publish(skillId, "1.0.0");

        // 另一个人（同属主身份但不是团队成员）看不到
        CurrentUser outsider = stranger();
        assertThat(outsider.id()).isNotNull();
        mockMvc.perform(get("/api/v1/reviews").param("status", "pending_review")
                .header("Authorization", auth))
            .andExpect(jsonPath("$[?(@.skill_slug == 'runbook')]").isEmpty());
    }

    @Test
    void 审核详情带SKILL正文与文件级差异() throws Exception {
        UUID orgId = orgs.createOrganization(owner, "acme", "Acme").getId();
        UUID teamId = orgs.createTeam(owner, orgId, "platform", "Platform", null).getId();
        UUID skillId = skills.create(owner, new CreateSkillRequest("runbook", "Runbook", "",
            "team", orgId, teamId, List.of(), Map.of(), null)).id();
        publish(skillId, "1.0.0");

        mockMvc.perform(get("/api/v1/reviews/{id}/{v}", skillId, "1.0.0")
                .header("Authorization", auth))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.skill_md").value(org.hamcrest.Matchers.containsString("Runbook")))
            .andExpect(jsonPath("$.diff.base_version").value(""))
            .andExpect(jsonPath("$.diff.files[0].path").value("SKILL.md"))
            .andExpect(jsonPath("$.diff.files[0].kind").value("added"));
    }

    private void publish(UUID skillId, String version) throws Exception {
        byte[] zip = TestArtifacts.zipOf("SKILL.md", "# Runbook\n");
        String created = mockMvc.perform(post("/api/v1/skills/{id}/versions", skillId)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                    "version", version, "channel", "stable", "size_bytes", zip.length,
                    "sha256", VersionFlowTest.sha256(zip), "file_count", 1,
                    "manifest", List.of(Map.of("path", "SKILL.md",
                        "sha256", VersionFlowTest.sha256(zip), "size_bytes", zip.length))))))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        var node = json.readTree(created);
        HttpResponse<String> put = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create(node.path("upload").path("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(zip)).build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(put.statusCode()).isEqualTo(200);
        mockMvc.perform(post("/api/v1/uploads/{id}/complete",
                node.path("upload").path("session_id").asText())
                .header("Authorization", auth))
            .andExpect(status().isAccepted());
    }

    private CurrentUser currentUser() throws Exception {
        String body = mockMvc.perform(get("/api/v1/me").header("Authorization", auth))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(body).path("user").path("id").asText());
        return new CurrentUser(id, "owner", "Owner", "owner@example.com");
    }

    private CurrentUser stranger() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, oidc_issuer, oidc_subject, display_name, status) "
            + "values (?, 'https://kc', ?, 'Stranger', 'active')", id, "stranger-" + id);
        return new CurrentUser(id, "stranger-" + id, "Stranger", null);
    }
}
```

- [ ] **Step 4: 跑测试确认失败**

```bash
cd apps/api && ./mvnw -q -Dtest=ReviewQueueTest test
```

Expected：编译失败——`ReviewQueueService` / `ReviewQueueController` 还不存在。

- [ ] **Step 5: 写 ReviewQueueService**

`apps/api/src/main/java/com/skillmesh/api/review/ReviewQueueService.java`：

```java
package com.skillmesh.api.review;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.UserRepository;
import com.skillmesh.api.common.ApiException;
import com.skillmesh.api.diff.ArtifactDiffService;
import com.skillmesh.api.org.OrgService;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.review.dto.ReviewDetailResponse;
import com.skillmesh.api.review.dto.ReviewScanSummary;
import com.skillmesh.api.review.dto.ReviewSourceSummary;
import com.skillmesh.api.scan.ScanEntity;
import com.skillmesh.api.scan.ScanRepository;
import com.skillmesh.api.skill.SkillEntity;
import com.skillmesh.api.skill.SkillRepository;
import com.skillmesh.api.skill.SourceEntity;
import com.skillmesh.api.skill.SourceRepository;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审核队列（设计文档 §10.1）。
 *
 * 只列「我审得了」的：技能的归属范围里我是 reviewer 及以上。
 * 角色等级看 OrgService.roleAtLeast，别在 SQL 里硬编码角色列表。
 */
@Service
public class ReviewQueueService {

    private final SkillVersionRepository versions;
    private final SkillRepository skills;
    private final SourceRepository sources;
    private final ScanRepository scans;
    private final ArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final OrgService orgs;
    private final UserRepository users;
    private final ArtifactDiffService diffs;

    public ReviewQueueService(SkillVersionRepository versions, SkillRepository skills,
                              SourceRepository sources, ScanRepository scans,
                              ArtifactRepository artifacts, ArtifactStorage storage,
                              OrgService orgs, UserRepository users,
                              ArtifactDiffService diffs) {
        this.versions = versions;
        this.skills = skills;
        this.sources = sources;
        this.scans = scans;
        this.artifacts = artifacts;
        this.storage = storage;
        this.orgs = orgs;
        this.users = users;
        this.diffs = diffs;
    }

    /** 待审列表。 */
    @Transactional(readOnly = true)
    public List<PendingReviewItem> pending(CurrentUser user, String status, int limit) {
        String wanted = status == null || status.isBlank() ? "pending_review" : status;
        int capped = Math.clamp(limit, 1, 200);
        return versions.findPendingVisible(user.id(), wanted, capped).stream()
            .filter(version -> canReview(user, version))
            .map(version -> toItem(user, version))
            .toList();
    }

    /** 审核详情：正文、扫描命中、来源、文件级差异。 */
    @Transactional(readOnly = true)
    public ReviewDetailResponse detail(CurrentUser user, UUID skillId, String version) {
        SkillEntity skill = skills.findById(skillId)
            .orElseThrow(() -> ApiException.notFound("Skill"));
        SkillVersionEntity entity = versions.findBySkillIdAndVersion(skillId, version)
            .orElseThrow(() -> ApiException.notFound("版本"));
        if (!canReview(user, entity)) {
            throw ApiException.notFound("版本");
        }
        PendingReviewItem item = toItem(user, entity);
        List<Map<String, Object>> findings = findingsOf(entity);
        return new ReviewDetailResponse(item,
            diffs.readSkillMarkdown(artifactOf(entity)),
            entity.getChangelog(),
            findings,
            diffs.diffAgainstLatestPublished(skillId, entity));
    }

    /** 我能不能审这个版本。 */
    private boolean canReview(CurrentUser user, SkillVersionEntity version) {
        SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
        if (skill == null) {
            return false;
        }
        if (user.id().equals(skill.getOwnerUserId())) {
            return true;
        }
        String role = orgs.roleIn(skill.getOrganizationId(), skill.getOwnerTeamId(), user.id());
        return OrgService.roleAtLeast(role, "reviewer");
    }

    private PendingReviewItem toItem(CurrentUser user, SkillVersionEntity version) {
        SkillEntity skill = skills.findById(version.getSkillId()).orElse(null);
        ReviewScanSummary scan = scanSummary(version);
        ReviewSourceSummary source = sourceSummary(version);
        return new PendingReviewItem(
            version.getSkillId(),
            skill == null ? null : skill.getSlug(),
            skill == null ? null : skill.getName(),
            version.getVersion(),
            version.getChannel(),
            displayName(version.getCreatedBy()),
            version.getCreatedAt() == null ? null : version.getCreatedAt().toString(),
            scan,
            source);
    }

    private ReviewScanSummary scanSummary(SkillVersionEntity version) {
        return scans.findFirstBySkillVersionIdOrderByStartedAtDesc(version.getId())
            .map(scan -> new ReviewScanSummary(scan.getStatus(), scan.getSummary()))
            .orElse(new ReviewScanSummary("unknown", "未扫描"));
    }

    private ReviewSourceSummary sourceSummary(SkillVersionEntity version) {
        if (version.getSourceId() == null) {
            return new ReviewSourceSummary(null, null, null, version.getSourceCommit());
        }
        return sources.findById(version.getSourceId())
            .map(source -> new ReviewSourceSummary(source.getProvider(),
                source.getRepositoryUrl(), source.getRefValue(), version.getSourceCommit()))
            .orElse(new ReviewSourceSummary(null, null, null, version.getSourceCommit()));
    }

    private List<Map<String, Object>> findingsOf(SkillVersionEntity version) {
        return scans.findFirstBySkillVersionIdOrderByStartedAtDesc(version.getId())
            .map(ScanEntity::getFindingsJson)
            .orElse(List.of());
    }

    private String displayName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return users.findById(userId).map(user -> user.getDisplayName()).orElse(null);
    }

    private ArtifactEntity artifactOf(SkillVersionEntity version) {
        if (version.getArtifactId() == null) {
            return null;
        }
        return artifacts.findById(version.getArtifactId()).orElse(null);
    }
}
```

`ScanRepository.findFirstBySkillVersionIdOrderByStartedAtDesc` 已存在，不用加；`ScanEntity.getFindingsJson()` 直接返回 `List<Map<String, Object>>`，不用再解析。

- [ ] **Step 6: 写 ReviewQueueController**

`apps/api/src/main/java/com/skillmesh/api/review/ReviewQueueController.java`：

```java
package com.skillmesh.api.review;

import com.skillmesh.api.auth.CurrentUser;
import com.skillmesh.api.auth.CurrentUserService;
import com.skillmesh.api.review.dto.PendingReviewItem;
import com.skillmesh.api.review.dto.ReviewDetailResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审核队列（设计文档 §10.1）。 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewQueueController {

    private final ReviewQueueService queue;
    private final CurrentUserService currentUserService;

    public ReviewQueueController(ReviewQueueService queue, CurrentUserService currentUserService) {
        this.queue = queue;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<PendingReviewItem> pending(@AuthenticationPrincipal Jwt jwt,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(defaultValue = "50") int limit) {
        CurrentUser user = currentUserService.current(jwt);
        return queue.pending(user, status, limit);
    }

    @GetMapping("/{skillId}/{version}")
    public ReviewDetailResponse detail(@AuthenticationPrincipal Jwt jwt,
                                       @PathVariable UUID skillId,
                                       @PathVariable String version) {
        CurrentUser user = currentUserService.current(jwt);
        return queue.detail(user, skillId, version);
    }
}
```

- [ ] **Step 7: 跑测试确认通过**

```bash
cd apps/api && ./mvnw -q -Dtest=ReviewQueueTest test
```

Expected：PASS。

- [ ] **Step 8: 提交**

```bash
git add apps/api/src/main/java/com/skillmesh/api/review apps/api/src/main/java/com/skillmesh/api/version/SkillVersionRepository.java apps/api/src/test/java/com/skillmesh/api/review/ReviewQueueTest.java
git commit -m "feat(api): 审核队列端点——待审列表与审核详情"
```

---

### Task 5: 版本间文件级差异

**Files:**

- Create: `apps/api/src/main/java/com/skillmesh/api/diff/FileChange.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/diff/ManifestDiff.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/diff/LineDiff.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/diff/ArtifactDiffResult.java`
- Create: `apps/api/src/main/java/com/skillmesh/api/diff/ArtifactDiffService.java`
- Test: `apps/api/src/test/java/com/skillmesh/api/diff/ManifestDiffTest.java`
- Test: `apps/api/src/test/java/com/skillmesh/api/diff/LineDiffTest.java`

**Interfaces:**

- Consumes: `ArtifactRepository`（取 manifest 与 storage_key）、`ArtifactStorage`（读 zip 字节）
- Produces:
  - `ManifestDiff.compare(List<Map<String, Object>> base, List<Map<String, Object>> next) → List<FileChange>`
  - `LineDiff.count(String oldText, String newText) → int[]`（`{added, removed}`）
  - `FileChange` 是 record：`(String path, String kind, int added, int removed, boolean truncated)`
  - `ArtifactDiffResult` 是 record：`(String baseVersion, boolean truncated, List<FileChange> files)`
  - `ArtifactDiffService.diffAgainstLatestPublished(UUID skillId, SkillVersionEntity next) → ArtifactDiffResult`
  - `ArtifactDiffService.readSkillMarkdown(ArtifactEntity artifact) → String`

**这个 Task 要在 Task 4 之前执行**——Task 4 的 DTO 引用了 `ArtifactDiffResult`。

- [ ] **Step 1: 写 FileChange**

`apps/api/src/main/java/com/skillmesh/api/diff/FileChange.java`：

```java
package com.skillmesh.api.diff;

/**
 * 一个文件在两个版本之间的变化。
 *
 * kind：added / modified / removed。added/removed 只对 modified 有意义。
 * truncated 表示差异大到只报文件级（行数不可信或算不动）。
 */
public record FileChange(String path, String kind, int added, int removed, boolean truncated) {

    public static FileChange added(String path) {
        return new FileChange(path, "added", 0, 0, false);
    }

    public static FileChange removed(String path) {
        return new FileChange(path, "removed", 0, 0, false);
    }

    public static FileChange modified(String path, int added, int removed, boolean truncated) {
        return new FileChange(path, "modified", added, removed, truncated);
    }
}
```

- [ ] **Step 2: 写 ManifestDiff**

`apps/api/src/main/java/com/skillmesh/api/diff/ManifestDiff.java`：

```java
package com.skillmesh.api.diff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 比两个 manifest，找新增/修改/删除的文件。
 *
 * manifest 是逐文件的 path + sha256（artifacts.manifest_json 与
 * skill_versions.manifest_json 都存着）。比摘要就够——内容相同则文件相同。
 */
public final class ManifestDiff {

    private ManifestDiff() {}

    public static List<FileChange> compare(List<Map<String, Object>> base,
                                            List<Map<String, Object>> next) {
        Map<String, String> baseIndex = index(base);
        Map<String, String> nextIndex = index(next);
        List<FileChange> changes = new ArrayList<>();

        for (Map.Entry<String, String> entry : nextIndex.entrySet()) {
            String previous = baseIndex.get(entry.getKey());
            if (previous == null) {
                changes.add(FileChange.added(entry.getKey()));
            } else if (!previous.equals(entry.getValue())) {
                changes.add(FileChange.modified(entry.getKey(), 0, 0, true));
            }
        }
        for (String path : baseIndex.keySet()) {
            if (!nextIndex.containsKey(path)) {
                changes.add(FileChange.removed(path));
            }
        }
        changes.sort((left, right) -> left.path().compareTo(right.path()));
        return changes;
    }

    private static Map<String, String> index(List<Map<String, Object>> manifest) {
        Map<String, String> result = new LinkedHashMap<>();
        if (manifest == null) {
            return result;
        }
        for (Map<String, Object> entry : manifest) {
            Object path = entry.get("path");
            Object sha = entry.get("sha256");
            if (path != null && sha != null) {
                result.put(path.toString(), sha.toString());
            }
        }
        return result;
    }
}
```

**注意**：`modified` 的行数这里先给 0 + `truncated=true`——真正的行数由 Task 5 Step 6 的 `ArtifactDiffService.withLineCounts` 重新生成这份列表时补上。

- [ ] **Step 3: 写 LineDiff**

`apps/api/src/main/java/com/skillmesh/api/diff/LineDiff.java`：

```java
package com.skillmesh.api.diff;

/**
 * 行级增删统计，口径对齐 CLI 的 lineDiff（apps/cli/internal/cmd/diff.go）。
 *
 * 用 LCS 算公共行数：added = 新行数 - 公共行数，removed = 旧行数 - 公共行数。
 */
public final class LineDiff {

    /** LCS DP 表的格数上限，超过就退化成只报文件级。 */
    public static final int MAX_CELLS = 4_000_000;

    /** 增删行数之和超过这个数就标 truncated——差异大到没人逐行看。 */
    public static final int MAX_LINES = 200;

    private LineDiff() {}

    /** 返回 {added, removed}。算不动时返回 {0, 0}。 */
    public static int[] count(String oldText, String newText) {
        String[] oldLines = split(oldText);
        String[] newLines = split(newText);
        if ((long) oldLines.length * newLines.length > MAX_CELLS) {
            return new int[] {0, 0};
        }
        int common = lcsLength(oldLines, newLines);
        return new int[] {newLines.length - common, oldLines.length - common};
    }

    /** 二进制判定：前 8192 字节含 NUL 就是二进制，不做行统计。 */
    public static boolean isBinary(byte[] data) {
        int limit = Math.min(data.length, 8192);
        for (int i = 0; i < limit; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private static String[] split(String text) {
        return text.split("\n", -1);
    }

    private static int lcsLength(String[] oldLines, String[] newLines) {
        int[][] common = new int[oldLines.length + 1][newLines.length + 1];
        for (int i = oldLines.length - 1; i >= 0; i--) {
            for (int j = newLines.length - 1; j >= 0; j--) {
                if (oldLines[i].equals(newLines[j])) {
                    common[i][j] = common[i + 1][j + 1] + 1;
                } else {
                    common[i][j] = Math.max(common[i + 1][j], common[i][j + 1]);
                }
            }
        }
        return common[0][0];
    }
}
```

- [ ] **Step 4: 写失败的测试**

`apps/api/src/test/java/com/skillmesh/api/diff/ManifestDiffTest.java`：

```java
package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestDiffTest {

    @Test
    void 认出新增修改删除() {
        List<Map<String, Object>> base = List.of(
            entry("SKILL.md", "aaa"),
            entry("old.sh", "bbb"));
        List<Map<String, Object>> next = List.of(
            entry("SKILL.md", "ccc"),
            entry("new.md", "ddd"));

        List<FileChange> changes = ManifestDiff.compare(base, next);

        assertThat(changes).hasSize(3);
        assertThat(changes).extracting(FileChange::path)
            .containsExactly("SKILL.md", "new.md", "old.sh");
        assertThat(changes).filteredOn(c -> c.path().equals("SKILL.md"))
            .singleElement().extracting(FileChange::kind).isEqualTo("modified");
        assertThat(changes).filteredOn(c -> c.path().equals("new.md"))
            .singleElement().extracting(FileChange::kind).isEqualTo("added");
        assertThat(changes).filteredOn(c -> c.path().equals("old.sh"))
            .singleElement().extracting(FileChange::kind).isEqualTo("removed");
    }

    @Test
    void 摘要相同就是没改() {
        List<Map<String, Object>> base = List.of(entry("SKILL.md", "aaa"));
        assertThat(ManifestDiff.compare(base, base)).isEmpty();
    }

    @Test
    void 空基线时全部算新增() {
        List<Map<String, Object>> next = List.of(entry("SKILL.md", "aaa"));
        assertThat(ManifestDiff.compare(List.of(), next))
            .singleElement().extracting(FileChange::kind).isEqualTo("added");
    }

    private static Map<String, Object> entry(String path, String sha) {
        return Map.of("path", path, "sha256", sha, "size_bytes", 10, "executable", false);
    }
}
```

`apps/api/src/test/java/com/skillmesh/api/diff/LineDiffTest.java`：

```java
package com.skillmesh.api.diff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LineDiffTest {

    @Test
    void 算出增删行数() {
        int[] counts = LineDiff.count("a\nb\nc\n", "a\nx\nc\nd\n");
        assertThat(counts[0]).isEqualTo(2);  // added
        assertThat(counts[1]).isEqualTo(1);  // removed
    }

    @Test
    void 完全相同没有增删() {
        int[] counts = LineDiff.count("a\nb\n", "a\nb\n");
        assertThat(counts[0]).isZero();
        assertThat(counts[1]).isZero();
    }

    @Test
    void 二进制内容不参与行统计() {
        byte[] binary = new byte[] {'a', 0, 'b'};
        assertThat(LineDiff.isBinary(binary)).isTrue();
        assertThat(LineDiff.isBinary("纯文本".getBytes())).isFalse();
    }
}
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd apps/api && ./mvnw -q -Dtest=ManifestDiffTest+LineDiffTest test
```

Expected：PASS（纯函数，不需要 Spring 上下文）。

- [ ] **Step 6: 写 ArtifactDiffResult 与 ArtifactDiffService**

`apps/api/src/main/java/com/skillmesh/api/diff/ArtifactDiffResult.java`：

```java
package com.skillmesh.api.diff;

import java.util.List;

/** 两个版本的差异。baseVersion 为空表示没有基线（首个版本，全部算新增）。 */
public record ArtifactDiffResult(String baseVersion, boolean truncated, List<FileChange> files) {

    public static ArtifactDiffResult empty() {
        return new ArtifactDiffResult("", false, List.of());
    }
}
```

`apps/api/src/main/java/com/skillmesh/api/diff/ArtifactDiffService.java`：

```java
package com.skillmesh.api.diff;

import com.skillmesh.api.artifact.ArtifactEntity;
import com.skillmesh.api.artifact.ArtifactRepository;
import com.skillmesh.api.artifact.ArtifactStorage;
import com.skillmesh.api.version.SkillVersionEntity;
import com.skillmesh.api.version.SkillVersionRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 版本之间的文件级差异（设计文档 §3.3）。
 *
 * 文件级比 manifest 就行，不解包；只有「被改动的文件」才从两个 zip 里
 * 各取一个 entry 算行数。口径与 CLI 的 update --dry-run 对齐。
 */
@Service
public class ArtifactDiffService {

    /** 单个 entry 超过这个大小就不做行统计。 */
    private static final int MAX_ENTRY_BYTES = 2 << 20;

    private final ArtifactRepository artifacts;
    private final ArtifactStorage storage;
    private final SkillVersionRepository versions;

    public ArtifactDiffService(ArtifactRepository artifacts, ArtifactStorage storage,
                               SkillVersionRepository versions) {
        this.artifacts = artifacts;
        this.storage = storage;
        this.versions = versions;
    }

    /** 待审版本 vs 该 Skill 最近的已发布版本。 */
    @Transactional(readOnly = true)
    public ArtifactDiffResult diffAgainstLatestPublished(UUID skillId, SkillVersionEntity next) {
        Optional<SkillVersionEntity> base = versions.findBySkillIdOrderByCreatedAtDesc(skillId).stream()
            .filter(version -> "published".equals(version.getStatus()))
            .filter(version -> !version.getId().equals(next.getId()))
            .findFirst();
        ArtifactEntity nextArtifact = artifactOf(next);
        if (nextArtifact == null) {
            return ArtifactDiffResult.empty();
        }
        if (base.isEmpty()) {
            List<FileChange> files = ManifestDiff.compare(List.of(), nextArtifact.getManifestJson());
            return new ArtifactDiffResult("", false, files);
        }
        ArtifactEntity baseArtifact = artifactOf(base.get());
        if (baseArtifact == null) {
            return new ArtifactDiffResult(base.get().getVersion(), false, List.of());
        }
        List<FileChange> files = ManifestDiff.compare(
            baseArtifact.getManifestJson(), nextArtifact.getManifestJson());
        return new ArtifactDiffResult(base.get().getVersion(), false, withLineCounts(files, baseArtifact, nextArtifact));
    }

    /** 读制品里的 SKILL.md 正文——审核者要看的第一眼就是它。 */
    @Transactional(readOnly = true)
    public String readSkillMarkdown(ArtifactEntity artifact) {
        if (artifact == null) {
            return "";
        }
        byte[] data = readEntry(artifact, "SKILL.md");
        return data == null ? "" : new String(data, StandardCharsets.UTF_8);
    }

    /** 给被改动的文件补上行数。 */
    private List<FileChange> withLineCounts(List<FileChange> files, ArtifactEntity base,
                                            ArtifactEntity next) {
        List<FileChange> result = new ArrayList<>(files.size());
        for (FileChange change : files) {
            if (!"modified".equals(change.kind())) {
                result.add(change);
                continue;
            }
            byte[] oldData = readEntry(base, change.path());
            byte[] newData = readEntry(next, change.path());
            if (oldData == null || newData == null
                || oldData.length > MAX_ENTRY_BYTES || newData.length > MAX_ENTRY_BYTES
                || LineDiff.isBinary(oldData) || LineDiff.isBinary(newData)) {
                result.add(FileChange.modified(change.path(), 0, 0, true));
                continue;
            }
            int[] counts = LineDiff.count(new String(oldData, StandardCharsets.UTF_8),
                new String(newData, StandardCharsets.UTF_8));
            boolean truncated = counts[0] + counts[1] > LineDiff.MAX_LINES;
            result.add(FileChange.modified(change.path(), counts[0], counts[1], truncated));
        }
        return result;
    }

    /** 从 zip 里取一个 entry 的字节。取不到返回 null。 */
    private byte[] readEntry(ArtifactEntity artifact, String path) {
        try (InputStream stream = storage.get(artifact.getStorageKey());
             ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory() || !path.equals(entry.getName())) {
                    continue;
                }
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                    if (buffer.size() > MAX_ENTRY_BYTES) {
                        return null;
                    }
                }
                return buffer.toByteArray();
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private ArtifactEntity artifactOf(SkillVersionEntity version) {
        if (version.getArtifactId() == null) {
            return null;
        }
        return artifacts.findById(version.getArtifactId()).orElse(null);
    }
}
```

- [ ] **Step 7: 跑 diff 与审核队列的测试**

```bash
cd apps/api && ./mvnw -q -Dtest=ManifestDiffTest+LineDiffTest+ReviewQueueTest test
```

Expected：PASS。

- [ ] **Step 8: 提交**

```bash
git add apps/api/src/main/java/com/skillmesh/api/diff apps/api/src/test/java/com/skillmesh/api/diff
git commit -m "feat(api): 版本间文件级差异——manifest 比对 + 行级统计"
```

---

## Slice 2：Web 基建

### Task 6: OIDC 登录（PKCE + 会话 + 路由）

**Files:**

- Modify: `apps/web/package.json`（加 `element-plus`、`@element-plus/icons-vue`、`vue-router`）
- Modify: `apps/web/src/main.ts`（注册 Element Plus 与路由）
- Create: `apps/web/src/auth/pkce.ts`
- Create: `apps/web/src/auth/session.ts`
- Create: `apps/web/src/router.ts`
- Create: `apps/web/src/pages/LoginPage.vue`
- Create: `apps/web/src/styles/app.css`（布局与工具类，一个文件）
- Modify: `apps/web/src/api.ts`（token 变成可选参数，缺省从会话取）
- Modify: `apps/web/src/App.vue`（改成布局 + `<router-view>`，删掉手贴 token 框）
- Test: `apps/web/tests/auth.spec.ts`

**Interfaces:**

- Consumes: 无（前端第一个任务）
- Produces:
  - `auth/pkce.ts` 导出 `randomVerifier()`、`codeChallenge(verifier)`、`exchangeCode(code, verifier, redirectUri)`、`refreshToken(refreshToken)`、`buildAuthorizeUrl(...)`
  - `auth/session.ts` 导出 `session`（reactive 单例）、`login()`、`logout()`、`ensureFresh()`、`loadMe()`
  - `session` 形状：`{ accessToken, refreshToken, expiresAt, user, organizations }`
  - `router.ts` 导出 `router`（createRouter + createWebHistory）
  - 路由：`/login`（公开）、`/invite/:token`（公开）、`/`（市场）、`/team`、`/reviews`、`/reviews/:skillId/:version`（后三个需登录）

- [ ] **Step 1: 装依赖**

```bash
cd apps/web && pnpm add element-plus@^2.9.0 @element-plus/icons-vue@^2.3.1 vue-router@^4.5.0
```

引入的三个：Element Plus（表格/表单/按钮/徽标/空态/告警条）、它的图标包、vue-router。

**刻意不引**：

- `dayjs`——Element Plus 内部用，但我们要显示的只是 `expires_at` / `submitted_at` 这类 ISO 字符串，`.slice(0, 10)` 就够，引入只为格式化是白背依赖。
- Markdown 渲染器——审核详情里的 SKILL.md 用 `<pre>` 显示纯文本。渲染 Markdown 就得处理 HTML 注入，那是要单独审的安全面；纯文本对审核足够。
- 状态管理库——会话状态一个 `reactive` 单例就够，Pinia/Vuex 是过度设计。

- [ ] **Step 2: 写 PKCE 模块**

`apps/web/src/auth/pkce.ts`：

```ts
// OIDC 授权码 + PKCE。Web Crypto 原生够用，不引 oidc-client-ts。
// Keycloak client 是 public 的，没有 client_secret，只有 code_verifier 能证明是我们发的请求。

export const ISSUER = 'http://127.0.0.1:8180/kc/realms/skillmesh'
export const CLIENT_ID = 'skillmesh-cli'
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`

export interface TokenResponse {
  access_token: string
  refresh_token: string
  expires_in: number
}

function base64Url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((b) => { binary += String.fromCharCode(b) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** 32 字节随机 verifier。 */
export function randomVerifier(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)))
}

/** SHA-256 后 base64url，即 S256。 */
export async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

export function buildAuthorizeUrl(redirectUri: string, state: string, challenge: string): string {
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  })
  return `${ISSUER}/protocol/openid-connect/auth?${params.toString()}`
}

async function postToken(body: URLSearchParams): Promise<TokenResponse> {
  const response = await fetch(TOKEN_ENDPOINT, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`令牌交换失败: ${response.status} ${text}`)
  }
  return (await response.json()) as TokenResponse
}

export function exchangeCode(code: string, verifier: string, redirectUri: string): Promise<TokenResponse> {
  return postToken(new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    client_id: CLIENT_ID,
    code_verifier: verifier,
  }))
}

/** 静默续期。Keycloak 的 public client 默认允许 refresh。 */
export function refreshToken(refresh: string): Promise<TokenResponse> {
  return postToken(new URLSearchParams({
    grant_type: 'refresh_token',
    refresh_token: refresh,
    client_id: CLIENT_ID,
  }))
}
```

- [ ] **Step 3: 写会话模块**

`apps/web/src/auth/session.ts`：

```ts
import { reactive } from 'vue'
import { exchangeCode, refreshToken } from './pkce'

const STORAGE_KEY = 'skillmesh_session'
/** 提前 30 秒续期，别让请求卡在过期上。 */
const REFRESH_MARGIN_MS = 30_000

export interface Me {
  id: string
  display_name: string
  email: string | null
  status: string
}

export interface OrgMembership {
  id: string
  slug: string
  name: string
  role: string
}

export interface Session {
  accessToken: string
  refreshToken: string
  expiresAt: number
  user: Me | null
  organizations: OrgMembership[]
}

function empty(): Session {
  return { accessToken: '', refreshToken: '', expiresAt: 0, user: null, organizations: [] }
}

function load(): Session {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return empty()
    const parsed = JSON.parse(raw) as Session
    return { ...empty(), ...parsed }
  } catch {
    return empty()
  }
}

export const session = reactive<Session>(load())

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    expiresAt: session.expiresAt,
    user: session.user,
    organizations: session.organizations,
  }))
}

export function setTokens(tokens: { access_token: string; refresh_token: string; expires_in: number }) {
  session.accessToken = tokens.access_token
  session.refreshToken = tokens.refresh_token
  session.expiresAt = Date.now() + tokens.expires_in * 1000
  persist()
}

export function logout() {
  Object.assign(session, empty())
  localStorage.removeItem(STORAGE_KEY)
}

/** 拉一次 /me，顺便把组织列表带回来。 */
export async function loadMe(apiBase = '/api/v1') {
  const response = await fetch(`${apiBase}/me`, {
    headers: { Authorization: `Bearer ${session.accessToken}` },
  })
  if (!response.ok) throw new Error(`拉取当前用户失败: ${response.status}`)
  const body = await response.json()
  session.user = body.user
  session.organizations = body.organizations ?? []
  persist()
  return body
}

/** 快过期就续期。续不了就登出，交给调用方跳登录页。 */
export async function ensureFresh(): Promise<boolean> {
  if (!session.accessToken) return false
  if (session.expiresAt - Date.now() > REFRESH_MARGIN_MS) return true
  if (!session.refreshToken) return false
  try {
    setTokens(await refreshToken(session.refreshToken))
    return true
  } catch {
    logout()
    return false
  }
}

/** 走完授权码换令牌。 */
export async function completeLogin(code: string, verifier: string, redirectUri: string) {
  setTokens(await exchangeCode(code, verifier, redirectUri))
  await loadMe()
}
```

- [ ] **Step 4: 改 api.ts 让 token 可选并支持其他方法**

`apps/web/src/api.ts` 的 `request` 改成：

```ts
import { session } from './auth/session'

async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown,
  token?: string,
): Promise<T> {
  const bearer = token ?? session.accessToken
  const response = await fetch(BASE + path, {
    method,
    headers: {
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!response.ok) {
    let code = `HTTP_${response.status}`
    let message = response.statusText
    try {
      const parsed = await response.json()
      code = parsed.code ?? code
      message = parsed.detail ?? message
    } catch {
      // 非 JSON 错误体，沿用状态码
    }
    throw new ApiError(code, response.status, message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}
```

参数顺序定成 `(path, method, body, token)`——method 在前，因为后面的接口函数几乎都要用非 GET，token 永远走会话里的那个。

其余函数（`searchSkills` / `getSkill` / `getVersions`）的 token 参数改成可选：

```ts
export function searchSkills(q: string, token?: string): Promise<SkillPage> {
  const params = new URLSearchParams()
  if (q) params.set('q', q)
  const suffix = params.toString() ? `?${params}` : ''
  return request<SkillPage>(`/skills${suffix}`, 'GET', undefined, token)
}

export function getSkill(id: string, token?: string): Promise<Skill> {
  return request<Skill>(`/skills/${id}`, 'GET', undefined, token)
}

export function getVersions(id: string, token?: string): Promise<VersionPage> {
  return request<VersionPage>(`/skills/${id}/versions`, 'GET', undefined, token)
}
```

**既有测试 `apps/web/tests/pages.spec.ts` 不改**——它显式传 token，行为不变。

- [ ] **Step 5: 写路由**

`apps/web/src/router.ts`：

```ts
import { createRouter, createWebHistory } from 'vue-router'
import { session } from './auth/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./pages/LoginPage.vue') },
    { path: '/invite/:token', component: () => import('./pages/InvitePage.vue') },
    { path: '/', component: () => import('./pages/MarketPage.vue') },
    { path: '/team', component: () => import('./pages/TeamPage.vue') },
    { path: '/reviews', component: () => import('./pages/ReviewQueuePage.vue') },
    { path: '/reviews/:skillId/:version', component: () => import('./pages/ReviewDetailPage.vue') },
  ],
})

// 没登录就去登录页，并记住原来想去哪
router.beforeEach((to) => {
  if (to.path === '/login' || to.path.startsWith('/invite/')) return true
  if (!session.accessToken) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
```

**注意**：`/invite/:token` 是公开的，但「接受邀请」这个动作需要登录——InvitePage 内部发现没登录时，引导用户登录并在登录后回到原邀请链接（LoginPage 读 `redirect` 参数）。

- [ ] **Step 6: 写登录页**

`apps/web/src/pages/LoginPage.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { buildAuthorizeUrl, codeChallenge, randomVerifier } from '../auth/pkce'
import { completeLogin, session } from '../auth/session'

const route = useRoute()
const router = useRouter()
const error = ref('')
const busy = ref(false)

const VERIFIER_KEY = 'skillmesh_pkce_verifier'
const STATE_KEY = 'skillmesh_pkce_state'

function redirectUri(): string {
  return `${window.location.origin}/login`
}

async function startLogin() {
  busy.value = true
  error.value = ''
  try {
    const verifier = randomVerifier()
    const state = randomVerifier()
    sessionStorage.setItem(VERIFIER_KEY, verifier)
    sessionStorage.setItem(STATE_KEY, state)
    const challenge = await codeChallenge(verifier)
    window.location.href = buildAuthorizeUrl(redirectUri(), state, challenge)
  } catch (e) {
    error.value = String(e)
    busy.value = false
  }
}

onMounted(async () => {
  const code = route.query.code as string | undefined
  if (!code) return
  const state = route.query.state as string | undefined
  const expected = sessionStorage.getItem(STATE_KEY)
  if (!expected || state !== expected) {
    error.value = '登录状态校验失败，请重试'
    return
  }
  const verifier = sessionStorage.getItem(VERIFIER_KEY)
  if (!verifier) {
    error.value = '登录状态丢失，请重试'
    return
  }
  busy.value = true
  try {
    await completeLogin(code, verifier, redirectUri())
    sessionStorage.removeItem(VERIFIER_KEY)
    sessionStorage.removeItem(STATE_KEY)
    const redirect = (route.query.redirect as string | undefined) ?? '/'
    await router.replace(redirect)
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
})
</script>

<template>
  <el-card class="sm-login" shadow="never">
    <h1 class="sm-h1">SkillMesh</h1>
    <p class="sm-muted">用团队账号登录，管理组织、团队与审核。</p>
    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
    />
    <el-button
      type="primary"
      :loading="busy"
      data-test="login"
      @click="startLogin"
    >{{ busy ? '登录中' : '登录' }}</el-button>
  </el-card>
</template>
```

- [ ] **Step 7: 写布局样式 app.css**

`apps/web/src/styles/app.css`——只放布局与工具类，组件外观交给 Element Plus：

```css
/* 布局与工具类。组件外观用 Element Plus，这里只管骨架。 */

.sm-shell { min-height: 100vh; display: flex; flex-direction: column; }

.sm-topbar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 12px 24px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  background: var(--el-bg-color);
}

.sm-brand { font-weight: 600; font-size: 16px; color: var(--el-text-color-primary); }
.sm-nav { display: flex; gap: 4px; }
.sm-nav a {
  color: var(--el-text-color-regular);
  padding: 4px 10px;
  border-radius: var(--el-radius-base);
}
.sm-nav a:hover { background: var(--el-fill-color-light); }
.sm-nav a.router-link-active { color: var(--el-color-primary); background: var(--el-color-primary-light-9); }
.sm-topbar-right { margin-left: auto; display: flex; align-items: center; gap: 12px; }

.sm-main { flex: 1; padding: 24px; max-width: 72rem; width: 100%; margin: 0 auto; }

.sm-h1 { font-size: 22px; font-weight: 600; margin: 0 0 8px; }
.sm-h2 { font-size: 16px; font-weight: 600; margin: 0 0 12px; }
.sm-muted { color: var(--el-text-color-secondary); margin: 0 0 12px; }
.sm-mono { font-family: var(--el-font-family-monospace, ui-monospace, monospace); }
.sm-break { overflow-wrap: anywhere; word-break: break-all; }

.sm-login { max-width: 24rem; margin: 12vh auto 0; }
.sm-login .el-alert { margin-bottom: 12px; }
.sm-login .el-button { width: 100%; }

.sm-pre {
  margin: 0;
  padding: 12px;
  background: var(--el-fill-color-light);
  border-radius: var(--el-radius-base);
  font-family: var(--el-font-family-monospace, ui-monospace, monospace);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

/* 列表与表单行 */
.sm-search { display: flex; gap: 8px; margin-bottom: 16px; }
.sm-grow { flex: 1; }
.sm-list { display: grid; gap: 12px; }
.sm-item { cursor: pointer; }
.sm-item-head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }

/* 间距与字号 */
.sm-mt-8 { margin-top: 8px; }
.sm-mt-12 { margin-top: 12px; }
.sm-mt-24 { margin-top: 24px; }
.sm-mb-12 { margin-bottom: 12px; }
.sm-mb-16 { margin-bottom: 16px; }
.sm-ml-4 { margin-left: 4px; }
.sm-text-sm { font-size: 13px; }

@media (max-width: 768px) {
  .sm-main { padding: 16px; }
  .sm-topbar { padding: 12px 16px; flex-wrap: wrap; }
}
```

- [ ] **Step 8: 注册 Element Plus 与路由**

`apps/web/src/main.ts`：

```ts
import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/app.css'

createApp(App).use(ElementPlus).use(router).mount('#app')
```

- [ ] **Step 9: 写 App.vue 布局**

`apps/web/src/App.vue` 换成：

```vue
<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ensureFresh, loadMe, logout, session } from '../auth/session'

const router = useRouter()

onMounted(async () => {
  if (!session.accessToken) return
  if (await ensureFresh()) {
    try {
      await loadMe()
    } catch {
      // 拉不到就当没登录，路由守卫会处理
    }
  }
})

function signOut() {
  logout()
  router.push('/login')
}
</script>

<template>
  <div class="sm-shell">
    <header class="sm-topbar">
      <router-link class="sm-brand" to="/">SkillMesh</router-link>
      <nav class="sm-nav">
        <router-link to="/">市场</router-link>
        <router-link to="/team">团队</router-link>
        <router-link to="/reviews">审核</router-link>
      </nav>
      <div class="sm-topbar-right">
        <el-tag v-if="session.user" type="info" effect="plain" data-test="who">
          {{ session.user.display_name }}
        </el-tag>
        <el-button v-if="session.user" text data-test="logout" @click="signOut">
          登出
        </el-button>
      </div>
    </header>
    <main class="sm-main">
      <router-view />
    </main>
  </div>
</template>
```

- [ ] **Step 10: 写失败的测试**

`apps/web/tests/auth.spec.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { codeChallenge, randomVerifier } from '../src/auth/pkce'
import LoginPage from '../src/pages/LoginPage.vue'

describe('PKCE', () => {
  it('verifier 是 43 位的 base64url', () => {
    const verifier = randomVerifier()
    expect(verifier).toHaveLength(43)
    expect(verifier).toMatch(/^[A-Za-z0-9_-]+$/)
  })

  it('challenge 是 SHA-256 后的 base64url，32 字节', async () => {
    const challenge = await codeChallenge(randomVerifier())
    expect(challenge).toHaveLength(43)
    expect(challenge).toMatch(/^[A-Za-z0-9_-]+$/)
  })

  it('同样的 verifier 得到同样的 challenge', async () => {
    const verifier = randomVerifier()
    expect(await codeChallenge(verifier)).toBe(await codeChallenge(verifier))
  })
})

describe('LoginPage', () => {
  it('没有授权码时显示登录按钮', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginPage }],
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginPage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.find('[data-test="login"]').exists()).toBe(true)
  })
})
```

**注意**：测试里也要注册 Element Plus——`global: { plugins: [router, ElementPlus] }`。不注册的话 `el-card` 会渲染成未知标签，样式全丢（断言还能过，但没测到真实渲染）。

- [ ] **Step 11: 跑测试确认通过**

```bash
cd apps/web && pnpm test
```

Expected：PASS（既有 5 个用例 + 新增 4 个）。

- [ ] **Step 12: 提交**

```bash
git add apps/web/package.json apps/web/pnpm-lock.yaml apps/web/src apps/web/tests
git commit -m "feat(web): OIDC 登录——手写 PKCE、会话模块与 Element Plus 骨架"
```

---

### Task 7: 市场页（搜索与详情合一）

**Files:**

- Create: `apps/web/src/pages/MarketPage.vue`
- Delete: `apps/web/src/components/SearchPage.vue`
- Delete: `apps/web/src/components/DetailPage.vue`
- Modify: `apps/web/tests/pages.spec.ts`（改测 MarketPage）

**Interfaces:**

- Consumes: Task 6 的 `router` 与 `session`；Element Plus 组件
- Produces: `MarketPage.vue`——搜索 + 详情合一页，用 `?skill=<id>` query 切换

- [ ] **Step 1: 写 MarketPage**

`apps/web/src/pages/MarketPage.vue`：

```vue
<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, getSkill, getVersions, searchSkills } from '../api'
import type { Skill, SkillVersion } from '../types'

const route = useRoute()
const router = useRouter()

const query = ref('')
const skills = ref<Skill[]>([])
const error = ref('')
const loading = ref(false)
const searched = ref(false)

const openId = ref<string | null>((route.query.skill as string | undefined) ?? null)
const detail = ref<Skill | null>(null)
const versions = ref<SkillVersion[]>([])

async function search() {
  loading.value = true
  error.value = ''
  try {
    const page = await searchSkills(query.value)
    skills.value = page.items
    searched.value = true
  } catch (e) {
    skills.value = []
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}

async function open(id: string) {
  openId.value = id
  await router.replace({ query: { ...route.query, skill: id } })
  try {
    detail.value = await getSkill(id)
    const page = await getVersions(id)
    versions.value = page.items
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

function back() {
  openId.value = null
  detail.value = null
  versions.value = []
  const next = { ...route.query }
  delete next.skill
  router.replace({ query: next })
}

watch(() => route.query.skill, (value) => {
  if (typeof value === 'string' && value !== openId.value) open(value)
})
</script>

<template>
  <el-card v-if="openId && detail" shadow="never">
    <el-button text data-test="back" @click="back">← 返回</el-button>
    <h1 class="sm-h1">{{ detail.name }}</h1>
    <p class="sm-mono sm-muted">{{ detail.slug }}</p>
    <p>{{ detail.description }}</p>
    <p>
      <el-tag size="small">{{ detail.visibility }}</el-tag>
      <el-tag size="small" type="info" class="sm-ml-4">最新 {{ detail.latest_version ?? '—' }}</el-tag>
    </p>
    <h2 class="sm-h2 sm-mt-24">版本</h2>
    <el-table :data="versions" size="small">
      <el-table-column prop="version" label="版本" width="120" />
      <el-table-column prop="status" label="状态" width="120" />
      <el-table-column prop="channel" label="通道" width="100" />
      <el-table-column label="提交" min-width="140">
        <template #default="scope">
          <span class="sm-mono">{{ scope.row.source_commit ?? '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="摘要" min-width="140">
        <template #default="scope">
          <span class="sm-mono">{{ scope.row.artifact?.sha256.slice(0, 12) ?? '—' }}</span>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <section v-else>
    <div class="sm-search">
      <el-input
        v-model="query"
        placeholder="搜索 Skill 名称、描述或标签"
        clearable
        @keyup.enter="search"
      />
      <el-button type="primary" :loading="loading" data-test="search" @click="search">
        搜索
      </el-button>
    </div>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />
    <el-empty
      v-else-if="searched && !skills.length"
      description="没有匹配的 Skill"
    />

    <div v-else class="sm-list">
      <el-card
        v-for="skill in skills"
        :key="skill.id"
        shadow="hover"
        class="sm-item"
        data-test="skill-item"
        @click="open(skill.id)"
      >
        <div class="sm-item-head">
          <strong>{{ skill.name }}</strong>
          <code class="sm-mono sm-muted">{{ skill.slug }}</code>
          <el-tag size="small">{{ skill.visibility }}</el-tag>
        </div>
        <p class="sm-muted">{{ skill.description }}</p>
        <p class="sm-muted sm-text-sm">最新版本 {{ skill.latest_version ?? '—' }}</p>
      </el-card>
    </div>
  </section>
</template>
```

- [ ] **Step 2: 改测试并删掉旧组件**

`apps/web/tests/pages.spec.ts` 改成测 `MarketPage`（把原来的 `SearchPage` / `DetailPage` 用例搬过来）：

```ts
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import MarketPage from '../src/pages/MarketPage.vue'

const SKILL = {
  id: 'a',
  slug: 'java-debug',
  name: 'Java Debug',
  description: '调试 Java 服务',
  visibility: 'private',
  status: 'active',
  tags: ['java'],
  latest_version: '1.2.0',
  created_at: '',
  updated_at: '',
}

const VERSION = {
  id: 'v1',
  skill_id: 'a',
  version: '1.2.0',
  channel: 'stable',
  status: 'published',
  source_commit: 'abc123def456',
  artifact: {
    id: 'art1',
    sha256: 'a'.repeat(64),
    size_bytes: 2048,
    content_type: 'application/zip',
  },
  published_at: null,
  created_at: '',
}

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

function problemResponse(status: number, code: string, detail: string) {
  return { ok: false, status, statusText: 'Error', json: async () => ({ code, detail }) }
}

async function mountMarket() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: MarketPage }],
  })
  await router.push('/')
  await router.isReady()
  return mount(MarketPage, { global: { plugins: [router, ElementPlus] } })
}

describe('MarketPage', () => {
  it('渲染搜索结果', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ items: [SKILL], next_cursor: null, has_more: false }),
    ))
    const wrapper = await mountMarket()
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('Java Debug')
  })

  it('把关键字放进查询参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ items: [], next_cursor: null, has_more: false }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountMarket()
    await wrapper.find('input').setValue('rust')
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/v1/skills?q=rust')
  })

  it('失败时展示错误码而不是静默失败', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problemResponse(401, 'UNAUTHORIZED', '令牌无效')))
    const wrapper = await mountMarket()
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toContain('UNAUTHORIZED')
  })
})
```

删掉旧组件：

```bash
git rm apps/web/src/components/SearchPage.vue apps/web/src/components/DetailPage.vue
```

- [ ] **Step 3: 跑测试确认通过**

```bash
cd apps/web && pnpm test && pnpm exec vue-tsc --noEmit
```

Expected：测试 PASS，类型检查无错。

- [ ] **Step 4: 提交**

```bash
git add apps/web
git commit -m "feat(web): 市场页——搜索与详情合一，用 Element Plus 表格"
```

---

### Task 8: 团队与成员页

**Files:**

- Create: `apps/web/src/pages/TeamPage.vue`
- Create: `apps/web/src/api.ts`（加团队相关函数）
- Create: `apps/web/src/types.ts`（加团队相关类型）
- Test: `apps/web/tests/team.spec.ts`

**Interfaces:**

- Consumes: Task 2/3 的端点：`GET /organizations/{id}/members`、`GET /organizations/{id}/teams`、`GET /organizations/{id}/invitations`、`POST /organizations/{id}/invitations`、`DELETE /organizations/{id}/invitations/{id}`
- Produces: `api.ts` 新增 `listTeams`、`listOrgMembers`、`listTeamMembers`、`listInvitations`、`createInvitation`、`revokeInvitation`、`updateOrgRole`；`types.ts` 新增 `Team`、`Member`、`Invitation`

- [ ] **Step 1: 加类型**

`apps/web/src/types.ts` 末尾加：

```ts
export interface Team {
  id: string
  organization_id: string
  slug: string
  name: string
  status: string
}

export interface Member {
  user_id: string
  display_name: string
  email: string | null
  role: string
  joined_at: string
}

export interface Invitation {
  id: string
  organization_name: string
  team_name: string | null
  org_role: string
  team_role: string
  status: string
  expires_at: string
  created_at: string
}

export interface CreatedInvitation {
  id: string
  organization_slug: string
  organization_name: string
  team_slug: string | null
  team_name: string | null
  org_role: string
  team_role: string
  expires_at: string
  invite_url: string
  token: string
}
```

- [ ] **Step 2: 加 API 函数**

`apps/web/src/api.ts` 末尾加：

```ts
export function listTeams(organizationId: string): Promise<Team[]> {
  return request<Team[]>(`/organizations/${organizationId}/teams`)
}

export function listOrgMembers(organizationId: string): Promise<Member[]> {
  return request<Member[]>(`/organizations/${organizationId}/members`)
}

export function listTeamMembers(organizationId: string, teamId: string): Promise<Member[]> {
  return request<Member[]>(`/organizations/${organizationId}/teams/${teamId}/members`)
}

export function listInvitations(organizationId: string): Promise<Invitation[]> {
  return request<Invitation[]>(`/organizations/${organizationId}/invitations`)
}

export function createInvitation(organizationId: string, body: {
  team_id?: string
  org_role?: string
  team_role?: string
}): Promise<CreatedInvitation> {
  return request<CreatedInvitation>(
    `/organizations/${organizationId}/invitations`, 'POST', body)
}

export function revokeInvitation(organizationId: string, invitationId: string): Promise<void> {
  return request<void>(`/organizations/${organizationId}/invitations/${invitationId}`, 'DELETE')
}

export function updateOrgRole(organizationId: string, userId: string, role: string): Promise<void> {
  return request<void>(`/organizations/${organizationId}/members/${userId}`, 'PATCH', { role })
}
```

- [ ] **Step 3: 写 TeamPage**

`apps/web/src/pages/TeamPage.vue`：

```vue
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ApiError, createInvitation, listInvitations, listOrgMembers, listTeamMembers, listTeams, updateOrgRole } from '../api'
import { session } from '../auth/session'
import type { Invitation, Member, Team } from '../types'

const org = computed(() => session.organizations[0] ?? null)
const teams = ref<Team[]>([])
const members = ref<Member[]>([])
const invitations = ref<Invitation[]>([])
const error = ref('')
const notice = ref('')
const inviteLink = ref('')
const selectedTeam = ref('')
const inviteRole = ref('member')
const inviteOrgRole = ref('member')

async function load() {
  if (!org.value) return
  error.value = ''
  try {
    teams.value = await listTeams(org.value.id)
    if (!selectedTeam.value && teams.value.length) selectedTeam.value = teams.value[0].id
    members.value = await listOrgMembers(org.value.id)
    invitations.value = await listInvitations(org.value.id)
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

async function loadTeamMembers() {
  if (!org.value || !selectedTeam.value) return
  members.value = await listTeamMembers(org.value.id, selectedTeam.value)
}

async function invite() {
  if (!org.value) return
  error.value = ''
  notice.value = ''
  try {
    const created = await createInvitation(org.value.id, {
      team_id: selectedTeam.value || undefined,
      org_role: inviteOrgRole.value,
      // 服务端要求：给了团队角色就必须给团队，否则 400。没选团队时别发。
      team_role: selectedTeam.value ? inviteRole.value : undefined,
    })
    inviteLink.value = created.invite_url
    notice.value = '邀请已生成，把链接发给对方。'
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

async function revoke(id: string) {
  if (!org.value) return
  await revokeInvitation(org.value.id, id)
  await load()
}

/** 改组织角色。被邀请进来的人默认是 member，要往团队发布得有 publisher 以上。 */
async function changeRole(member: Member, role: string) {
  if (!org.value || role === member.role) return
  error.value = ''
  try {
    await updateOrgRole(org.value.id, member.user_id, role)
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="sm-h1">团队</h1>

    <el-empty v-if="!org" description="你还没有加入任何组织。" />

    <template v-else>
      <p class="sm-muted">
        组织 {{ org.name }} · 我的角色 {{ org.role }}
      </p>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">团队</h2>
        <div class="sm-search">
          <el-select
            v-model="selectedTeam"
            placeholder="（不指定团队）"
            data-test="team-select"
            class="sm-grow"
            @change="loadTeamMembers"
          >
            <el-option label="（不指定团队）" value="" />
            <el-option v-for="team in teams" :key="team.id" :label="team.name" :value="team.id" />
          </el-select>
        </div>

        <el-table :data="members" size="small">
          <el-table-column prop="display_name" label="成员" min-width="140" />
          <el-table-column label="邮箱" min-width="160">
            <template #default="scope">{{ scope.row.email ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="角色" width="170">
            <template #default="scope">
              <el-select
                :model-value="scope.row.role"
                size="small"
                data-test="role-edit"
                @change="(value: string) => changeRole(scope.row, value)"
              >
                <el-option label="owner" value="owner" />
                <el-option label="admin" value="admin" />
                <el-option label="publisher" value="publisher" />
                <el-option label="reviewer" value="reviewer" />
                <el-option label="member" value="member" />
                <el-option label="viewer" value="viewer" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="加入时间" width="120">
            <template #default="scope">
              <span class="sm-text-sm">{{ scope.row.joined_at.slice(0, 10) }}</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!members.length" description="还没有成员" :image-size="60" />
      </el-card>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">邀请</h2>
        <div class="sm-search">
          <el-select v-model="inviteOrgRole" data-test="org-role-select" class="sm-grow">
            <el-option label="组织角色 member" value="member" />
            <el-option label="组织角色 publisher" value="publisher" />
            <el-option label="组织角色 reviewer" value="reviewer" />
            <el-option label="组织角色 admin" value="admin" />
          </el-select>
          <el-select v-model="inviteRole" data-test="role-select" class="sm-grow" :disabled="!selectedTeam">
            <el-option label="团队角色 member" value="member" />
            <el-option label="团队角色 publisher" value="publisher" />
            <el-option label="团队角色 reviewer" value="reviewer" />
            <el-option label="团队角色 admin" value="admin" />
          </el-select>
          <el-button type="primary" data-test="invite" @click="invite">生成邀请链接</el-button>
        </div>

        <el-alert
          v-if="inviteLink"
          data-test="invite-link"
          :title="inviteLink"
          type="success"
          :closable="false"
          show-icon
          class="sm-mb-12"
        />
        <el-alert
          v-if="notice"
          :title="notice"
          type="info"
          :closable="false"
          class="sm-mb-12"
        />

        <el-table :data="invitations" size="small">
          <el-table-column label="团队" min-width="120">
            <template #default="scope">{{ scope.row.team_name ?? '—' }}</template>
          </el-table-column>
          <el-table-column prop="team_role" label="角色" width="110" />
          <el-table-column label="状态" width="110">
            <template #default="scope">
              <el-tag
                size="small"
                :type="scope.row.status === 'pending' ? 'warning' : 'info'"
              >{{ scope.row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="过期" width="120">
            <template #default="scope">
              <span class="sm-text-sm">{{ scope.row.expires_at.slice(0, 10) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="" width="90">
            <template #default="scope">
              <el-button
                v-if="scope.row.status === 'pending'"
                text
                type="danger"
                @click="revoke(scope.row.id)"
              >撤销</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!invitations.length" description="还没有发出过邀请" :image-size="60" />
      </el-card>

      <el-alert
        v-if="error"
        data-test="error"
        :title="error"
        type="error"
        :closable="false"
        show-icon
      />
    </template>
  </section>
</template>
```

- [ ] **Step 4: 写失败的测试**

`apps/web/tests/team.spec.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import TeamPage from '../src/pages/TeamPage.vue'
import { session } from '../src/auth/session'

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/team', component: TeamPage }],
  })
}

describe('TeamPage', () => {
  it('没有组织时提示先建组织', async () => {
    session.organizations = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])))
    const router = makeRouter()
    await router.push('/team')
    await router.isReady()
    const wrapper = mount(TeamPage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('还没有加入任何组织')
  })

  it('列出成员与邀请', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/members')) {
        return jsonResponse([{
          user_id: 'u1', display_name: 'Dev Two', email: 'd2@example.com',
          role: 'member', joined_at: '2026-10-20T00:00:00Z',
        }])
      }
      if (url.includes('/invitations')) {
        return jsonResponse([{
          id: 'i1', organization_name: 'Acme', team_name: 'Platform',
          org_role: 'member', team_role: 'member', status: 'pending',
          expires_at: '2026-10-27T00:00:00Z', created_at: '2026-10-20T00:00:00Z',
        }])
      }
      return jsonResponse([{ id: 't1', organization_id: 'org1', slug: 'platform', name: 'Platform', status: 'active' }])
    }))
    const router = makeRouter()
    await router.push('/team')
    await router.isReady()
    const wrapper = mount(TeamPage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="member-row"]').text()).toContain('Dev Two')
    expect(wrapper.find('[data-test="invitation-row"]').text()).toContain('Platform')
  })
})
```

- [ ] **Step 5: 跑测试确认通过**

```bash
cd apps/web && pnpm test && pnpm exec vue-tsc --noEmit
```

- [ ] **Step 6: 提交**

```bash
git add apps/web
git commit -m "feat(web): 团队与成员页——成员列表、角色与邀请"
```

---

### Task 9: 邀请接受页

**Files:**

- Create: `apps/web/src/pages/InvitePage.vue`
- Create: `apps/web/src/api.ts`（加 `getInvitation`、`acceptInvitation`）
- Create: `apps/web/src/types.ts`（加 `InvitationSummary`）
- Test: `apps/web/tests/invite.spec.ts`

**Interfaces:**

- Consumes: Task 2 的 `GET /invitations/{token}`（公开）与 `POST /invitations/{token}/accept`（需登录）
- Produces: `api.ts` 新增 `getInvitation(token)`、`acceptInvitation(token)`

- [ ] **Step 1: 加类型与 API 函数**

`types.ts`：

```ts
export interface InvitationSummary {
  id: string
  organization_name: string
  team_name: string | null
  org_role: string
  team_role: string
  status: string
  expires_at: string
  created_at: string
}
```

`api.ts`：

```ts
export function getInvitation(token: string): Promise<InvitationSummary> {
  return request<InvitationSummary>(`/invitations/${encodeURIComponent(token)}`)
}

export function acceptInvitation(token: string): Promise<{ message: string }> {
  return request<{ message: string }>(
    `/invitations/${encodeURIComponent(token)}/accept`, undefined, 'POST')
}
```

- [ ] **Step 2: 写 InvitePage**

`apps/web/src/pages/InvitePage.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, acceptInvitation, getInvitation } from '../api'
import { session } from '../auth/session'
import type { InvitationSummary } from '../types'

const route = useRoute()
const router = useRouter()

const invitation = ref<InvitationSummary | null>(null)
const error = ref('')
const busy = ref(false)
const done = ref(false)

async function load() {
  error.value = ''
  try {
    invitation.value = await getInvitation(String(route.params.token))
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

async function accept() {
  busy.value = true
  error.value = ''
  try {
    await acceptInvitation(String(route.params.token))
    done.value = true
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card class="sm-login" shadow="never">
    <h1 class="sm-h1">加入团队</h1>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />

    <template v-if="invitation">
      <p>
        组织 <strong>{{ invitation.organization_name }}</strong>
        <template v-if="invitation.team_name">
          · 团队 <strong>{{ invitation.team_name }}</strong>
        </template>
      </p>
      <p class="sm-muted">
        角色 {{ invitation.org_role }}<template v-if="invitation.team_name"> / {{ invitation.team_role }}</template>
      </p>
      <el-alert
        v-if="invitation.status !== 'pending'"
        data-test="invalid"
        :title="`这份邀请已经${invitation.status === 'expired' ? '过期' : '失效'}。`"
        type="warning"
        :closable="false"
        show-icon
        class="sm-mb-12"
      />

      <el-alert
        v-if="done"
        data-test="done"
        title="已加入，去团队页看看。"
        type="success"
        :closable="false"
        show-icon
        class="sm-mb-12"
      />

      <el-button
        v-if="!session.accessToken"
        type="primary"
        data-test="login-first"
        @click="router.push({ path: '/login', query: { redirect: route.fullPath } })"
      >登录后接受</el-button>

      <el-button
        v-else-if="invitation.status === 'pending' && !done"
        type="primary"
        :loading="busy"
        data-test="accept"
        @click="accept"
      >{{ busy ? '接受中' : '接受邀请' }}</el-button>

      <router-link v-if="done" to="/team">
        <el-button class="sm-mt-8">去团队页</el-button>
      </router-link>
    </template>
  </el-card>
</template>
```

- [ ] **Step 3: 写失败的测试**

`apps/web/tests/invite.spec.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import InvitePage from '../src/pages/InvitePage.vue'
import { session } from '../src/auth/session'

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

describe('InvitePage', () => {
  it('展示邀请摘要，未登录时引导先登录', async () => {
    session.accessToken = ''
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      id: 'i1', organization_name: 'Acme', team_name: 'Platform',
      org_role: 'member', team_role: 'member', status: 'pending',
      expires_at: '2026-10-27T00:00:00Z', created_at: '2026-10-20T00:00:00Z',
    })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/invite/:token', component: InvitePage }],
    })
    await router.push('/invite/abc')
    await router.isReady()
    const wrapper = mount(InvitePage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.text()).toContain('Acme')
    expect(wrapper.text()).toContain('Platform')
    expect(wrapper.find('[data-test="login-first"]').exists()).toBe(true)
  })

  it('已登录时显示接受按钮', async () => {
    session.accessToken = 'token'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      id: 'i1', organization_name: 'Acme', team_name: null,
      org_role: 'member', team_role: 'member', status: 'pending',
      expires_at: '2026-10-27T00:00:00Z', created_at: '2026-10-20T00:00:00Z',
    })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/invite/:token', component: InvitePage }],
    })
    await router.push('/invite/abc')
    await router.isReady()
    const wrapper = mount(InvitePage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="accept"]').exists()).toBe(true)
  })
})
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd apps/web && pnpm test && pnpm exec vue-tsc --noEmit
```

- [ ] **Step 5: 提交**

```bash
git add apps/web
git commit -m "feat(web): 邀请接受页"
```

---

### Task 10: 审核队列页与审核详情页

**Files:**

- Create: `apps/web/src/pages/ReviewQueuePage.vue`
- Create: `apps/web/src/pages/ReviewDetailPage.vue`
- Create: `apps/web/src/api.ts`（加 `listReviews`、`getReviewDetail`、`approveVersion`、`rejectVersion`）
- Create: `apps/web/src/types.ts`（加审核相关类型）
- Test: `apps/web/tests/reviews.spec.ts`

**Interfaces:**

- Consumes: Task 4 的 `GET /reviews`、`GET /reviews/{skillId}/{version}`；既有的 `POST /skills/{id}/versions/{v}/approve|reject`
- Produces: `api.ts` 新增 `listReviews`、`getReviewDetail`、`approveVersion`、`rejectVersion`；`types.ts` 新增 `PendingReview`、`ReviewDetail`、`FileChange`

- [ ] **Step 1: 加类型**

`types.ts`：

```ts
export interface ReviewScan {
  status: string
  summary: string
}

export interface ReviewSource {
  provider: string | null
  repository: string | null
  ref: string | null
  commit: string | null
}

export interface PendingReview {
  skill_id: string
  skill_slug: string
  skill_name: string
  version: string
  channel: string
  submitted_by: string | null
  submitted_at: string | null
  scan: ReviewScan
  source: ReviewSource
}

export interface FileChange {
  path: string
  kind: 'added' | 'modified' | 'removed'
  added: number
  removed: number
  truncated: boolean
}

export interface ReviewDetail {
  item: PendingReview
  skill_md: string
  changelog: string
  findings: Record<string, unknown>[]
  diff: {
    base_version: string
    truncated: boolean
    files: FileChange[]
  }
}
```

- [ ] **Step 2: 加 API 函数**

`api.ts`：

```ts
export function listReviews(status = 'pending_review'): Promise<PendingReview[]> {
  return request<PendingReview[]>(`/reviews?status=${encodeURIComponent(status)}`)
}

export function getReviewDetail(skillId: string, version: string): Promise<ReviewDetail> {
  return request<ReviewDetail>(`/reviews/${skillId}/${encodeURIComponent(version)}`)
}

export function approveVersion(skillId: string, version: string, comment = ''): Promise<unknown> {
  return request(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/approve`,
    undefined, 'POST', { comment })
}

export function rejectVersion(skillId: string, version: string, comment = ''): Promise<unknown> {
  return request(
    `/skills/${skillId}/versions/${encodeURIComponent(version)}/reject`,
    undefined, 'POST', { comment })
}
```

- [ ] **Step 3: 写 ReviewQueuePage**

`apps/web/src/pages/ReviewQueuePage.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, listReviews } from '../api'
import type { PendingReview } from '../types'

const router = useRouter()
const reviews = ref<PendingReview[]>([])
const error = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    reviews.value = await listReviews()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}

function open(review: PendingReview) {
  router.push(`/reviews/${review.skill_id}/${review.version}`)
}

onMounted(load)
</script>

<template>
  <section>
    <h1 class="sm-h1">待审版本</h1>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />
    <el-empty
      v-else-if="!loading && !reviews.length"
      description="没有待审的版本"
    />

    <div v-else class="sm-list">
      <el-card
        v-for="review in reviews"
        :key="`${review.skill_id}-${review.version}`"
        shadow="hover"
        class="sm-item"
        data-test="review-item"
        @click="open(review)"
      >
        <div class="sm-item-head">
          <strong>{{ review.skill_name }}</strong>
          <code class="sm-mono sm-muted">{{ review.skill_slug }}</code>
          <el-tag size="small">{{ review.version }}</el-tag>
          <el-tag
            size="small"
            :type="review.scan.status === 'passed' ? 'success' : 'warning'"
          >扫描 {{ review.scan.status }}</el-tag>
        </div>
        <p class="sm-muted">
          提交人 {{ review.submitted_by ?? '—' }}
          <template v-if="review.source.repository">
            · {{ review.source.repository }}
          </template>
        </p>
      </el-card>
    </div>
  </section>
</template>
```

- [ ] **Step 4: 写 ReviewDetailPage**

`apps/web/src/pages/ReviewDetailPage.vue`：

```vue
<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, approveVersion, getReviewDetail, rejectVersion } from '../api'
import type { ReviewDetail } from '../types'

const route = useRoute()
const router = useRouter()

const detail = ref<ReviewDetail | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)

async function load() {
  error.value = ''
  try {
    detail.value = await getReviewDetail(String(route.params.skillId), String(route.params.version))
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

async function decide(approve: boolean) {
  if (!detail.value) return
  busy.value = true
  error.value = ''
  try {
    if (approve) {
      await approveVersion(detail.value.item.skill_id, detail.value.item.version)
      notice.value = '已通过，版本转为已发布。'
    } else {
      await rejectVersion(detail.value.item.skill_id, detail.value.item.version)
      notice.value = '已拒绝。'
    }
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <el-button text class="sm-mb-12" @click="router.push('/reviews')">← 返回队列</el-button>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />
    <el-alert
      v-if="notice"
      data-test="notice"
      :title="notice"
      type="success"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />

    <template v-if="detail">
      <h1 class="sm-h1">
        {{ detail.item.skill_name }}
        <el-tag size="small" class="sm-ml-4">{{ detail.item.version }}</el-tag>
      </h1>
      <p class="sm-muted">
        提交人 {{ detail.item.submitted_by ?? '—' }}
        <template v-if="detail.item.source.repository">
          · {{ detail.item.source.repository }}
        </template>
        <template v-if="detail.item.source.ref">
          · {{ detail.item.source.ref }}
        </template>
        <template v-if="detail.item.source.commit">
          · {{ detail.item.source.commit.slice(0, 12) }}
        </template>
      </p>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">扫描结论</h2>
        <p class="sm-muted">状态：{{ detail.item.scan.status }}</p>
        <pre class="sm-pre">{{ detail.item.scan.summary || '无' }}</pre>
        <ul v-if="detail.findings.length" class="sm-list sm-mt-12">
          <li v-for="(finding, index) in detail.findings" :key="index" class="sm-text-sm">
            {{ finding.rule }} · {{ finding.file ?? '—' }}<template v-if="finding.line">:{{ finding.line }}</template>
            — {{ finding.message }}
          </li>
        </ul>
      </el-card>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">
          文件差异
          <el-tag size="small" type="info" class="sm-ml-4">
            {{ detail.diff.base_version ? `对比 ${detail.diff.base_version}` : '首个版本' }}
          </el-tag>
        </h2>
        <el-table :data="detail.diff.files" size="small">
          <el-table-column prop="path" label="文件" min-width="180">
            <template #default="scope">
              <span class="sm-mono">{{ scope.row.path }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="kind" label="变化" width="100" />
          <el-table-column label="行数" min-width="160">
            <template #default="scope">
              <span v-if="scope.row.kind === 'modified'">
                <span v-if="scope.row.truncated" class="sm-muted">差异较大，只报文件级</span>
                <span v-else>+{{ scope.row.added }} / -{{ scope.row.removed }}</span>
              </span>
              <span v-else class="sm-muted">—</span>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!detail.diff.files.length" description="没有文件差异" :image-size="60" />
      </el-card>

      <el-card shadow="never" class="sm-mb-16">
        <h2 class="sm-h2">SKILL.md</h2>
        <pre class="sm-pre" data-test="skill-md">{{ detail.skill_md }}</pre>
      </el-card>

      <div class="sm-search">
        <el-button type="primary" data-test="approve" :disabled="busy" @click="decide(true)">
          通过
        </el-button>
        <el-button type="danger" data-test="reject" :disabled="busy" @click="decide(false)">
          拒绝
        </el-button>
      </div>
    </template>
  </section>
</template>
```

- [ ] **Step 5: 写失败的测试**

`apps/web/tests/reviews.spec.ts`：

```ts
import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import ReviewQueuePage from '../src/pages/ReviewQueuePage.vue'
import ReviewDetailPage from '../src/pages/ReviewDetailPage.vue'

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

const REVIEW = {
  skill_id: 's1', skill_slug: 'runbook', skill_name: 'Runbook',
  version: '1.0.0', channel: 'stable', submitted_by: 'Dev Two',
  submitted_at: '2026-10-20T00:00:00Z',
  scan: { status: 'passed', summary: '未发现问题' },
  source: { provider: 'git', repository: 'https://git.example.com/x.git', ref: 'v1', commit: 'abc123' },
}

describe('ReviewQueuePage', () => {
  it('列出待审版本并展示扫描结论', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([REVIEW])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/reviews', component: ReviewQueuePage }],
    })
    await router.push('/reviews')
    await router.isReady()
    const wrapper = mount(ReviewQueuePage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="review-item"]').text()).toContain('Runbook')
    expect(wrapper.text()).toContain('扫描 passed')
  })

  it('没有待审时给出空态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/reviews', component: ReviewQueuePage }],
    })
    await router.push('/reviews')
    await router.isReady()
    const wrapper = mount(ReviewQueuePage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('没有待审的版本')
  })
})

describe('ReviewDetailPage', () => {
  it('展示正文、扫描结论与文件差异', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      item: REVIEW,
      skill_md: '# Runbook\n\n内容',
      changelog: '',
      findings: [{ rule: 'external_download', file: 'a.sh', line: 3, message: '外部下载' }],
      diff: {
        base_version: '',
        truncated: false,
        files: [{ path: 'SKILL.md', kind: 'added', added: 0, removed: 0, truncated: false }],
      },
    })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/reviews/:skillId/:version', component: ReviewDetailPage }],
    })
    await router.push('/reviews/s1/1.0.0')
    await router.isReady()
    const wrapper = mount(ReviewDetailPage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="skill-md"]').text()).toContain('Runbook')
    expect(wrapper.find('[data-test="diff-row"]').text()).toContain('SKILL.md')
    expect(wrapper.text()).toContain('external_download')
  })
})
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd apps/web && pnpm test && pnpm exec vue-tsc --noEmit
```

- [ ] **Step 7: 提交**

```bash
git add apps/web
git commit -m "feat(web): 审核队列页与审核详情页"
```

---

## Slice 4：CLI

### Task 11: `publish --team/--org`

**Files:**

- Create: `apps/cli/internal/apiclient/org.go`（`OrgMembership`、`Team`、`Teams` 客户端方法）
- Modify: `apps/cli/internal/apiclient/types.go`（`MeResponse.Organizations` 改用具名 `OrgMembership`）
- Modify: `apps/cli/internal/apiclient/publish.go`（`CreateSkillRequest` 加两个 id 字段）
- Create: `apps/cli/internal/cmd/roles.go`（`roleAtLeast`）
- Modify: `apps/cli/internal/cmd/publish.go`（加 `--org` / `--team` flag 与解析）
- Test: `apps/cli/internal/cmd/publish_test.go`

**Interfaces:**

- Consumes: `GET /me`（已返回组织列表，含 id/slug/role）、`GET /organizations/{id}/teams`
- Produces:
  - `apiclient.OrgMembership`：`{ID, Slug, Name, Role}`
  - `apiclient.Team`：`{ID, OrganizationID, Slug, Name, Status}`
  - `apiclient.Client.Teams(ctx, organizationID) ([]Team, error)`
  - `apiclient.CreateSkillRequest` 新增 `OrganizationID *string`（json `organization_id`）、`OwnerTeamID *string`（json `owner_team_id`）
  - `cmd.roleAtLeast(actual, required string) bool`
  - `cmd.findOrganization(orgs []apiclient.OrgMembership, slug string) (*apiclient.OrgMembership, error)`
  - `cmd.findTeam(teams []apiclient.Team, slug string) (*apiclient.Team, error)`
  - `cmd.resolvePublishScope(orgs, teams, orgSlug, teamSlug, visibility) (publishScope, error)`
  - `cmd.publishScope`：`{OrganizationID, TeamID, Visibility string}`

- [ ] **Step 1: 加 org 客户端**

`apps/cli/internal/apiclient/org.go`：

```go
package apiclient

import (
    "context"
    "net/http"
    "net/url"
)

// OrgMembership 是我在一个组织里的身份（来自 GET /me）。
type OrgMembership struct {
    ID   string `json:"id"`
    Slug string `json:"slug"`
    Name string `json:"name"`
    Role string `json:"role"`
}

// Team 是组织下的一个团队。
type Team struct {
    ID             string `json:"id"`
    OrganizationID string `json:"organization_id"`
    Slug           string `json:"slug"`
    Name           string `json:"name"`
    Status         string `json:"status"`
}

// Teams 列组织下的团队。
func (c *Client) Teams(ctx context.Context, organizationID string) ([]Team, error) {
    var out []Team
    return out, c.do(ctx, http.MethodGet,
        "/organizations/"+url.PathEscape(organizationID)+"/teams", nil, &out)
}
```

- [ ] **Step 2: 换掉匿名结构体**

`apps/cli/internal/apiclient/types.go` 的 `MeResponse`：

```go
type MeResponse struct {
    User struct {
        ID          string `json:"id"`
        DisplayName string `json:"display_name"`
        Email       string `json:"email"`
        Status      string `json:"status"`
    } `json:"user"`
    Organizations []OrgMembership `json:"organizations"`
}
```

- [ ] **Step 3: 发布请求加 id 字段**

`apps/cli/internal/apiclient/publish.go` 的 `CreateSkillRequest`：

```go
type CreateSkillRequest struct {
    Slug        string         `json:"slug"`
    Name        string         `json:"name"`
    Description string         `json:"description"`
    Visibility  string         `json:"visibility"`
    Tags        []string       `json:"tags,omitempty"`
    Metadata    map[string]any `json:"metadata,omitempty"`
    // Source 是来源元数据（设计文档 §7.1）；本地直接发布的 Skill 记 local_import。
    Source *SkillSource `json:"source,omitempty"`
    // OrganizationID 与 OwnerTeamID 决定可见性范围（团队可见必须两个都带）。
    OrganizationID *string `json:"organization_id,omitempty"`
    OwnerTeamID    *string `json:"owner_team_id,omitempty"`
}
```

- [ ] **Step 4: 加角色等级判断**

`apps/cli/internal/cmd/roles.go`：

```go
package cmd

// roleRank 是角色等级，索引越小权限越高。
// 顺序与服务端 OrgService.roleAtLeast 一致，别单独改一边。
var roleRank = map[string]int{
    "owner":     0,
    "admin":     1,
    "publisher": 2,
    "reviewer":  3,
    "member":    4,
    "viewer":    5,
}

// roleAtLeast 判断实际角色是否达到要求。
func roleAtLeast(actual, required string) bool {
    have, ok := roleRank[actual]
    if !ok {
        return false
    }
    need, ok := roleRank[required]
    return ok && have <= need
}
```

- [ ] **Step 5: 写失败的测试**

`apps/cli/internal/cmd/publish_test.go`：

```go
package cmd

import (
    "testing"

    "github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

func TestResolvePublishScope(t *testing.T) {
    orgs := []apiclient.OrgMembership{
        {ID: "org-1", Slug: "acme", Name: "Acme", Role: "owner"},
        {ID: "org-2", Slug: "other", Name: "Other", Role: "member"},
    }
    teams := []apiclient.Team{
        {ID: "team-1", OrganizationID: "org-1", Slug: "platform", Name: "Platform"},
    }

    t.Run("不指定组织时只用原可见性", func(t *testing.T) {
        scope, err := resolvePublishScope(orgs, teams, "", "", "public")
        if err != nil {
            t.Fatalf("不该报错: %v", err)
        }
        if scope.Visibility != "public" || scope.OrganizationID != "" {
            t.Fatalf("不该带组织信息: %+v", scope)
        }
    })

    t.Run("只给组织时可见性是 organization", func(t *testing.T) {
        scope, err := resolvePublishScope(orgs, teams, "acme", "", "private")
        if err != nil {
            t.Fatalf("不该报错: %v", err)
        }
        if scope.Visibility != "organization" || scope.OrganizationID != "org-1" {
            t.Fatalf("可见性定错了: %+v", scope)
        }
    })

    t.Run("给了团队时可见性是 team 且带上两个 id", func(t *testing.T) {
        scope, err := resolvePublishScope(orgs, teams, "acme", "platform", "private")
        if err != nil {
            t.Fatalf("不该报错: %v", err)
        }
        if scope.Visibility != "team" || scope.OrganizationID != "org-1" || scope.TeamID != "team-1" {
            t.Fatalf("可见性定错了: %+v", scope)
        }
    })

    t.Run("只给团队没给组织是错", func(t *testing.T) {
        if _, err := resolvePublishScope(orgs, teams, "", "platform", "private"); err == nil {
            t.Fatal("应该报错")
        }
    })

    t.Run("组织不存在时报错并列出可选项", func(t *testing.T) {
        _, err := resolvePublishScope(orgs, teams, "nope", "", "private")
        if err == nil {
            t.Fatal("应该报错")
        }
        if !strings.Contains(err.Error(), "acme") {
            t.Fatalf("错误信息里应列出可见组织: %v", err)
        }
    })

    t.Run("团队不存在时报错", func(t *testing.T) {
        if _, err := resolvePublishScope(orgs, teams, "acme", "nope", "private"); err == nil {
            t.Fatal("应该报错")
        }
    })

    t.Run("角色不够发布时拒绝", func(t *testing.T) {
        if _, err := resolvePublishScope(orgs, teams, "other", "", "private"); err == nil {
            t.Fatal("member 不该能往组织发布")
        }
    })
}

func TestRoleAtLeast(t *testing.T) {
    cases := []struct {
        actual, required string
        want             bool
    }{
        {"owner", "publisher", true},
        {"publisher", "publisher", true},
        {"reviewer", "publisher", false},
        {"member", "member", true},
        {"viewer", "member", false},
        {"", "member", false},
    }
    for _, c := range cases {
        if got := roleAtLeast(c.actual, c.required); got != c.want {
            t.Errorf("roleAtLeast(%q, %q) = %v, 想要 %v", c.actual, c.required, got, c.want)
        }
    }
}
```

import 补 `"strings"`。

- [ ] **Step 6: 跑测试确认失败**

```bash
cd apps/cli && go test ./internal/cmd/ -run "TestResolvePublishScope|TestRoleAtLeast"
```

Expected：编译失败——`resolvePublishScope` / `roleAtLeast` 还不存在。

- [ ] **Step 7: 实现解析**

`apps/cli/internal/cmd/publish.go` 里加：

```go
// publishScope 是发布到团队时要带的组织与团队 id。
type publishScope struct {
    OrganizationID string
    TeamID         string
    Visibility     string
}

// findOrganization 按 slug 找组织。
func findOrganization(orgs []apiclient.OrgMembership, slug string) (*apiclient.OrgMembership, error) {
    for i := range orgs {
        if orgs[i].Slug == slug {
            return &orgs[i], nil
        }
    }
    slugs := make([]string, 0, len(orgs))
    for _, org := range orgs {
        slugs = append(slugs, org.Slug)
    }
    return nil, fmt.Errorf("组织 %q 不存在或你不是成员，可选：%s",
        slug, strings.Join(slugs, " / "))
}

// findTeam 按 slug 找团队。
func findTeam(teams []apiclient.Team, slug string) (*apiclient.Team, error) {
    for i := range teams {
        if teams[i].Slug == slug {
            return &teams[i], nil
        }
    }
    slugs := make([]string, 0, len(teams))
    for _, team := range teams {
        slugs = append(slugs, team.Slug)
    }
    return nil, fmt.Errorf("团队 %q 不存在，可选：%s", slug, strings.Join(slugs, " / "))
}

// resolvePublishScope 把 --org/--team 的 slug 解析成 id，并定出可见性。
//
// 指定团队 → visibility=team（组织与团队 id 都要带）；
// 只指定组织 → visibility=organization；都不指定 → 用原可见性。
func resolvePublishScope(orgs []apiclient.OrgMembership, teams []apiclient.Team,
    orgSlug, teamSlug, visibility string) (publishScope, error) {

    if orgSlug == "" && teamSlug == "" {
        return publishScope{Visibility: visibility}, nil
    }
    if orgSlug == "" {
        return publishScope{}, errors.New("指定 --team 时必须同时指定 --org")
    }
    org, err := findOrganization(orgs, orgSlug)
    if err != nil {
        return publishScope{}, err
    }
    if !roleAtLeast(org.Role, "publisher") {
        return publishScope{}, fmt.Errorf(
            "你在组织 %q 的角色是 %s，发布团队 Skill 需要 publisher 及以上", orgSlug, org.Role)
    }
    scope := publishScope{OrganizationID: org.ID, Visibility: "organization"}
    if teamSlug == "" {
        return scope, nil
    }
    team, err := findTeam(teams, teamSlug)
    if err != nil {
        return publishScope{}, err
    }
    scope.TeamID = team.ID
    scope.Visibility = "team"
    return scope, nil
}

// loadPublishScope 取我可见的组织与团队，再解析成发布范围。
func loadPublishScope(ctx context.Context, client *apiclient.Client,
    orgSlug, teamSlug, visibility string) (publishScope, error) {

    if orgSlug == "" && teamSlug == "" {
        return publishScope{Visibility: visibility}, nil
    }
    me, err := client.Me(ctx)
    if err != nil {
        return publishScope{}, err
    }
    org, err := findOrganization(me.Organizations, orgSlug)
    if err != nil {
        return publishScope{}, err
    }
    teams, err := client.Teams(ctx, org.ID)
    if err != nil {
        return publishScope{}, err
    }
    return resolvePublishScope(me.Organizations, teams, orgSlug, teamSlug, visibility)
}
```

- [ ] **Step 8: 接上 flag**

`publishVisibility` 放开：

```go
// publishVisibility 是发布时允许的可见性。
// team / organization 需要组织与成员概念，v2 起可用。
var publishVisibility = []string{"private", "public", "team", "organization"}
```

`newPublishCmd` 里加变量与 flag：

```go
    var (
        version, visibility, channel, slug, name, description string
        orgSlug, teamSlug                                   string
        all, dryRun, skipSecretScan                         bool
    )
```

```go
    cmd.Flags().StringVar(&orgSlug, "org", "", "发布到的组织（slug）")
    cmd.Flags().StringVar(&teamSlug, "team", "", "发布到的团队（slug），需同时指定 --org")
```

`RunE` 里，在 `newClient()` 之后加：

```go
            scope, err := loadPublishScope(cmd.Context(), client, orgSlug, teamSlug, visibility)
            if err != nil {
                return err
            }
```

并把 `options` 里加 `Scope: scope`。

`publishOptions` 加字段：

```go
type publishOptions struct {
    Version        string
    Visibility     string
    Channel        string
    Slug           string
    Name           string
    Description    string
    DryRun         bool
    SkipSecretScan bool
    Scope          publishScope
}
```

`publishOne` 里 `CreateSkillRequest` 改成：

```go
        skill, err = client.CreateSkill(ctx, apiclient.CreateSkillRequest{
            Slug:           slug,
            Name:           displayName,
            Description:    description,
            Visibility:     options.Scope.Visibility,
            OrganizationID: optionalString(options.Scope.OrganizationID),
            OwnerTeamID:    optionalString(options.Scope.TeamID),
        })
```

加辅助函数：

```go
// optionalString 空串转 nil——omitempty 才知道不传。
func optionalString(value string) *string {
    if value == "" {
        return nil
    }
    return &value
}
```

**注意**：`options.Visibility` 在 `publishOptions` 里保留但发布时用 `options.Scope.Visibility`——`Scope.Visibility` 在不指定组织时就是传入的 visibility，所以行为一致。

- [ ] **Step 9: 跑测试确认通过**

```bash
cd apps/cli && go test ./...
```

Expected：全绿。

- [ ] **Step 10: 提交**

```bash
git add apps/cli
git commit -m "feat(cli): publish 支持 --team/--org，发到团队可见的版本"
```

---

## Slice 5：验收与文档

### Task 12: 第二个账号与端到端验收

**Files:**

- Modify: `infra/keycloak/realm-skillmesh.json`（加 `dev-user2`）
- Create: `infra/scripts/add-second-user.sh`（realm 导入不覆盖已存在用户，用 kcadm 建）
- Create: `docs/superpowers/plans/2026-10-20-v2-team-console-checklist.md`（验收清单与实际结果）

**Interfaces:**

- Consumes: 全部前序任务
- Produces: 一个能在本机跑通的双人验收环境

- [ ] **Step 1: realm 里加第二个用户**

`infra/keycloak/realm-skillmesh.json` 的 `users` 数组里加：

```json
    {
      "username": "dev-user2",
      "enabled": true,
      "emailVerified": true,
      "email": "dev2@skillmesh.local",
      "firstName": "Dev",
      "lastName": "User Two",
      "credentials": [{ "type": "password", "value": "dev2", "temporary": false }],
      "realmRoles": ["skillmesh-user"]
    }
```

- [ ] **Step 2: 写补建用户的脚本**

Keycloak 导入**不覆盖**已存在的用户，所以 realm 改了也要能补建。`infra/scripts/add-second-user.sh`：

```bash
#!/usr/bin/env bash
# 补建第二个测试用户 dev-user2。
#
# Keycloak 的 realm 导入不覆盖已存在的用户——改了 realm-skillmesh.json
# 之后，已经跑过的本机实例里还是没有这个用户。这个脚本用 kcadm 直接建。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KC="$ROOT/infra/bin/keycloak-26.8.1"
ADMIN_USER="${SM_KEYCLOAK_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${SM_KEYCLOAK_ADMIN_PASSWORD:-admin}"
ISSUER="http://127.0.0.1:8180/kc"

if [ ! -x "$KC/bin/kcadm.sh" ] && [ ! -f "$KC/bin/kcadm.sh" ]; then
  echo "找不到 kcadm.sh，先跑 fetch-keycloak.sh" >&2
  exit 1
fi

"$KC/bin/kcadm.sh" config credentials --server "$ISSUER" --realm master \
  --user "$ADMIN_USER" --password "$ADMIN_PASSWORD" >/dev/null

if "$KC/bin/kcadm.sh" get users -r skillmesh -q username=dev-user2 \
    --fields username --format csv 2>/dev/null | grep -q dev-user2; then
  echo "dev-user2 已存在"
  exit 0
fi

USER_ID=$("$KC/bin/kcadm.sh" create users -r skillmesh \
  -s username=dev-user2 -s enabled=true -s email=dev2@skillmesh.local \
  -s emailVerified=true -s firstName=Dev -s lastName="User Two" -i)
"$KC/bin/kcadm.sh" set-password -r skillmesh --userid "$USER_ID" \
  --new-password dev2 --temporary=false >/dev/null
"$KC/bin/kcadm.sh" add-roles -r skillmesh --uusername dev-user2 \
  --rolename skillmesh-user >/dev/null
echo "dev-user2 已创建（密码 dev2）"
```

- [ ] **Step 3: 起服务并补建用户**

```bash
bash infra/scripts/start-minio.sh
bash infra/scripts/start-keycloak.sh
bash infra/scripts/add-second-user.sh
bash infra/scripts/smoke.sh
```

Expected：smoke 四项全 OK；`add-second-user.sh` 输出「dev-user2 已创建（密码 dev2）」或「dev-user2 已存在」。

- [ ] **Step 4: 起 API 与前端**

```bash
cd apps/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local &
cd apps/web && pnpm install && pnpm dev
```

- [ ] **Step 5: 走一遍闭环**

按顺序做，每步记下实际结果：

| # | 操作 | 期望 |
| --- | --- | --- |
| 1 | 浏览器打开 `http://127.0.0.1:5174/login`，用 dev-user / dev 登录 | 顶栏显示 Dev User |
| 2 | CLI 建组织与团队：`skillmesh` 目前没有建组织的命令——用 API 建，见下方 curl | 组织 acme、团队 platform 建好 |
| 3 | 团队页生成邀请链接 | 拿到 `http://127.0.0.1:5174/invite/<token>` |
| 4 | 无痕窗口打开邀请链接，用 dev-user2 / dev2 登录并接受 | 显示「已加入」 |
| 5 | 团队页把 dev-user2 的组织角色改成 publisher | 成员表角色列显示 publisher |
| 6 | dev-user2 用 CLI 发布到团队：`skillmesh publish ./skills/java-debug --org acme --team platform` | 输出「已提交审核」 |
| 7 | dev-user 打开 `/reviews` | 能看到待审版本，带扫描结论与来源 |
| 8 | 打开详情 | 有 SKILL.md 正文与文件级差异 |
| 9 | 点「通过」 | 提示已通过 |
| 10 | dev-user2 `skillmesh search java-debug` | 能搜到；输出最后一列是 Skill 的完整 ID |
| 11 | dev-user2 `skillmesh install <上一步的 ID>` | 装到 Agent 目录 |
| 12 | 负例：dev-user2 打开 `/reviews` | 看不到（他不是 reviewer） |
| 13 | 负例：把一个过期邀请的链接打开 | 页面提示已过期，接受返回 409 `INVITATION_INVALID` |
| 14 | 负例：组织 owner 尝试把自己降级 | 400 `VALIDATION_FAILED` |

第 5 步不能省：被邀请进来的人默认是组织 `member`，而 CLI 发布到团队校验的是**组织角色**要有 publisher 及以上（`/me` 只返回组织角色）。所以要么在邀请时就把组织角色选成 publisher，要么事后在成员表里改——两种都走团队页。

`install` 收的是 Skill **ID** 不是 slug（`install.go` 的 `Use: "install <skill-id>"`），`search` 输出的最后一列就是它。

第 2 步的建组织用 curl（CLI 还没有建组织命令，v2 不加）：

```bash
TOKEN=$(curl -s -X POST "http://127.0.0.1:8180/kc/realms/skillmesh/protocol/openid-connect/token" \
  -d "grant_type=password" -d "client_id=skillmesh-test" \
  -d "username=dev-user" -d "password=dev" \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
ORG=$(curl -s -X POST http://127.0.0.1:8080/api/v1/organizations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"slug":"acme","name":"Acme"}' | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST "http://127.0.0.1:8080/api/v1/organizations/$ORG/teams" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"slug":"platform","name":"Platform"}'
```

- [ ] **Step 6: 把结果写进验收清单**

`docs/superpowers/plans/2026-10-20-v2-team-console-checklist.md` 记下每一步的实际结果与踩到的坑。失败的项在这里写明原因，不要改测试来迁就。

- [ ] **Step 7: 提交**

```bash
git add infra/keycloak/realm-skillmesh.json infra/scripts/add-second-user.sh docs/superpowers/plans/2026-10-20-v2-team-console-checklist.md
git commit -m "test: 第二个账号与 v2 闭环验收"
```

---

### Task 13: 团队控制台使用手册

**Files:**

- Create: `docs/团队控制台.md`
- Modify: `README.md`

**Interfaces:**

- Consumes: 全部前序任务
- Produces: 用户能照着做的操作手册

- [ ] **Step 1: 写手册**

`docs/团队控制台.md`：

```markdown
# 团队控制台使用手册

> 面向第二个人加入团队之后的日常操作：登录、邀请、发布到团队、审核、安装。

## 1. 登录

浏览器打开控制台首页，点「登录」，跳到 Keycloak 完成认证后回到控制台。

- 令牌存在浏览器 localStorage 里，约 30 分钟自动续期（Keycloak realm 的 `accessTokenLifespan: 1800`）；过期了会自动跳登录页。
- 登出在右上角。

## 2. 建组织与团队

组织是顶层空间，团队是组织下面的一组人。Skill 的可见性有四档：

| 可见性 | 谁能看 |
| --- | --- |
| private | 只有属主 |
| team | 该团队的成员 |
| organization | 该组织的成员 |
| public | 所有人 |

建组织和建团队目前用 API（CLI 还没有这两个命令）：

```bash
TOKEN=$(curl -s -X POST "http://127.0.0.1:8180/kc/realms/skillmesh/protocol/openid-connect/token" \
  -d "grant_type=password" -d "client_id=skillmesh-test" \
  -d "username=dev-user" -d "password=dev" \
  | python -c "import sys,json;print(json.load(sys.stdin)['access_token'])")
ORG=$(curl -s -X POST http://127.0.0.1:8080/api/v1/organizations \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"slug":"acme","name":"Acme"}' | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
curl -s -X POST "http://127.0.0.1:8080/api/v1/organizations/$ORG/teams" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"slug":"platform","name":"Platform"}'
```

## 3. 邀请成员

团队页 → 「邀请」区 → 选团队和角色 → 点「生成邀请链接」。

- 链接 7 天内有效，库里只存哈希，明文只显示一次。
- 把链接发给对方；对方打开后登录并点「接受邀请」即入组。
- 发出后还能在列表里撤销。

## 4. 发布到团队

```bash
skillmesh publish ./skills/java-debug --org acme --team platform
```

- `--org` / `--team` 传的是 **slug**，不是 id。
- 给了 `--team` 时可见性是 team，只给 `--org` 时是 organization。
- 你在组织里需要 publisher 及以上角色才能发。
- 团队与组织可见的版本**不会直接发布**，会停在待审核状态。

## 5. 审核

「审核」页列出待审版本。点进去能看到：

- 扫描结论与服务端扫描命中的规则
- 来源（仓库、分支、commit）
- 与上一个已发布版本的文件级差异（新增/修改/删除 + 增删行数）
- SKILL.md 正文

差异大到只报文件级时，页面会明说「差异较大，只报文件级」。

reviewer 及以上角色才能看到并处理自己范围内的待审版本。

## 6. 安装

审核通过后，团队成员就能装：

```bash
skillmesh search java-debug
skillmesh install java-debug
```

团队可见的 Skill 只有团队成员能搜到并下载。

## 7. 排错

| 现象 | 原因 |
| --- | --- |
| 登录页报「登录状态校验失败」 | state 对不上，重新点一次登录 |
| 邀请页显示已失效 | 超过 7 天或已被撤销，重新生成 |
| 看不到待审版本 | 你在该团队/组织里不是 reviewer 及以上 |
| CLI 报「组织 xxx 不存在或你不是成员」 | `--org` 传的是 slug；用 `skillmesh` 登录的账号不在这个组织里 |
| CLI 报「角色是 member，发布团队 Skill 需要 publisher 及以上」 | 让组织管理员把你的角色提到 publisher |
```

- [ ] **Step 2: README 加一行**

`README.md` 的文档表格里加一行：

```markdown
| [团队控制台使用手册](docs/团队控制台.md) | 浏览器登录、邀请入组、发布到团队、审核放行、安装与排错 |
```

- [ ] **Step 3: 提交**

```bash
git add docs/团队控制台.md README.md
git commit -m "docs: 团队控制台使用手册"
```

---

## 完成判据

全部 13 个任务的测试绿、两个端到端验收清单打完，就绪。

| 层 | 命令 | 期望 |
| --- | --- | --- |
| API | `cd apps/api && ./mvnw test` | 全绿（含新增的邀请/成员/审核/diff 用例） |
| CLI | `cd apps/cli && go test ./...` | 全绿 |
| Web | `cd apps/web && pnpm test && pnpm exec vue-tsc --noEmit` | 全绿，类型无错 |
| 端到端 | [验收清单](2026-10-20-v2-team-console-checklist.md) | 11 步全过 |

---

---

---
