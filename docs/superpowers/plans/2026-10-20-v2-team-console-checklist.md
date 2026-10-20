# v2 团队控制台 · 双人端到端验收清单

> 对应计划：`docs/superpowers/plans/2026-10-20-v2-team-console-plan.md` 的 Task 12（14 步版）。
> 验收日期：2026-10-21。
> 验收方式：**本机 API + CLI 自动化**跑通闭环；浏览器交互步骤用 API 达成同一状态，浏览器路径未实测。

## 环境

| 组件 | 地址 | 备注 |
| --- | --- | --- |
| MinIO | http://127.0.0.1:9100 | `infra/scripts/start-minio.sh` |
| Keycloak | http://127.0.0.1:8180/kc | `infra/scripts/start-keycloak.sh` |
| API | http://127.0.0.1:8080/api/v1 | `cd apps/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |
| 前端 | http://127.0.0.1:5174 | `cd apps/web && pnpm dev` |
| CLI | `apps/cli/skillmesh.exe` | `go build -o skillmesh.exe ./cmd/skillmesh` |

`infra/scripts/smoke.sh` 四项全 OK。

**数据库**：`local` profile 走 `application.yml` → `SM_DB_URL`，指向**开发库 `skill_mesh`**；
测试库 `skillmesh_test` 只在 `application-test.yml` 里（`apps/api/src/test/resources/application-test.yml`）。
本次没有跑 Java 集成测试，开发库与测试库未混。

**账号**：Keycloak realm `skillmesh` 里 `dev-user` / dev 与 `dev-user2` / dev2。
`dev-user2` 由 `infra/scripts/add-second-user.sh` 补建（realm 导入不覆盖已存在的用户，脚本可重复跑，第二次输出「dev-user2 已存在」）。

## 14 步结果

