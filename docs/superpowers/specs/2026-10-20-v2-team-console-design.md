# SkillMesh v2：团队控制台（最小治理闭环）

> 日期：2026-10-20 ｜ 状态：已实现（验收记录见 [闭环验收清单](../plans/2026-10-20-v2-team-console-checklist.md)） ｜ 上游：[设计文档](../../设计文档.md) V1.0 §10 §15、[v1.1 需求](2026-10-19-mvp-v1-1-requirements.md)、[实现对照表](2026-10-19-design-doc-coverage.md)

## 1. 这一版要解决的

v1/v1.1 已把设计文档 §1–§15 的条目全部落地（[对照表](2026-10-19-design-doc-coverage.md)），但「团队共享与治理」这条主链路仍然只能靠 CLI 单人操作跑通：

- Web 端只有 2 页只读页面，**没有登录入口**——token 靠手贴。
- 没有任何途径把第二个人拉进团队：`organizations` / `teams` / `memberships` 三张表都在，但**没有邀请**。
- CLI `publish --visibility` 只认 `private|public`，**发不出团队可见的版本**，审核流因此永远没有输入。
- 审核只有 `submit` / `approve` / `reject` 三个动作端点，**没有"待办列表"**，人得知道版本号才能去审。

v2 的目标不是加功能面，而是把「两个人用 SkillMesh 协作」这条路走通一次。

## 2. 非目标

| 不做 | 原因 |
| --- | --- |
| 审计台 | 后端查询已改成一条 join（不再全表扫描），但**审计台页面**仍不做，与本闭环无关，下一轮处理 |
| 成员自助注册 | 成员只能被邀请进来，不存在"注册即入组" |
| SSO / LDAP | 现有 Keycloak OIDC 够用，企业身份源是另一个方向 |
| MCP 工具服务、桌面端、更多 Agent 适配器 | 属设计文档 §15 阶段 3，与本闭环无依赖 |
| 审核页的版本间"行级内容 diff" | 只给增删行数，不给具体行内容（见 §6.3） |
| 多设备冲突可视化 | 仍只在 CLI 侧处理 |

## 3. 用户旅程（v2 的定义性闭环）

```text
A 登录 Web → A 建组织+团队 → A 生成邀请链接
  → B 打开链接，看到「acme / platform，角色 member」→ B 登录 → 接受邀请
  → B 用 CLI publish --team platform 发布 Skill → 版本停在 pending_review
  → A 在审核队列看到待审版本（SKILL.md 正文 + 扫描结论 + 来源 + 文件级 diff）
  → A 批准 → 版本转 published
  → B 用 CLI search 搜到 → install 装到 Agent 原生目录
```

闭环的每一步都对应下面 §4–§8 的具体改动。**B 必须是一个真实存在的第二个 OIDC 账号**——见 §9 的验收前提。

## 4. 数据模型

### 4.1 新表 `org_invitations`

V3 迁移（`db/migrations/V3__team_console.sql`，同时复制到 `apps/api/src/main/resources/db/migrations/`）：

```sql
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

> V3 里的 `org_role` CHECK 抄的是**组织角色全集**（含 `owner`），不是**邀请可授角色集**——
> 与下面「`owner` 不能通过邀请拿到」矛盾。V5 把这个约束收紧了（见下）。

之后三条迁移（同样两份文件逐字相同）：V4、V5，以及 V6（`V6__version_submitted_at.sql`，给 `skill_versions` 加 `submitted_at` 列——审核队列的送审时间与排序都建在这一列上，§5.3 的 `submitted_at` 字段就来自它）：

**V4**（`V4__invitation_list_index.sql`）把邀请列表的索引改成与查询对上：
唯一按组织查邀请的查询是 `findByOrganizationIdOrderByCreatedAtDesc`——只按 `organization_id` 过滤、
按 `created_at DESC` 排序，**从不过滤 `status`**。所以索引改成 `(organization_id, created_at DESC)`：
第二列 `status` 对那条查询没有用处，而排序键 `created_at` 原本也没被索引覆盖。

**V5**（`V5__invitation_org_role_check.sql`）把 `org_role` 的 CHECK 收紧到**邀请能授**的那几档：

```sql
DELETE FROM org_invitations WHERE org_role = 'owner';

