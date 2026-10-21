# v2 团队控制台 · 双人端到端验收清单

> 对应计划：`docs/superpowers/plans/2026-10-20-v2-team-console-plan.md` 的 Task 12（14 步版）。
> 验收日期：2026-10-21。
> 验收方式：**本机 API + CLI 自动化**跑通闭环；浏览器交互步骤用 API 达成同一状态，浏览器路径未实测。
>
> **本轮是重跑**：`f8adc8b` 之后又改了 8 个 commit（`16aaec8` / `b8770b0` / `dec956e` / `9489715` /
> `7999524` / `5ea6c1f` / `e39a545` / `d752c24`），动了角色判定、并发保护、索引、事务边界。
> 上一轮的结论全部作废重来。本轮跑在 **HEAD = `9489715`** 的构建上。
>
> **本轮跑在一次性隔离库 `skillmesh_acceptance` 上**（不是开发库 `skill_mesh`）。
> 隔离库建好时是空的，Flyway 在上面跑完 V1–V4；跑完已删除。

## 环境

| 组件 | 地址 | 备注 |
| --- | --- | --- |
| MinIO | http://127.0.0.1:9100 | `infra/scripts/start-minio.sh`（已在跑，未重启） |
| Keycloak | http://127.0.0.1:8180/kc | `infra/scripts/start-keycloak.sh`（已在跑，未重启） |
| API | http://127.0.0.1:8080/api/v1 | **本轮重新起了**（上一轮留下的旧构建占着 8080，已 kill） |
| 前端 | http://127.0.0.1:5174 | 已有进程在跑；已确认它服务的是**当前代码**（见「踩到的坑」6） |
| CLI | `apps/cli/skillmesh.exe` | **本轮重新构建**，用 amd64 Go（`GOROOT=D:/tmp/goamd64/go`） |

### 数据库

| 库 | 用途 | 本轮状态 |
| --- | --- | --- |
| `skillmesh_acceptance` | 本轮验收专用，一次性 | **本轮唯一写入的库**。`jdbc:postgresql://123.56.146.58:5432/skillmesh_acceptance`，建库→跑完→已删 |
| `skill_mesh` | 开发库 | **未触碰**。本轮 API 的数据源指向隔离库 |
| `skillmesh_test` | Java 集成测试库 | **未触碰**。本轮没有跑任何 Java 测试 |

**怎么确认 API 连的是隔离库**（两条独立证据）：

1. API 启动日志（Flyway）打印 `Database: jdbc:postgresql://123.56.146.58:5432/skillmesh_acceptance (PostgreSQL 18.4)`。
2. 隔离库里 `flyway_schema_history` 有 V1–V4 四条记录，且本轮造的全部数据（十几条邀请、2 个组织成员、2 个技能、若干版本）都在隔离库里。

起 API 的方式（**不改代码、不改 `.env`**，只覆盖环境变量）：