| # | 操作 | 期望 | 实际结果 | 结论 |
| --- | --- | --- | --- | --- |
| 1 | 浏览器打开 `http://127.0.0.1:5174/login`，用 dev-user / dev 登录 | 顶栏显示 Dev User | **浏览器路径未实测**——需人工在浏览器验证：打开该 URL → 点「登录」按钮（`data-test="login"`）→ 跳到 Keycloak → 用 dev-user / dev 登录 → 回到控制台，顶栏显示「Dev User」。**API 侧等价已验证**：`GET /api/v1/me` 用 dev-user 的 token 返回 `display_name: "Dev User"`、`email: dev@skillmesh.local` | 未实测（浏览器），API 达成 |
| 2 | 建组织 acme 与团队 platform | 组织 acme、团队 platform 建好 | `POST /api/v1/organizations`（slug=acme, name=Acme）→ **201**，`id=9d7b67e9-839e-47ba-b313-85dee8df127f`；`POST /api/v1/organizations/{orgId}/teams`（slug=platform）→ **201**，`id=35c57d21-51bb-4140-bdee-f4318b045146` | 通过 |
| 3 | 团队页生成邀请链接 | 拿到 `http://127.0.0.1:5174/invite/<token>` | **浏览器路径未实测**——需人工验证：打开 `http://127.0.0.1:5174/team` → 选团队 platform → 选组织角色 → 点「生成邀请链接」（`data-test="invite"`）→ 页面出现 `data-test="invite-link"` 的链接。**API 侧等价已验证**：`POST /api/v1/organizations/{orgId}/invitations` → **201**，返回 `invite_url = http://127.0.0.1:5174/invite/<token>`（明文 token 只此一次返回，库里只存 SHA-256）。另：未登录 `GET /api/v1/invitations/{token}` → **200**，摘要公开可读（`SecurityConfig` 对该路径 permitAll） | 未实测（浏览器），API 达成 |
| 4 | 无痕窗口打开邀请链接，用 dev-user2 / dev2 登录并接受 | 显示「已加入」 | **浏览器路径未实测**——需人工验证：打开邀请链接 → 未登录时显示「登录后接受」（`data-test="login-first"`）→ 登录后点「接受邀请」（`data-test="accept"`）→ 页面出现 `data-test="done"` 的「已加入，去团队页看看。」。**API 侧等价已验证**：`POST /api/v1/invitations/{token}/accept` 用 dev-user2 的 token → **200**，`message: "已加入"`；重复接受同一邀请 → **200**（同一人幂等）；`GET /api/v1/me` 显示 dev-user2 已加入 acme，角色 member | 未实测（浏览器），API 达成 |
| 5 | 团队页把 dev-user2 的组织角色改成 publisher | 成员表角色列显示 publisher | **浏览器路径未实测**——需人工验证：打开 `http://127.0.0.1:5174/team` → **不要选团队**（选了团队则下拉改的是团队角色，这一步要的是组织角色）→ 成员表找到「Dev User Two」→ 角色下拉改 publisher → 表格角色列更新为 publisher。**API 侧等价已验证**：`PATCH /api/v1/organizations/{orgId}/members/{dev-user2-id}` 由 dev-user（组织 owner）发起 → **204**；`GET /api/v1/organizations/{orgId}/members` → dev-user2 角色为 `publisher`。**附带的权限负例**：dev-user2 自己改自己的组织角色 → **403 FORBIDDEN**（「组织权限不足」）；dev-user2 改别人的角色 → **403 FORBIDDEN**；dev-user2 改团队成员角色 → **403 FORBIDDEN**（「只有组织或团队管理员能管成员」） | 未实测（浏览器），API 达成 |
| 6 | dev-user2 用 CLI 发布到团队 | 输出「已提交审核」 | `skillmesh publish ./skills/java-debug --org acme --team platform` → 输出 `已提交审核 java-debug -> 49fbc2ad-…（1.0.0，0 字节）`。**注意**：末尾的「0 字节」是 CLI 的显示 bug——「已提交审核」分支的 `publishResult` 没填 `SizeBytes`（`apps/cli/internal/cmd/publish.go` 的 `needsReview` 分支），实际制品大小是 552 字节（`artifacts.size_bytes`），`SKILL.md` 557 字节。不影响验收结论，但输出误导。**该 bug 已由并发会话修复**（`apps/cli/internal/cmd/publish.go` 补上 `SizeBytes: artifact.SizeBytes`） | 通过（含一处 CLI 显示 bug，已修复） |
| 7 | dev-user 打开 `/reviews` | 能看到待审版本，带扫描结论与来源 | **浏览器路径未实测**——需人工验证：打开 `http://127.0.0.1:5174/reviews` → 列表里出现 `data-test="review-item"` 的条目，显示提交人「Dev User Two」与扫描结论。**API 侧等价已验证**：`GET /api/v1/reviews?status=pending_review` 用 dev-user 的 token → **200**，返回待审项：slug `java-debug`、version `1.0.0`、`submitted_by: "Dev User Two"`、`scan.status: "passed"`、`status: "pending_review"`。来源字段全为 null（本地发布没有 git 源，真实场景下应有仓库/分支/commit） | 未实测（浏览器），API 达成 |
| 8 | 打开详情 | 有 SKILL.md 正文与文件级差异 | **浏览器路径未实测**——需人工验证：点列表条目进 `http://127.0.0.1:5174/reviews/{skillId}/{version}` → 看到 `data-test="skill-md"` 的正文与 `data-test="diff-row"` 的文件级差异行。**API 侧等价已验证**：`GET /api/v1/reviews/{skillId}/{version}` → **200**，`skill_md` 是完整的 SKILL.md 正文（含 frontmatter 与正文），`diff.files` = `[{path: "SKILL.md", kind: "added", added: 0, removed: 0, truncated: false}]`（首次发布没有可对比的已发布版本，所以是文件级差异，没有增删行数） | 未实测（浏览器），API 达成 |
| 9 | 点「通过」 | 提示已通过 | **浏览器路径未实测**——需人工验证：在审核详情页点「通过」（`data-test="approve"`）→ 页面出现 `data-test="notice"` 的提示，版本状态标签变为 published。**API 侧等价已验证**：`POST /api/v1/skills/{skillId}/versions/{version}/approve` 用 dev-user 的 token → **200**，`status: "published"`；重复过审 → **409 VERSION_CONFLICT**（「只有待审核的版本能过审，当前状态：published」） | 未实测（浏览器），API 达成 |
| 10 | dev-user2 `skillmesh search java-debug` | 能搜到；输出最后一列是 Skill 的完整 ID | `skillmesh search java-debug` 用 dev-user2 的 token → 输出两行：`java-debug-v2 … team 1.0.0 84bfb8f4-c67c-45d4-849d-302b0fca8d1f` 与 `java-debug … team 1.0.0 49fbc2ad-c93f-4f14-9139-5a173cf51a95`。最后一列确实是完整 Skill ID | 通过 |
| 11 | dev-user2 `skillmesh install <上一步的 ID>` | 装到 Agent 目录 | `skillmesh install 84bfb8f4-… --scope project` → 装到 `<项目根>/.claude/skills/java-debug-v2`，含 `SKILL.md`（557 字节，内容正确）与 `.skillmesh-install` 标记；项目根生成 `skillmesh.lock.yaml` 锁文件（记录 source_id / version / sha256 / installed_hash）。**装到临时目录**（`%TEMP%/t12-proj`），没有污染本机真实的 `~/.claude/skills` | 通过 |
| 12 | 负例：dev-user2 打开 `/reviews` | 看不到（他不是 reviewer） | **浏览器路径未实测**——需人工验证：用 dev-user2 登录后打开 `http://127.0.0.1:5174/reviews` → 列表为空（空态「没有待审的版本」）。**API 侧等价已验证**：`GET /api/v1/reviews?status=pending_review` 用 dev-user2 的 token → **200** `[]`；`GET /api/v1/reviews/{skillId}/{version}` → **404 NOT_FOUND**（「版本 不存在或不可见」）。dev-user2 的团队角色是 member、组织角色 publisher——审核放行权判的是团队角色，所以看不到 | 未实测（浏览器），API 达成 |
| 13 | 负例：把一个过期邀请的链接打开 | 页面提示已过期，接受返回 409 `INVITATION_INVALID` | **浏览器路径未实测**——需人工验证：打开一个已过期的邀请链接 → 页面出现 `data-test="invalid"` 的「这份邀请已经过期。」。**API 侧等价已验证**：造一份过期邀请（把 `org_invitations.expires_at` 改到 1 天前）→ 未登录 `GET /api/v1/invitations/{token}` → **200**，`status: "expired"`；`POST /api/v1/invitations/{token}/accept` → **409**，`code: "INVITATION_INVALID"`，detail「邀请已过期」。另外：不存在的 token → **404**；已撤销的邀请 → 接受返回 **409 INVITATION_INVALID**（「邀请已失效」） | 未实测（浏览器），API 达成 |
| 14 | 负例：组织 owner 尝试把自己降级 | 400 `VALIDATION_FAILED` | `PATCH /api/v1/organizations/{orgId}/members/{dev-user-id}` 用 dev-user（组织唯一 owner）自己的 token，角色改成 admin → **400**，`code: "VALIDATION_FAILED"`，detail「不能改自己的组织角色」 | 通过 |