ALTER TABLE org_invitations DROP CONSTRAINT org_invitations_org_role_check;
ALTER TABLE org_invitations ADD CONSTRAINT org_invitations_org_role_check
    CHECK (org_role IN ('admin', 'publisher', 'reviewer', 'member', 'viewer'));
```

应用层本来就有三道闸（DTO `@Pattern`、服务层 `Roles.INVITABLE_ORG_ROLES`、接受时
`requireOwnerToGrantOwner`），所以这条不是提权洞——是**让数据库也承载这条不变量**：
一条本该永远不可能存在的邀请不该能静悄悄躺在表里。

字段说明：

- `token_hash` 是原始 token 的 SHA-256 十六进制。**明文 token 只在创建响应里出现一次**，库里不留。
- `subject` 可选，填了就只有该 OIDC subject 能接受（一人一链接）；不填则任何人可接受。
- `expires_at` 默认创建后 7 天。过期不改库状态，读取时按 `expires_at < now()` 判为 `expired`（避免定时任务）。
- 无 `email` 字段：身份以 OIDC subject 为准，邮箱只是展示信息，不做可靠投递。

### 4.2 不改的表

`organizations` / `organization_memberships` / `teams` / `team_memberships` / `skills` / `skill_versions` / `skill_scans` 结构不变。

## 5. API 契约

新增端点全部挂在 `/api/v1` 下。JSON 字段 `snake_case`，错误沿用 problem+json，客户端只按 `code` 分支。

### 5.1 邀请

| 方法与路径 | 权限 | 用途 |
| --- | --- | --- |
| `POST /organizations/{organizationId}/invitations` | org `owner`/`admin` | 创建邀请，返回一次性链接 |
| `GET /organizations/{organizationId}/invitations` | org 成员 | 列该组织的邀请 |
| `DELETE /organizations/{organizationId}/invitations/{invitationId}` | org `owner`/`admin` | 撤销（置 `revoked`） |
| `GET /invitations/{token}` | **公开** | 看邀请摘要 |
| `POST /invitations/{token}/accept` | 需登录 | 接受邀请，落成员关系 |

创建请求：

```json
{"team_id": "<uuid>", "org_role": "member", "team_role": "member", "subject": null}
```

角色分两层：组织角色有 `owner` 一档，团队角色没有（`admin` / `publisher` / `reviewer` / `member` / `viewer`）。`org_role` 只能填 `admin` 及以下——`owner` **不能通过邀请拿到**，只能由现任 owner 直接任命（`Roles.INVITABLE_ORG_ROLE_PATTERN`）。

创建响应（**明文 token 只在这里出现**）：

```json
{
  "id": "<uuid>",
  "organization_slug": "acme",
  "organization_name": "Acme",
  "team_slug": "platform",
  "team_name": "Platform",
  "org_role": "member",
  "team_role": "member",
  "expires_at": "2026-10-27T10:00:00Z",
  "invite_url": "http://127.0.0.1:5174/invite/<raw-token>",
  "token": "<raw-token>"
}
```

字段是**扁平的**（`organization_slug` / `organization_name` / `team_slug` / `team_name`），不是嵌套对象——前端只展示名字与 slug，不需要组织/团队的 id。`team_slug` / `team_name` 在组织级邀请（没指定团队）时为 `null`。

`invite_url` 的 base 由服务端配置 `skillmesh.web.base-url` 拼；本地默认 `http://127.0.0.1:5174`。Web 端同时展示原始 token 文本，便于复制。

`GET /invitations/{token}`（公开）只回**非敏感**摘要，用于未登录时展示：