```bash
cd apps/api && SM_DB_URL='jdbc:postgresql://123.56.146.58:5432/skillmesh_acceptance' \
  ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

**账号**：Keycloak realm `skillmesh` 里 `dev-user` / dev 与 `dev-user2` / dev2。
`dev-user2` 由 `infra/scripts/add-second-user.sh` 补建（幂等）。

**本轮用到的名字**：组织 slug `acme`、团队 slug `platform`、技能目录 `java-debug`（外加 `java-debug-v2` / `java-debug-v3` 做对照）。

| 对象 | ID |
| --- | --- |
| 组织 acme | `c6bb6fa7-3d66-4e1b-b429-4c4822b6ce87` |
| 团队 platform | `99af4abd-1364-40eb-8b9f-c70638541615` |
| dev-user | `136d7469-3f44-41c7-9b13-862bd7d16489` |
| dev-user2 | `794e847a-abaf-4d84-93c1-d96e4223db13` |
| 技能 java-debug | `13fead9c-5ea1-4ccb-b942-aa9c70b0da57` |
| 技能 java-debug-v2 | `cda4e14f-bbf2-4712-b54b-e0db809ac6dd` |

## 14 步结果

| # | 操作 | 期望 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 浏览器打开 `http://127.0.0.1:5174/login`，用 dev-user / dev 登录 | 顶栏显示 Dev User | **浏览器路径未实测**——需人工在浏览器验证：打开该 URL → 点「登录」按钮（`data-test="login"`）→ 跳到 Keycloak → 用 dev-user / dev 登录 → 回到控制台，顶栏 `data-test="who"` 显示「Dev User」。**API 侧等价已验证**：`GET /api/v1/me` 用 dev-user 的 token → **200**，`display_name: "Dev User"`、`email: dev@skillmesh.local`、`organizations: []` | 未实测（浏览器），API 达成 |
| 2 | 建组织 acme 与团队 platform | 组织 acme、团队 platform 建好 | `POST /api/v1/organizations`（slug=acme, name=Acme）→ **201**，`id=c6bb6fa7-3d66-4e1b-b429-4c4822b6ce87`；`POST /api/v1/organizations/{orgId}/teams`（slug=platform）→ **201**，`id=99af4abd-1364-40eb-8b9f-c70638541615` | 通过 |
| 3 | 团队页生成邀请链接 | 拿到 `http://127.0.0.1:5174/invite/<token>` | **浏览器路径未实测**——需人工验证：打开 `http://127.0.0.1:5174/team` → 选团队 platform → 选组织角色与团队角色 → 点「生成邀请链接」（`data-test="invite"`）→ 页面出现 `data-test="invite-link"` 的链接。**API 侧等价已验证**：`POST /api/v1/organizations/{orgId}/invitations`（body：`team_id=platform`、`org_role=member`、`team_role=member`）→ **201**，返回 `invite_url = http://127.0.0.1:5174/invite/us532NAEamjUvaD-…`（明文 token 只此一次返回，库里只存 SHA-256）。另：未登录 `GET /api/v1/invitations/{token}` → **200**，`status: "pending"`，摘要公开可读（`SecurityConfig` 对该路径 permitAll） | 未实测（浏览器），API 达成 |
| 4 | 无痕窗口打开邀请链接，用 dev-user2 / dev2 登录并接受 | 显示「已加入」，角色是**落库后的实际角色** | **浏览器路径未实测**——需人工验证：打开邀请链接 → 未登录时显示「登录后接受」（`data-test="login-first"`）→ 登录后点「接受邀请」（`data-test="accept"`）→ 页面出现 `data-test="done"` 的「已加入，去团队页看看。」。**API 侧等价已验证**：`POST /api/v1/invitations/{token}/accept` 用 dev-user2 的 token → **200**，`message: "已加入"`、`org_role: "member"`、`team_role: "member"`。**本轮新增断言（`dec956e` 的行为）**：再造一份邀请写着 `org_role=member`，让**已经是 publisher** 的 dev-user2 接受 → 响应给的是 `org_role: "publisher"`、`team_role: "member"`，即**落库后的实际角色**，不是邀请上写的那一档。同一人重复接受同一邀请 → **200** 幂等 | 未实测（浏览器），API 达成 |
| 5 | 团队页把 dev-user2 的组织角色改成 publisher | 成员表角色列显示 publisher | **浏览器路径未实测**——需人工验证：打开 `http://127.0.0.1:5174/team` → **不要选团队**（选了团队，列标题会变成「团队角色」，改的是团队角色）→ 成员表找到「Dev User Two」→ 角色下拉（`data-test="role-edit"`）改 publisher → 角色列更新为 publisher。**API 侧等价已验证**：`PATCH /api/v1/organizations/{orgId}/members/{dev-user2-id}` 由 dev-user（组织 owner）发起 → **204**；`GET /api/v1/organizations/{orgId}/members` → dev-user2 角色为 `publisher`。**附带的权限负例**：dev-user2 自己改自己的组织角色 → **403 FORBIDDEN**（「组织权限不足」）；dev-user2 改别人的组织角色 → **403 FORBIDDEN**（「组织权限不足」）；dev-user2 改团队成员角色 → **403 FORBIDDEN**（「只有组织或团队管理员能管成员」）；dev-user2 加团队成员 → **403 FORBIDDEN**（同上前） | 未实测（浏览器），API 达成 |
| 6 | dev-user2 用 CLI 发布到团队 | 输出「已提交审核」 | `skillmesh publish skills/java-debug --org acme --team platform`（dev-user2，组织角色 publisher）→ 服务端接受，输出 `已提交审核 java-debug-v2 -> cda4e14f-bbf2-4712-b54b-e0db809ac6dd（1.0.0，578 字节）`。**上一轮那个「0 字节」显示 bug 已修好**（`e39a545`）：本轮三次发布都正确带出制品大小（578 / 584 / 673 字节），与库里 `artifacts.size_bytes` 一致。**本轮新判定的正面确认**（`16aaec8`）：服务端现在按**组织角色 publisher** 判发布权，dev-user2 组织角色是 publisher、团队角色是 member，照样发得出去 | 通过 |
| 7 | dev-user 打开 `/reviews` | 能看到待审版本，带扫描结论与来源 | **浏览器路径未实测**——需人工验证：打开 `http://127.0.0.1:5174/reviews` → 列表里出现 `data-test="review-item"` 的条目，显示提交人「Dev User Two」与扫描结论。**API 侧等价已验证**：`GET /api/v1/reviews?status=pending_review` 用 dev-user 的 token → **200**，返回 3 条待审项（java-debug 1.0.0 / 1.0.1 / java-debug-v2 1.0.0），每条都带 `submitted_by: "Dev User Two"`、`scan.status: "passed"`、`scan.summary: "未发现问题"`、`status: "pending_review"`。来源字段全为 null（本地发布没有 git 源，真实场景下应有仓库/分支/commit） | 未实测（浏览器），API 达成 |
| 8 | 打开详情 | 有 SKILL.md 正文与文件级差异 | **浏览器路径未实测**——需人工验证：点列表条目进 `http://127.0.0.1:5174/reviews/{skillId}/{version}` → 看到 `data-test="skill-md"` 的正文与 `data-test="diff-row"` 的文件级差异行。**API 侧等价已验证**：`GET /api/v1/reviews/{skillId}/{version}` → **200**，`skill_md` 是完整的 SKILL.md 正文（含 frontmatter 与正文）。**三种 diff 形态都验过**：① 首个版本（无基线）→ `diff = {base_version: "", files: [{path: "SKILL.md", kind: "added", added: 0, removed: 0}]}`；② 内容完全相同的新版本 → `diff = {base_version: "1.0.0", files: []}`（没差异就没行，**不是 bug**）；③ 内容改过的版本 → `diff = {base_version: "1.0.1", files: [{path: "SKILL.md", kind: "modified", added: 3, removed: 1}]}` | 未实测（浏览器），API 达成 |
| 9 | 点「通过」 | 提示已通过 | **浏览器路径未实测**——需人工验证：在审核详情页点「通过」（`data-test="approve"`）→ 页面出现 `data-test="notice"` 的提示，版本状态标签（`data-test="version-status"`）变为 published。**API 侧等价已验证**：`POST /api/v1/skills/{skillId}/versions/{version}/approve` 用 dev-user 的 token → **200**，`status: "published"`；重复过审 → **409 VERSION_CONFLICT**（「只有待审核的版本能过审，当前状态：published」）。本轮共过审 4 个版本（1.0.0 / 1.0.1 / 1.0.2 / java-debug-v2 1.0.0），全部 200 | 未实测（浏览器），API 达成 |
| 10 | dev-user2 `skillmesh search java-debug` | 能搜到；输出最后一列是 Skill 的完整 ID | `skillmesh search java-debug` 用 dev-user2 的 token → 输出两行：`java-debug … team 1.0.2 13fead9c-5ea1-4ccb-b942-aa9c70b0da57` 与 `java-debug-v2 … team 1.0.0 cda4e14f-bbf2-4712-b54b-e0db809ac6dd`。最后一列确实是完整 Skill ID | 通过 |
| 11 | dev-user2 `skillmesh install <上一步的 ID>` | 装到 Agent 目录 | `skillmesh install cda4e14f-bbf2-4712-b54b-e0db809ac6dd --scope project` → 装到 `<项目根>/.claude/skills/java-debug-v2`，含 `SKILL.md`（545 字节，内容正确）与 `.skillmesh-install` 标记；项目根生成 `skillmesh.lock.yaml` 锁文件（记录 source_id / version / sha256 / installed_hash / scope / agents）。**装到临时目录**（`D:\tmp\smacc\proj`），没有污染本机真实的 `~/.claude/skills` | 通过 |
| 12 | 负例：dev-user2 打开 `/reviews` | 看不到（他不是 reviewer） | **浏览器路径未实测**——需人工验证：用 dev-user2 登录后打开 `http://127.0.0.1:5174/reviews` → 列表为空（空态「没有待审的版本」）。**API 侧等价已验证**：在队列里**还有 2 条待审项**的时候，`GET /api/v1/reviews?status=pending_review` 用 dev-user2 的 token → **200 `[]`**；`GET /api/v1/reviews/{skillId}/{version}` → **404 NOT_FOUND**（「版本 不存在或不可见」）。dev-user2 的团队角色是 member、组织角色是 publisher——审核放行权判的是**团队角色**，所以看不到 | 未实测（浏览器），API 达成 |
| 13 | 负例：把一个过期邀请的链接打开 | 页面提示已过期，接受返回 409 `INVITATION_INVALID` | **浏览器路径未实测**——需人工验证：打开一个已过期的邀请链接 → 页面出现 `data-test="invalid"` 的「这份邀请已经过期。」。**API 侧等价已验证**：造一份过期邀请（把 `org_invitations.expires_at` 改到 1 天前）→ 未登录 `GET /api/v1/invitations/{token}` → **200**，`status: "expired"`；`POST /api/v1/invitations/{token}/accept` → **409**，`code: "INVITATION_INVALID"`，detail「邀请已过期」。另外：不存在的 token → **404**；**已撤销**的邀请 → 公开摘要 **404**、接受 **409 INVITATION_INVALID**（「邀请已失效」） | 未实测（浏览器），API 达成 |
| 14 | 负例：组织 owner 尝试把自己降级 | 400 `VALIDATION_FAILED` | `PATCH /api/v1/organizations/{orgId}/members/{dev-user-id}` 用 dev-user（组织唯一 owner）自己的 token，角色改成 admin → **400**，`code: "VALIDATION_FAILED"`，detail「不能改自己的组织角色」；改成 member 同样 **400** | 通过 |