## 汇总

- **14 步全部达成**：第 1、3、4、5、7、8、9、12、13 步的**浏览器交互路径未实测**（本机无浏览器），
  但每一步的**后端状态变更都用 API 达成并断言过**，链路本身是通的。
- 第 2、6、10、11、14 步是纯 API/CLI 步骤，**全部实测通过**。
- 负例（12、13、14）全部按期望返回：404 / 409 `INVITATION_INVALID` / 400 `VALIDATION_FAILED`。

## 踩到的坑

0. **验收用的技能目录没有进仓库**：`skills/java-debug/` 与 `skills/java-debug-v2/` 是本次验收
   临时造的（含 `SKILL.md`），只用于喂给 `skillmesh publish`，**未提交**（plan 的 Step 7
   只让提交 3 个文件）。重跑第 6 步前得先自己造一个含 `SKILL.md` 的目录。
1. **Keycloak 的 realm 导入不覆盖已存在的用户**——改了 `realm-skillmesh.json` 之后，
   已经跑过的本机实例里还是没有 `dev-user2`。这就是 `infra/scripts/add-second-user.sh` 存在的理由。
2. **邀请的 `subject` 字段比的是 OIDC 的 `sub`（一串 UUID），不是用户名**。第一次验收时给邀请传了
   `subject: "dev-user2"`，dev-user2 接受时直接 **403「这份邀请不是发给你的」**。
   Web 端从来不传这个字段（`apps/web/src/pages/TeamPage.vue` 只传 `team_id` / `org_role` / `team_role`），
   所以 UI 路径不会踩到；用 API 手工造邀请时不要传 `subject`，或者传真正的 `sub`。