```json
{"id": "<uuid>", "organization_name": "Acme", "team_name": "Platform",
 "org_role": "member", "team_role": "member",
 "expires_at": "2026-10-27T10:00:00Z", "created_at": "2026-10-20T10:00:00Z",
 "status": "pending"}
```

- 令牌不存在 / 已撤销 → 404 `NOT_FOUND`（不区分原因，避免探测）。
- 令牌存在但**已过期** → 仍返回 200，`status` 字段为 `expired`，页面提示已过期。
  过期是按 `expires_at < now()` 算的，不改库里的 `status`（`InvitationService.effectiveStatus`）。

`POST /invitations/{token}/accept`：

- 幂等：已是组织成员 → 200 直接返回当前角色（重复点链接不报错）。
- 落位：写 `organization_memberships`（`org_role`）+ `team_memberships`（`team_role`，`team_id` 非空时）。
- 目标用户已存在同记录 → 更新角色，不报冲突。
- 接受后置 `status='accepted'`、`accepted_by`、`accepted_at`，写审计 `invitation.accept`。
- 过期或撤销 → 409 `INVITATION_INVALID`。

新增错误码 `INVITATION_INVALID`（加入 `ErrorCodes`）。邀请相关的 404 一律用既有的 `NOT_FOUND`，不新增。

### 5.2 成员管理

| 方法与路径 | 权限 | 用途 |
| --- | --- | --- |
| `GET /organizations/{organizationId}/members` | org 成员 | 组织成员列表（含角色） |
| `GET /organizations/{organizationId}/teams/{teamId}/members` | 团队成员 | 团队成员列表 |
| `PATCH /organizations/{organizationId}/members/{userId}` | org `owner`/`admin` | 改组织角色 |
| `PATCH /organizations/{organizationId}/teams/{teamId}/members/{userId}` | org `owner`/`admin` 或 team `admin` | 改团队角色 |
| `DELETE /organizations/{organizationId}/teams/{teamId}/members/{userId}` | 同上 | 移出团队 |

成员响应：

```json
{"user_id": "<uuid>", "display_name": "Dev Two", "email": "dev2@skillmesh.local",
 "role": "member", "joined_at": "2026-10-20T10:00:00Z"}
```

`role` 是**哪一层的角色取决于查询的是哪张表**：查组织成员是组织角色，查团队成员是团队角色。两层的词表不同——组织有 `owner` 一档，团队没有（`Roles.ORG_RANK` / `Roles.TEAM_RANK`）。

保护规则：

- 改/删自己的组织角色 → 400（避免把自己降权后无法管理）。
- 唯一 `owner` 不允许被降级或移除 → 400。
- 授予或撤销 `owner` 身份只能由现任 owner 动手。
- 移出团队不撤销其组织成员身份（两套身份独立）。

### 5.3 审核队列

| 方法与路径 | 权限 | 用途 |
| --- | --- | --- |
| `GET /reviews?status=pending_review&limit=` | reviewer+ | 待审版本列表 |
| `GET /reviews/{skillId}/{version}` | reviewer+ | 审核详情（含 diff） |

列表项：

```json
{
  "skill_id": "<uuid>", "skill_slug": "java-debug", "skill_name": "Java Debug",
  "version": "1.0.0", "channel": "stable",
  "submitted_by": "Dev Two",
  "submitted_at": "2026-10-20T10:00:00Z",
  "status": "pending_review",
  "scan": {"status": "passed", "summary": "3 findings, 0 blocking"},
  "source": {"provider": "git", "repository": "https://github.com/owner/skills.git",
             "ref": "v1.2.0", "commit": "abc1234"}
}
```

`submitted_by` 是提交人的**显示名**（不是对象）——审核页要的是「谁提交的」这个名字。

`status` 是**版本自己的状态**（`draft` / `scanning` / `pending_review` / `published` / `rejected`），不是队列的筛选条件。详情端点要靠它判断「这一版还能不能过审」——用 URL 直接打开一个已发布版本的详情页时，服务端只校验「我能审」，不校验是否还在待审，没有状态字段前端就会给出一个必然 409 的「通过」按钮。