### 第 15 步（本轮新增，上一轮没验过）

`16aaec8` 加的新判定：给**已有的**团队 Skill 建新版本也是一次发布动作，要按**组织角色 publisher** 判。
上一轮只验过「建 Skill 时判发布权」，没验过「发新版本」这条路径。

| 操作 | 期望 | 实际结果 | 结论 |
| --- | --- | --- | --- |
| 把 dev-user2 的组织角色降为 member（`PATCH /organizations/{org}/members/{dev-user2}` → **204**），再让它给已有团队 Skill java-debug 建新版本 | 403 `FORBIDDEN` | `POST /api/v1/skills/13fead9c-…/versions`（dev-user2 的 token）→ **403**，`code: "FORBIDDEN"`，detail「**在该组织里需要 publisher 及以上角色才能发布**」 | 通过 |
| 同上，再让它**新建**一个团队 Skill | 403 `FORBIDDEN`，同一句文案 | `POST /api/v1/skills`（`visibility=team`）→ **403 FORBIDDEN**，「在该组织里需要 publisher 及以上角色才能发布」 | 通过 |
| CLI 侧同一场景（dev-user2，组织角色 member） | 客户端与服务端都要挡住 | ① `skillmesh publish skills/java-debug-v3 --org acme --team platform` → 客户端先挡：`你在组织 "acme" 的角色是 member，发布到组织/团队需要 publisher 及以上`；② `skillmesh publish skills/java-debug`（不带 `--org`）→ 走到服务端：`发布 java-debug 失败: FORBIDDEN (403): 在该组织里需要 publisher 及以上角色才能发布` | 通过 |