3. **开发库里已经有 17 个历史 Skill**，且 Keycloak 重启后 `sub` 变了、库里多出一批重复的 `users` 行
   （`users` 表按 `oidc_issuer + oidc_subject` 建档，`sub` 一变就是新用户）。
   不影响验收，但本机验收前最好清一下。
4. **CLI 的「已提交审核」分支不填 `SizeBytes`**（`apps/cli/internal/cmd/publish.go`），
   输出恒为「0 字节」。待审队列与审核详情里的文件大小是对的，属显示层问题。
   **已由并发会话修复**：该分支补上了 `SizeBytes: artifact.SizeBytes`。
5. **第一次起 API 时端口 8080 被一个旧构建占着**——那是上一轮会话留下的进程，
   它的 `SecurityConfig` 还没有「邀请公开摘要 permitAll」，`GET /api/v1/invitations/{token}` 返回 401。
   判断「跑的是不是当前代码」的一个好办法：请求这个路径，看是 401（旧构建）还是 404/200（新构建）。
6. **验收期间有另一个会话在同一 worktree 上并发提交**（HEAD 从 `842ccd5` 前进到 `394e600`，
   其中 `6a11ef0` 改了审核放行权的角色判定口径：approve/reject 改判**团队**角色）。
   因此本次验收最终是在 **HEAD = `394e600`** 的构建上重跑了一遍闭环（重新起 API、重新发布
   `java-debug-v2`、重新过审），第 5/9/12 步的结果以这一轮为准。

## 留给人工在浏览器里验证的清单

按顺序做，每步应当看到：

1. `http://127.0.0.1:5174/login` → 点「登录」→ Keycloak 里用 dev-user / dev → 回到控制台，顶栏显示「Dev User」。
2. `http://127.0.0.1:5174/team` → 选组织 acme（**不要选团队**）→ 成员表里找到「Dev User Two」，
   角色下拉改成 publisher → 角色列显示 publisher。
   > 选了团队的话，下拉改的是**团队角色**（管审核放行权），这一步要的是**组织角色**
   > （管发布权）——发布到团队看的是组织角色，两层是分开的。
3. 同一页 → 选团队与角色 → 点「生成邀请链接」→ 出现 `http://127.0.0.1:5174/invite/<token>`。
4. 无痕窗口打开该链接 → 显示「加入团队」与组织/团队/角色 → 点「登录后接受」并登录 dev-user2 / dev2
   → 回到页面点「接受邀请」→ 出现「已加入，去团队页看看。」
5. `http://127.0.0.1:5174/reviews` → 列表里能看到 dev-user2 提交的待审版本（含扫描结论）。
6. 点进详情 → 看到 SKILL.md 正文与文件级差异 → 点「通过」→ 提示已通过，状态标签变 published。
7. 用 dev-user2 登录 → `http://127.0.0.1:5174/reviews` → 空态「没有待审的版本」。
8. 用 dev-user2 打开一个过期邀请链接 → 页面提示「这份邀请已经过期。」