**可见范围**：只返回「我在其所属范围里是 reviewer 及以上」的待审版本。

取角色是**团队优先、没有才回落组织**（`coalesce(团队角色, 组织角色)`，与 `OrgService.roleIn` 同一套语义）：

- 技能的 `owner_team_id` 非空且我在该团队有成员行 → 用我在**该团队**的角色。
- 我在该团队**没有**成员行 → 回落到我在该组织的角色。
- `organization_id` 为空（个人 Skill）→ 不进审核流（个人与公开可见的版本扫描通过即发布，见 `ReviewService.needsReview`）。

「团队优先」不是二选一：建团队时不写 `team_memberships` 行，所以组织 owner 在该团队没有团队角色，会回落到组织角色——按互斥二选一理解会以为 owner 看不到队列。

排序按 `(submitted_at DESC, id DESC)`。

v2 **不做游标分页**——队列只有 `limit`（上限 200），待审量在团队规模下足够小。
要翻页时再按现有做法加：base64 编码的 `submitted_at|version_id` 游标。

详情把列表项**整个放进 `item`**，再挂上详情独有的字段（不是与列表项拍平成一层——
前端按 `detail.item.status` 判断「这一版还能不能过审」，平铺会与 `item` 混在一起）：

```json
{
  "item": {"skill_id": "<uuid>", "skill_slug": "java-debug", "version": "1.0.0",
           "status": "pending_review", "submitted_by": "Dev Two", "...": "列表项原样"},
  "skill_md": "---\nname: Java Debug\n---\n...",
  "changelog": "...",
  "findings": [{"rule": "external_download",
                "path": "scripts/fetch.sh", "line": 12, "detail": "..."}],
  "diff": {
    "base_version": "0.9.0",
    "truncated": false,
    "files": [
      {"path": "SKILL.md", "kind": "modified", "added": 8, "removed": 2},
      {"path": "references/setup.md", "kind": "added"},
      {"path": "old.sh", "kind": "removed"}
    ]
  }
}
```

`kind` ∈ `added` / `modified` / `removed`。`added`/`removed` 是行数，只对 `modified` 存在。

### 5.4 安全配置

`SecurityConfig` 放行一条：

```java
.requestMatchers(HttpMethod.GET, "/api/v1/invitations/*").permitAll()
```

`/api/v1/invitations/*` 不匹配 `/api/v1/invitations/{token}/accept`（Spring 的 `*` 不跨 `/`），所以接受端点仍需 JWT。

CSRF 仍是 `disable`（纯 Bearer token 认证，无 Cookie 会话）。

## 6. 文件级 diff：口径与实现

审核者要判断「这一版改了什么」，需要与 CLI 的 `update --dry-run` **口径一致**——否则同一份 Skill 在两处看到的行数不同，会失去信任。

### 6.1 基线

待审版本 vs **同一 Skill 最近的 `published` 版本**。该 Skill 还没有 published 版本时，基线为空 → 全部文件记为 `added`。

### 6.2 文件级：比 manifest，不解包

`artifacts.manifest_json` 与 `skill_versions.manifest_json` 都存着逐文件清单：

```json
[{"path": "SKILL.md", "sha256": "...", "size_bytes": 812, "executable": false}]
```

所以文件级差异 = 两个 manifest 的集合运算：

- 新版有、旧版无 → `added`
- 两边都有但 sha256 不同 → `modified`
- 旧版有、新版无 → `removed`

**纯 JSON 操作，不解包制品。**

### 6.3 行级：只解包被改动的文件对

只对 `modified` 的文件做行数统计：从两个 zip 里各读出该 entry 的字节。

**字节要无损解码，不能用 UTF-8。** CLI 的 LCS 比较的是**原始字节**；若用 UTF-8 解码，不同的非法字节序列会一起折成同一个替换字符 `U+FFFD`，两份不同的文件会算出「零差异」——差异被静默吞掉。用 ISO-8859-1（Latin-1）解码，它是字节↔字符的一一映射，字符相等等价于字节相等。`\n`（0x0A）在 UTF-8 里也不会出现在多字节序列内部，切行语义不受影响。