**这一条的关键点**：判断依据是**组织角色**，不是团队角色。
dev-user2 当时团队角色是 member、组织角色是 publisher，两者结论相反——
服务端 `requireOrgPublisher` 刻意传 `teamId = null`，就是为了不被「接受邀请写下的 team_role=member」把组织角色遮住。

### 附加：并发接受同一份邀请

`dec956e` 给接受邀请加了行锁串行化。做法：造一份组织级邀请，让**两个不同用户同时**接受。

| 轮次 | 发起顺序 | 结果 |
| --- | --- | --- |
| 1–5 | dev-user 先起线程 | 每轮都是 `[200, 409]`：赢的一方 **200**，输的一方 **409 `INVITATION_INVALID`**（「邀请已被接受」） |
| 6–10 | dev-user2 先起线程 | 每轮都是 `[200, 409]`，同上 |

**恰好一个成功、另一个 409**，两种发起顺序都稳定。库里核对：`organization_memberships` 只有 2 行
（每个用户一行，没有重复写入），`joined_at` 还是最早那次接受的时间（`b8770b0` 的「保留 joined_at」也一并验到）。

## 汇总

- **14 步全部达成**，外加第 15 步（新建版本的发布权判定）与并发那条也达成。
- 第 1、3、4、5、7、8、9、12、13 步的**浏览器交互路径未实测**（本机无浏览器），
  但每一步的**后端状态变更都用 API 达成并断言过**，链路本身是通的。