口径与 CLI（`apps/cli/internal/cmd/diff.go`）完全一致：

| 约束 | 值 | 行为 |
| --- | --- | --- |
| 行级摘要上限 | 200 行 | 超过则标 `truncated`，**但仍然给出增删行数** |
| LCS DP 表上限 | 4,000,000 格 | 超过则退化为只报文件级，不给行数 |
| 二进制文件 | 前 8192 字节含 NUL | 只报文件级，不做行统计 |

LCS 长度 C → `added = 新版行数 - C`，`removed = 旧版行数 - C`。

「算不动」与「没差异」必须能区分：前者是不可计算，后者是 0。调用方不能把「表太大」读成「文件没改」。

### 6.4 读制品的方式

**流式读 entry，不落盘**：用 `ZipInputStream` 遍历 zip，只把需要的那几个 entry 的字节读进内存。因为不写文件系统，zip slip（entry 名带 `../` 写出目录外）在这条路径上不成立——不落盘就没有目录可逃逸。

单次读取的 entry 大小上限 2MB，超过就只报文件级。

### 6.5 不做缓存

审核是低频操作，重复解包的代价可以接受。同一对版本被多人查看时各算一次。

## 7. Web 前端

技术栈保持 Vue 3 + Vite + TypeScript。**新增依赖只有 `vue-router`**，不引 Pinia（会话状态用一个 `reactive` 单例即可）。

### 7.1 页面与路由

| 路由 | 页面 | 认证 |
| --- | --- | --- |
| `/login` | 登录页 | 公开 |
| `/invite/:token` | 邀请接受页 | 公开（接受动作需登录） |
| `/` | 市场（搜索 + 详情，现状改造） | 需登录 |
| `/team` | 团队与成员管理 | 需登录 |
| `/reviews` | 审核队列 | 需登录 |
| `/reviews/:skillId/:version` | 审核详情 | 需登录 |

现状 `App.vue` 是「token 输入框 + 搜索/详情切换」，改为：`vue-router` 路由 + 顶部导航条（当前用户、组织切换、登出）+ 未登录跳 `/login`。

**删掉手贴 token 的输入框**——新登录页取代它，不保留兼容层。

### 7.2 鉴权会话

OIDC 授权码 + PKCE，复用现有 Keycloak client `skillmesh-cli`（public + PKCE S256）。**client 的 `redirectUris` 与 `webOrigins` 必须显式列出端口**——Keycloak 不认 `http://127.0.0.1:*` 这种「带端口的通配」（只认整段 `*` 或子域 `+`），配错会让 token 端点对每个请求回 `403 Invalid origin`，浏览器永远换不到令牌，登录页只显示「TypeError: Failed to fetch」。当前配置：`redirectUris` = `http://127.0.0.1:5174/*`（Web）、`http://127.0.0.1:53682/*`（CLI 浏览器回调）、`http://localhost:5174/*`；`webOrigins` 去掉路径。realm 只在验收时加第二个用户（§9）。

PKCE **手写，不引 `oidc-client-ts`**：

| 文件 | 内容 |
| --- | --- |
| `src/auth/pkce.ts` | `randomVerifier()`（CSPRNG 32 字节 → base64url）、`codeChallenge(verifier)`（Web Crypto SHA-256 → base64url）、`exchangeCode(code, verifier, redirectUri)`（POST token 端点）、`buildAuthorizeUrl()`、`refreshToken()` |
| `src/auth/session.ts` | `reactive` 单例存 access/refresh token 与当前用户；`login()` / `logout()` / `ensureFresh()` |

用 Web Crypto API 原生能力，约 80 行。理由：`oidc-client-ts` 的 silent-renew 走 hidden iframe，在本地 Keycloak + 5174 端口下容易出意外；手写版本没有隐式定时器，行为可预测。