- 第 2、6、10、11、14 步是纯 API/CLI 步骤，**全部实测通过**。
- 负例（12、13、14）全部按期望返回：404 / 409 `INVITATION_INVALID` / 400 `VALIDATION_FAILED`。
- 上一轮之后改的 8 个 commit 逐条对上了：
  - `16aaec8`（新建版本按组织角色 publisher 判）→ **第 15 步**，403 文案正确。
  - `b8770b0`（权限档位收进 Roles；加成员只升不降 + 保留 joined_at）→ 加团队成员时**没有**把 publisher 静默降回 member，`joined_at` 保持不变。
  - `dec956e`（接受邀请行锁串行化 + 响应给实际角色）→ **第 4 步**响应给 publisher（不是邀请上的 member）；**并发那条**恰好一个 200 一个 409。
  - `9489715`（邀请列表索引改成 `(organization_id, created_at DESC)`）→ 库里的索引确实是这个形状，列表查询也确实是「只按 organization_id 过滤 + created_at DESC 排序」，两边对上了；V4 迁移在隔离库上跑成功。
  - `7999524` / `5ea6c1f` / `e39a545`（前端与 CLI 显示修复）→ CLI 的「0 字节」已修好；前端 dev server 确认在服务当前代码。
- **没发现像 bug 的地方。** 见下面「需要留意的两点」。

### 需要留意的两点（不是 bug，但值得知道）

1. **同一份内容发新版本，diff 是空的**：1.0.0 与 1.0.1 内容一模一样时，
   `GET /api/v1/reviews/{id}/1.0.1` 返回 `diff = {base_version: "1.0.0", files: []}`。
   看起来像「没生成差异」，其实是内容没变。改过内容之后（1.0.2）就正常给出
   `[{path: "SKILL.md", kind: "modified", added: 3, removed: 1}]`。审核详情页遇到空 diff 列表时是正常现象。
2. **给已有 Skill 发新版本时，CLI 不能带 `--org/--team`**：
   `skillmesh publish <dir> --org acme --team platform` 对已存在的 Skill 会报
   `Skill "java-debug" 已存在，--org/--team 只在新建时生效`。要发新版本就跑 `skillmesh publish <dir>`。
   顺带一提，服务端的新发布权判定正是在这条（不带 `--org`）的路径上才拦得到——
   带 `--org` 时 CLI 客户端自己先按组织角色挡掉了（`resolvePublishScope` 里的 `roleAtLeast(org.Role, "publisher")`）。

## 踩到的坑

0. **验收用的技能目录没有进仓库**：`java-debug` / `java-debug-v2` / `java-debug-v3` 三个目录是本次验收
   临时造的（含 `SKILL.md`），只用于喂给 `skillmesh publish`，**放在仓库外的 `D:\tmp\smacc\skills\`**，
   未进仓库。重跑第 6 步前得先自己造一个含 `SKILL.md` 的目录。
1. **Keycloak 的 realm 导入不覆盖已存在的用户**——改了 `realm-skillmesh.json` 之后，
   已经跑过的本机实例里还是没有 `dev-user2`。这就是 `infra/scripts/add-second-user.sh` 存在的理由。
2. **邀请的 `subject` 字段比的是 OIDC 的 `sub`（一串 UUID），不是用户名**。给邀请传
   `subject: "dev-user2"`，dev-user2 接受时直接 **403「这份邀请不是发给你的」**。
   Web 端从来不传这个字段（`apps/web/src/pages/TeamPage.vue` 只传 `team_id` / `org_role` / `team_role`），
   所以 UI 路径不会踩到；用 API 手工造邀请时不要传 `subject`，或者传真正的 `sub`。
3. **开发库里已经有历史数据**（上一轮记的是 17 个历史 Skill），且 Keycloak 重启后 `sub` 变了、
   库里会多出一批重复的 `users` 行（`users` 表按 `oidc_issuer + oidc_subject` 建档，`sub` 一变就是新用户）。
   本轮已经改用**一次性隔离库**，这条坑本轮没踩到——隔离库建好时是空的，没有任何历史数据干扰。
4. **第一次起 API 时端口 8080 被一个旧构建占着**——那是上一轮会话留下的进程。
   判断「跑的是不是当前代码」的一个好办法（沿用上一轮的办法）：请求
   `GET /api/v1/invitations/<随便一个 token>`，**401 是旧构建、404/200 是新构建**。
   本轮开工时先探了一次，拿到 404，说明旧构建已经带 permitAll，但仍按要求**重新起了 API**，
   因为旧进程连的是开发库、且不确定是不是 HEAD。
5. **`spring-boot:run` 日志里的 "Nothing to compile - all classes are up to date"**——
   它不代表跑的是旧代码：`target/classes` 的 class 文件比源文件新，
   而且 class 文件里能搜到本轮新加的中文字符串（「在该组织里需要 publisher 及以上角色才能发布」）。
   另外本轮的行为断言本身也证明了跑的是新代码（新版本 403、接受邀请返回实际角色、并发 409 都只有新代码才有）。
6. **前端的 dev server 是上一轮就在跑的**（PID 36252），没法确认它指向哪个 worktree。
   确认办法：拉它服务的源码模块，看内容对不对。
   `curl -s http://127.0.0.1:5174/src/pages/TeamPage.vue | grep -c membersScope` → **4**（`7999524` 新加的变量），
   `curl -s http://127.0.0.1:5174/src/auth/session.ts` 里能看到修好的 `if (seq > meApplied) { meApplied = seq; … }`
   （旧代码只有 `if (seq > meApplied) meError.value = message`，不更新 `meApplied`）→ **在服务当前代码**。