Token 存储：`localStorage` 存 access + refresh token。access token 一年过期（Keycloak realm 的 `accessTokenLifespan: 31536000`，`ssoSessionIdleTimeout` 同值——refresh token 跟着它活），调用前由 `ensureFresh()` 用 refresh token 静默续期；refresh 失败 → 清空会话跳登录页。**取舍**：localStorage 有 XSS 面，这是内部管理工具的常规取舍，spec 里记明。令牌有效期这么长是因为本机 Keycloak 是开发环境（`sslRequired: none`）——换到真部署要按安全要求调短。

### 7.3 状态

不引 Pinia。会话状态在 `session.ts` 的 `reactive` 单例里；页面内需要局部状态就用组件自己的 `ref`。组织列表来自 `/me`（已含 `id`/`slug`/`role`）。

### 7.4 样式

用 **Element Plus**（Vue 3 组件库），不引 Tailwind/UnoCSS。

- 表格、表单、按钮、徽标、空态、告警条一律用 `el-*` 组件，不自己撸。
- 自有 CSS 只留布局与少量工具类（顶栏、内容区、间距），放在 `apps/web/src/styles/app.css` 一个文件里；不建多文件 CSS 库。
- 需要覆盖组件库样式时，用 Element Plus 的 CSS 变量改主题，不写 `!important` 硬改。

## 8. CLI 改动

只有一处：`publish` 支持发布到团队。

```bash
skillmesh publish ./skills/java-debug --team platform --org acme
```

- `--org` / `--team` 取**slug**，不是 id。
- 解析路径：`GET /me` 拿组织列表（已含 `id`/`slug`/`role`）→ 按 `--org` 匹配得 `organization_id`；再 `GET /organizations/{id}/teams` 按 `--team` 匹配得 `owner_team_id`。**服务端不需要新增端点。**
- 解析不到 → 报错并列出我可见的组织/团队。
- 可见性放宽：`cmd/publish.go` 的 `publishVisibility` 白名单从 `private|public` 放开到加 `team|organization`；`apiclient.CreateSkillRequest` 加 `organization_id` / `owner_team_id` 两个字段。
- 随发布请求带上 `organization_id` / `owner_team_id` / `visibility=team`（`--team` 缺省时按 `--org` 推 `organization` 可见性）。服务端 `SkillService.create` 已校验：`team` 可见必须同时给 `organization_id` 与 `owner_team_id`。
- 发布者在该组织/团队里需 `publisher` 及以上，否则拒绝。

`publish` 之外的命令**零改动**。安装侧（`install`）收的是 **Skill ID 而不是 slug**（`install <skill-id>`），团队可见的 Skill 同样按 ID 装——`skillmesh search` 输出的最后一列就是完整 ID，闭环走得通。让 `install` 也吃 slug 是 v1 的可用性缺口，不在 v2 范围（见 §11）。

## 9. 实施切片

| 切片 | 内容 | 交付判据 |
| --- | --- | --- |
| 1. 服务端 | V3 迁移 + 邀请服务与端点 + 成员读改删 + 审核队列 + diff | Java 单测绿（邀请生命周期、成员权限、审核可见性、diff 口径） |
| 2. Web 基建 | vue-router + 会话 + 登录页 + 布局 + CSS 库 | 能用两个 Keycloak 账号在浏览器登录并互相看到对方 |
| 3. Web 业务页 | 团队页 + 邀请页 + 审核队列页与详情页 | 浏览器里走完 §3 的前三步与审核步 |
| 4. CLI | `publish --team/--org` | Go 单测：slug 解析、权限拒绝、请求体带 id |
| 5. 验收与文档 | 双账号端到端 + 手册 | §10 清单全过；新增《团队控制台使用手册》 |

每个切片内部按 TDD：先写会失败的用例，再写实现。

### 验收前提

Keycloak realm 需要**第二个账号**。在 `infra/keycloak/realm-skillmesh.json` 里加 `dev-user2`（密码 `dev2`，同 `skillmesh-user` 角色）。