7. **Windows 控制台是 GBK，Python 读子进程输出会炸**：`subprocess.run(..., text=True)` 抓到 CLI 的中文输出时
   抛 `UnicodeDecodeError: 'gbk' codec can't decode byte 0xae`，结果 **CLI 其实已经发布成功了，输出却被吞掉**，
   差点误判成失败。给 `subprocess.run` 加 `encoding="utf-8", errors="replace"` 才看得见输出。
   这是验收脚本自己的问题，不是产品 bug。
8. **Flyway 跑 V4 时打了一条 WARN**：`DB: there is already a transaction in progress (SQL State: 25001)`。
   PostgreSQL 上跑带事务的 Flyway 迁移常见，迁移本身成功（`flyway_schema_history` 里 V4 `success = true`，
   索引形状也对）。不影响验收。

## 留给人工在浏览器里验证的清单

按顺序做，每步应当看到：

1. `http://127.0.0.1:5174/login` → 点「登录」→ Keycloak 里用 dev-user / dev → 回到控制台，
   顶栏 `data-test="who"` 显示「Dev User」。
2. `http://127.0.0.1:5174/team` → 选组织 acme（**不要选团队**）→ 成员表里找到「Dev User Two」，
   角色下拉（`data-test="role-edit"`）改成 publisher → 角色列显示 publisher。
   > 选了团队的话，列标题会变成「**团队角色**」，改的是**团队角色**（管审核放行权），这一步要的是**组织角色**
   > （管发布权）——发布到团队看的是组织角色，两层是分开的。
3. 同一页 → 选团队 platform → 选组织角色与团队角色 → 点「生成邀请链接」（`data-test="invite"`）
   → 出现 `data-test="invite-link"` 的 `http://127.0.0.1:5174/invite/<token>`。
4. 无痕窗口打开该链接 → 显示「加入团队」与组织/团队/角色 → 点「登录后接受」（`data-test="login-first"`）
   并登录 dev-user2 / dev2 → 回到页面点「接受邀请」（`data-test="accept"`）→ 出现「已加入，去团队页看看。」
   （`data-test="done"`）。
5. `http://127.0.0.1:5174/reviews` → 列表里能看到 dev-user2 提交的待审版本（含扫描结论「未发现问题」）。
6. 点进详情 → 看到 SKILL.md 正文（`data-test="skill-md"`）与文件级差异行（`data-test="diff-row"`）
   → 点「通过」（`data-test="approve"`）→ 提示已通过，状态标签（`data-test="version-status"`）变 published。
7. 用 dev-user2 登录 → `http://127.0.0.1:5174/reviews` → 空态「没有待审的版本」。
8. 用 dev-user2 打开一个过期邀请链接 → 页面提示「这份邀请已经过期。」（`data-test="invalid"`）。
9. （可选，第 15 步的浏览器等价）用 dev-user2 在 `http://127.0.0.1:5174/team` 把自己的**组织角色**降成 member，
   然后让 dev-user2 试着往团队里发一个 Skill → 应当被拒，页面报「在该组织里需要 publisher 及以上角色才能发布」。

---

## 追加验收：合并后的最后一轮（2026-10-21 晚）

**合并**：`worktree-v2-team-console` fast-forward 合并到 `main`（`2a7659b` → `10aac95`），合并后两支同点。
本轮只验收**合并进来的最后两个提交**：`72d6395`（服务端：组织内 slug 唯一预检 + 上传收尾回传 `version_status`）、
`10aac95`（CLI：发布结果看版本状态、归属比对、`SLUG_TAKEN` 翻译）。

### 环境（与上一轮不同的地方）

| 组件 | 本轮 | 说明 |
| --- | --- | --- |
| 数据库 | **开发库 `skill_mesh`** | 远程 PG 上 `CREATE DATABASE` 被安全策略拦了，只能用开发库。上一轮用隔离库是因为「开发库里已经有历史数据…会干扰」；本轮改用唯一 slug（`acc2-*`）避开干扰 |
| API | 本轮新起（worktree 代码） | 上一轮留下的进程已不在；`GET /actuator/health` → 200 UP |
| MinIO / Keycloak / 前端 | 上一轮就在跑，未重启 | 9100 / 8180 / 5174 |
| CLI | 本轮用 amd64 Go 重建 | `GOROOT=D:/tmp/goamd64/go`（本机默认 Go 是 32 位） |

### 7 个用例

| # | 操作 | 期望 | 实际 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 个人 Skill 发布（`acc2-personal`） | 「已发布」 | `已发布 acc2-personal -> 5ac359f7…（1.0.0，247 字节）` | 通过（`version_status=published`） |
| 2 | 发到 acme/platform（`acc2-demo`） | 「已提交审核」 | `已提交审核 acc2-demo -> 2fe8eff6…（1.0.0，257 字节）` | 通过（`version_status=pending_review`） |
| 3 | 发 v2，带**一样**的 `--org/--team` | 放行 | `已提交审核 acc2-demo -> 2fe8eff6…（1.0.1，276 字节）` | 通过（**旧代码在这里会报「--org/--team 只在新建时生效」**） |
| 4 | 发 v2 到另一个组织（`acc2-org2`） | 报错说清归属 | 「Skill "acc2-demo" 已存在，归属改不了：它已登记在 组织 UUID 的团队 UUID，你要发到 组织 acc2-org2。去掉 --org/--team 会沿用它已登记的归属」 | 通过，但**已登记那侧显示的是 UUID**（已修，见下） |
| 5 | 不带 scope 发 v2 | 沿用已登记的归属，放行 | `已提交审核 acc2-demo -> 2fe8eff6…（1.0.2，276 字节）` | 通过 |
| 6 | 上传含 AWS key 的包 | 版本 `rejected`，complete 回 `version_status` | `complete: 202 {"session_status":"completed","version_status":"rejected"}` | 通过 |
| 7 | 同组织内再建同名 Skill | 409 `SLUG_TAKEN` | `409 {"code":"SLUG_TAKEN","detail":"slug 在该组织下已被占用: acc2-dup"}` | 通过 |

**用例 6 只能从 API 走**：CLI 本地扫描与服务端的密钥规则完全一致（同样 6 条 BLOCKING 规则），
含密钥的包在本地就被拦，走不到服务端那一步。CLI 侧对 `version_status=rejected` 的判定由
`publish_test.go` 的「版本被服务端拒了要报错」覆盖（走 httptest 假服务端）。

### 本轮发现并修掉的问题

用例 4 的报错里，**同一句话两种口径**：目标那侧是 slug（「你要发到 组织 acc2-org2」），
已登记那侧是 UUID（「它已登记在 组织 9d7b67e9… 的团队 35c57d21…」）。用户看到 UUID 会懵。

修法（CLI 侧，不动服务端）：服务端 `toResponse` 没有 org/team 的 join，给它加 slug 会在列表场景引入 N+1；
改成 CLI 侧反查——`publishScope` 带上已解析的团队列表，`ownerScope()` 把已登记的 team id 翻成 slug，
同一组织的组织 slug 直接复用 scope 里那个。服务端返回的 slug 拼不出时仍退回 id（我不了解那个组织）。

单测：`TestCheckExistingSkill比对归属` 补一条——同一组织不同团队时，错误里要同时出现
「组织 acme」「团队 core」「团队 platform」三个 slug。

**这一路只有单测覆盖**：真实环境要验它得先在 acme 下再建一个团队（那里只有 platform），
建团队被安全策略拦了。同组织不同**团队**的报错文案没在真实服务端跑过；
用例 4（不同组织）验的是另一条分支，那边组织 slug 本来就查不到、显示 UUID 是对的。

### 遗留

- **浏览器人工验证清单（上一轮列的 9 步）仍未实测**——浏览器路径始终只做到 API 等价。
- **验收数据留在开发库**：`acc2-personal` / `acc2-demo` / `acc2-dup` 三个 Skill、组织 `acc2-org2`、
  团队 `core`。服务端没有删 Skill / 组织 / 团队的端点，要清只能直接删库，本轮没清。