注意 Keycloak 导入**不覆盖**已存在的用户。要让新用户生效，需要清掉 `infra/bin/keycloak-26.8.1/data` 后重启，或用 `kcadm.sh` 单独建用户。启动脚本 `start-keycloak.sh` 不动，重置步骤写进手册。

## 10. 验收清单

| # | 用例 | 期望 |
| --- | --- | --- |
| 1 | A 登录 Web | 进市场页，无手贴 token 框 |
| 2 | A 建组织 `acme` + 团队 `platform` | 团队页可见；`/me` 含该组织，角色 `owner` |
| 3 | A 生成邀请 | 拿到链接与 token；库里只有 SHA-256 |
| 4 | B 打开链接 | 显示「Acme / Platform，角色 member」 |
| 5 | B 登录并接受 | `organization_memberships` + `team_memberships` 各一条；重复接受不报错 |
| 6 | B 发布到团队 | 版本 `status=pending_review`，不进 `published` |
| 7 | A 看审核队列 | 能看到该版本，含扫描结论、来源、diff |
| 8 | A 批准 | 转 `published`，写审计 |
| 9 | B 搜到并安装 | `search` 能搜到（团队可见性过滤生效），`install` 装进 Agent 目录 |
| 10 | 负例：B（非 reviewer）打开审核队列 | 看不到 A 组织的待审项 |
| 11 | 负例：C 无权限访问成员列表 | 403 / 404 |
| 12 | 负例：过期邀请链接 | 页面提示已过期，接受返回 409 |
| 13 | 负例：唯一 owner 尝试降级自己 | 400 |

## 11. 已知边界

| 项 | 说明 |
| --- | --- |
| 制品 key 后缀曾是 `.tar.gz` 但内容是 zip | **已修**：`ArtifactService` 的 key 后缀改成 `.zip`（同项目的 `SyncUploadService` 早就用 `.zip`）。存量制品的 key 记在 `artifacts.storage_key`，下载走库里的 key，不受影响。 |
| Web token 存 localStorage | 有 XSS 面。内部管理工具的常规取舍；换 sessionStorage 则刷新页面即掉线，影响体验。 |
| 邀请链接指向本机 | `skillmesh.web.base-url` 默认 `http://127.0.0.1:5174`。跨机访问需改该配置；Web 端同时展示原始 token 便于复制。 |
| 邀请无邮件投递 | 没有 SMTP 依赖，管理员手动把链接发给对方。 |
| 审核 diff 无缓存 | 每次查看重新解包。低频场景可接受。 |
| 审核队列无游标分页 | v2 只有 `limit`（上限 200），超过 200 条待审取不到。团队规模下队列不长，先这样；要翻页时按 §5.3 的说明加游标。 |
| 审核队列不排除「自己提交的版本」 | 提交人自己会在队列里看到自己发的版本，但点「通过」会被服务端 403（不能审自己发的版本）。前端拿不到提交人的 id（`submitted_by` 只是显示名），没法预先禁用按钮。 |
| 审计仍只在 Skill 维度 | 全表扫描已修（`GET /audit-events` 改成一条 join 查询，可见性条件与搜索共用 `VisibleSkills`），但**审计台页面**仍不做。 |
| 治理类审计事件查不出来 | 本分支新增的 `invitation.*` / `organization.*` / `team.*` / `*.membership.*` 事件写进了 `audit_events`，但两个查询端点（`GET /api/v1/skills/{id}/audit` 与 `GET /api/v1/audit-events`）都按 `target_type='skill'` 过滤，**一条也查不出来**。要查治理流水得给端点加按 `target_type` 分流的分支——本轮不做。另外 `audit_events.request_id` 从来没被写过，恒为 null。 |
| 制品签名仍覆盖**发布方声明的清单** | 审核 diff 已改用服务端从真 zip 算的清单（不信自填），但**签名与 `content_digest` 仍用 `skill_versions.manifest_json`**（发布方声明的那份）。发布方声明与真包不符时：审核面板说真话，CLI 装的时候会对一遍本地算出的摘要与服务端的 `content_digest`，不符就拒装（`verify.go`、`report.go` 的「装下来的内容和清单摘要对不上」）——**安全上不漏，只是体验割裂**。改它要连带改签名与 CLI 验签，牵扯面大，本轮不做。 |
| 审核队列的 `status` 参数无白名单 | `GET /api/v1/reviews?status=published` 会列出已发布版本。**不是权限洞**（同一套可见性规则仍在），但端点叫「审核队列」却能列出队列外的行，语义不对。 |
| 同组织成员能看到彼此的 OIDC `sub` | 邀请的 `subject` 要精确匹配 OIDC `sub`，管理员得拿得到要填的值 → 成员花名册带 `subject`、`/me` 回显自己的。同组织成员因此能看到彼此的 `sub`（Keycloak 的内部 id）。`sub` 是伪名标识不是凭据（拿到它不能登录），但这是一次信息面放宽。要收回就只能让管理员去问人，或加一个「按 user_id 换取 sub」的受限端点。 |
| `install` 收 Skill ID 而非 slug | v1 的实现就是这样（`install <skill-id>`），设计文档 §7.4 的「推荐 CLI 命令」里其实没有 install 这一条，所以设计文档对此没有规定——这是 v1 自己留下的缺口。`skillmesh search` 输出的最后一列就是完整 ID，所以闭环走得通；让 install 同时吃 slug 是 v1 的可用性缺口，不在 v2 范围。 |
| 发布权看**组织角色**、审核放行权看**团队角色** | 两层角色各管一件事（§5.2、§5.3）。发布与提交审核判组织角色——接受邀请会写一行 `team_role=member`，用团队角色判会把组织角色永久遮住。审核放行与队列可见性判团队角色（没有团队成员行才回落到组织角色）——审核是团队范围内的事。所以被邀请进来的人默认组织 `member`、团队 `member`：要发布得把**组织角色**提到 publisher，要审别人的东西得把**团队角色**提到 reviewer。控制台两边都能改（选中团队改团队角色，不选团队改组织角色）。 |
| **读**团队可见的 Skill：团队成员或组织 owner/admin | 读（直读、搜索、安装）的口径与审核放行不同：审核放行是团队范围内的事，所以判团队角色；读是「这内容归谁管」——团队可见的内容归该团队管，也归管着它的组织管，所以该团队成员与该组织的 owner/admin 都能看。建团队时不写 `team_memberships` 行，组织 owner 在该团队没有团队角色——若只认团队成员，他会读不到、搜不到自己团队的东西，而他在审核队列里却能看见并批准它，「能批不能看」说不通。团队里的**普通**成员仍看不到别的团队的 Skill。 |
| CLI 按 slug 找 Skill，同名时会挑错 | slug 在服务端是分域唯一的：个人 `uq_skills_personal_slug`、组织 `uq_skills_org_slug` 各管各的，所以「A 的个人 demo」「acme 的 demo」「B 的公开 demo」可以并存。CLI 的 `publish` 只拿 slug 去搜索，命中哪一条取决于 `updated_at` 排序——于是「发到 acme」可能先撞上 A 的个人 demo，被报成「归属改不了」。要按目标范围挑，服务端得给搜索接口加归属过滤，或 CLI 在歧义时列出候选让人选。 |
| 只给 `--org` 时，归属冲突的报错里团队是裸 ID | 报错的组织用 slug、团队用 ID，是同一句话两种口径。原因是 CLI 只在指定 `--team` 时才拉团队列表（省一次请求，也免得团队接口挂了拖累「发到组织」），没带 `--team` 就没法把已登记的 team id 翻成 slug。服务端在 Skill 响应里带 team slug 会给列表接口引入 N+1，所以没这么做。 |
| 多设备冲突可视化 | 仍只在 CLI 侧处理。 |
