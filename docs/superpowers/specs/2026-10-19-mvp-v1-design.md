# SkillMesh MVP v1 设计（增量 spec）

> 日期：2026-10-19 | 状态：已确认 | 上游文档：[设计文档](../../设计文档.md) V1.0、[详细设计](../../详细设计.md)
>
> 本文件**不重复**上游两份文档的领域设计。它只记录 MVP v1 的范围裁剪、技术落地形态与环境事实；凡本文未覆盖的，一律以上游文档为准。

## 1. 环境事实（已实测）

| 项 | 值 | 验证方式 |
| --- | --- | --- |
| JDK | OpenJDK 21.0.2 | `java -version` |
| Go | go1.24.3 windows/386 | `go version` |
| Node / pnpm | v24.12.0 / 10.13.1 | `node -v` / `pnpm -v` |
| Maven | 3.9.9（wrapper，`~/.m2/wrapper/dists` 已有缓存） | `ls ~/.m2/wrapper/dists` |
| Docker | **未安装** | `docker -v` 无此命令 |
| PostgreSQL | 远程 `123.56.146.58:5432/skill_mesh`，PostgreSQL 18.4 (Debian) | TCP 可达 + `psycopg` 连接成功 |
| 数据库权限 | `gis_project` 为 superuser，public schema 可建表，库当前为空（0 张表） | 只读查询 + `BEGIN; CREATE EXTENSION pgcrypto; ROLLBACK;` |
| UUID 函数 | `gen_random_uuid()`、`uuidv7()` 均可用（PG18 内置） | SELECT 实测 |

**开发期基础设施**：本机无 Docker，Keycloak 与 MinIO 以**原生进程**运行，脚本提交在 `infra/`；`docker-compose.yml` 仍提交，供生产部署使用。

## 2. 仓库结构

在详细设计 0.3 的基础上，MVP 阶段收敛为：

```text
skillmesh/
├── apps/
│   ├── api/            # Spring Boot 3 · Java 21 · Flyway · PG · MinIO SDK · Keycloak resource server
│   ├── cli/            # Go + cobra · OIDC 授权码+PKCE · 调系统 git
│   └── web/            # Vue 3 + Vite + TypeScript
├── api-spec/openapi.yaml
├── db/migrations/V1__init.sql      # 详细设计 1.2 的 DDL 原样落地
├── infra/                          # MinIO / Keycloak 启动脚本 + Keycloak realm 导出
├── doc/                            # 已有的两份设计文档
└── docs/superpowers/
```

`adapters/` 不单独成目录：MVP 阶段适配器逻辑放在 `apps/cli` 内（claude-code / codex 两个小函数），等 Cursor 等第三个适配器进入时再抽目录——避免只有一个实现的抽象层。

## 3. 五步垂直切片

顺序即实施顺序。每步必须可独立验证，独立跑通后才进入下一步。

| # | 交付物 | 验收标准 |
| --- | --- | --- |
| 1 | `infra/`：MinIO（`:9000` / 控制台 `:9001`）、Keycloak（`:8180`）启动脚本；realm 导出文件含 realm、client、用户、scope | MinIO 控制台可登录；Keycloak 能签发 access token，claims 含 subject 与组织/角色 |
| 2 | `db/migrations/V1__init.sql` + Flyway 配置 | 远程 `skill_mesh` 库建表成功，Flyway schema history 记录 `V1` |
| 3 | API（全部挂在 `/api/v1` 下，详细设计 §2.1）：`GET /me`、`GET /skills`、`GET /skills/{id}`、`GET /skills/{id}/versions`、`POST /skills`、`POST /skills/{id}/versions`（上传制品）、`GET /artifacts/{id}/download` | 带 token 的 curl 跑通全链路；上传后 sha256 与 DB 记录一致；下载不返回公开 URL |
| 4 | CLI：`login` `search` `info` `source resolve` `add` `install` `list` `status` `uninstall` `doctor` `agents detect` `update --check` `lock refresh`；适配器 claude-code + codex | `add <url> --path <subdir> --install --agent claude-code` 装进 `~/.claude/skills/`；`--agent codex` 装进 `~/.codex/skills/`（或 `CODEX_HOME/skills`）；项目级 `skillmesh.lock.yaml` 记录 `resolved_commit` + `sha256`；`update --check` 能报出上游新 commit；`install --locked` 在 lock 不匹配时失败 |
| 5 | Web：搜索页 + 详情页（含版本列表） | 能搜到并查看 API 创建的 Skill 及其版本 |

## 4. 第一天就做、不可推迟的约束

这三条后补会破坏已有数据或已发布制品，因此必须在切片 2/3 完成：

1. **版本不可变**：`skill_versions` 的 `(skill_id, version)` 唯一约束 + 制品内容 SHA-256 校验，在首次建表时就位。
2. **制品下载必须授权**：`GET /api/v1/artifacts/{id}/download` 先鉴权再流式返回，不返回带长期权限的公开 URL（详细设计 §12）。
3. **凭据不入库、不入日志**：数据库只存 Git 凭据的**引用**；服务端凭据只存在于 gitignore 的 `.env` / `application-local.yml`，CLI 凭据交给系统 Credential Manager（详细设计 §3.1.1）。

## 5. 推迟到 v2 的范围

明确不在 MVP v1。标注「**有意偏离**」的条目是本 spec 与详细设计不一致的地方，v2 必须回归：

- 本地同步引擎：daemon / `watch` / changeset 双向合并 / 离线队列 / 冲突解决
- **有意偏离**：发布审批流 `submit` → `review` → `publish` 状态机（详细设计 §10.1）。v1 中 `POST /skills/{id}/versions` 创建即 `published`，跳过 `draft`/`scanning`/`pending_review`。v2 回归完整状态机，**表结构在 v1 就按最终状态机建**，避免事后改列
- 团队角色矩阵与组织/团队管理（v1 只有单用户 + 一个个人空间）
- 审计事件全量记录（v1 不写 `audit_events`）
- 制品签名与供应链校验
- Cursor 适配器（v1 做 claude-code + codex 两个，详细设计 §4 首期要求）
- `rollback` 命令——v1 用 `install --version` 固定版本替代
- 服务端 `POST /sources/resolve` 与 `POST /sources/check-updates`——v1 的 `source resolve` 与 `update --check` 完全在 CLI 本地完成（clone 到临时目录探测 `SKILL.md`、`git ls-remote` 取 ref）。详细设计 §3.5 允许「本地直接 clone 模式」，故非偏离
- 风险扫描阻断安装（详细设计 §3.5 步骤 4）——v1 只做路径穿越/大小/`SKILL.md` 存在性校验，扫描引擎进 v2

## 6. 实现要点

### CLI 与 Git

- `update --check` 用 `git ls-remote` 取远端 ref，不做完整 clone；只有 `add` / `install` / `update` 才 clone 到临时目录。
- 引入 go-git 或纯 exec 系统 git **以 exec 为主**：系统 git 已在，go-git 只在需要无 git 环境时才值得。MVP 不引入。
- `add` 只登记来源，`--install` 才写入 Agent 目录（详细设计 §3.5）。`--scope` 默认 `project`，检测不到项目根时提示用户。
- 安装前校验：路径穿越（`..`、绝对路径、符号链接逃逸）、单文件大小上限、总大小上限、`SKILL.md` 必须存在。
- 安装用「临时目录 → 校验 → 原子替换」，失败不破坏当前版本；目标目录已有同名 Skill 且不受 SkillMesh 管理时默认不覆盖，需 `--replace`。
- Agent 目录路径全部来自配置（`config.yaml` 的 `agents.*.user_skills_dir`），不在代码里硬编码（附录 A.8）。

### 配置与凭据

- **服务端**：仓库内只提交 `.env.example`；真实值在 `.env`（已 gitignore）。`datasource.url`、`minio.*`、`keycloak.issuer-uri` 全部外部化到环境变量。
- **CLI**：配置在 `%APPDATA%\SkillMesh\config.yaml`，缓存 `%LOCALAPPDATA%\SkillMesh\Cache`（详细设计 §3.1.1）。`config.yaml` 只存非敏感设置，access/refresh token 存 Windows Credential Manager；`--credential-ref` 指向凭据引用，不接受明文 token。

### 项目配置与锁文件

按详细设计 §3.10，项目根目录：

- `skillmesh.yaml`——声明式：来源、`ref_type`/`ref`、`install.agents`、`install.scope`、`update_policy`。`install`/`update --all` 读取它。
- `skillmesh.lock.yaml`——解析结果：`source_id`、`repository`、`path`、`ref_type`、`ref`、`resolved_commit`、`version`、`sha256`、`agents`、`resolved_at`。
- `install --locked` 在远端解析结果与 lock 不符时失败，用于 CI 防漂移。

### 制品存储（MinIO）

- bucket：`skillmesh-artifacts`；object key：`{skill_id}/{version}/{sha256}.tar.gz`。
- 数据库存 `artifact_key` + `sha256` + `size_bytes`，key 不可复用覆盖。
- **临时态与正式态分离**（附录 A.2）：`POST /uploads/{upload_id}/complete` 收尾时把临时对象搬到按摘要寻址的正式 key；临时对象配置自动过期。

### API 契约

- `api-spec/openapi.yaml` 是**手写契约**，从详细设计 §2.3 原样抽出，不从代码生成（附录 A.1）。变更时同步更新 Go 客户端模型并跑 lint。
- 前缀 `/api/v1`，JSON 字段 `snake_case`；错误统一 problem+json，字段 `type`/`title`/`status`/`code`/`detail`/`request_id`/`retryable`/`field_errors`；客户端只按 `code` 分支，不解析 `detail`。
- 创建返回 `201` + `Location`；列表用 `limit` + `cursor`；无权限的私有资源按策略返回 `404` 而非 `403`。

## 7. 风险

| 风险 | 缓解 |
| --- | --- |
| 远程 PG 是共享服务器，DDL 会落在 `skill_mesh` 库 | 库当前为空且为专用库名；第一次 Flyway 前先人工确认 |
| Keycloak / MinIO 首轮下载体积大（合计约 500MB） | 只下载一次，脚本固化到 `infra/` |
| Go 为 386 32 位，跨平台分发受限 | MVP 只在本机开发，不做交叉编译发布；发版时换 amd64/arm64 |
| 上游详细设计的 DDL 未在 PG 18 实测 | 切片 2 的验收即为此项实测 |

## 8. 与详细设计 §4.5「最小可交付范围」的覆盖对照

| §4.5 条目 | v1 状态 | 说明 |
| --- | --- | --- |
| 1. PostgreSQL DDL 与 Flyway migration | ✅ 切片 2 | |
| 2. OIDC 登录、`GET /me`、Skill 搜索/详情和版本查询 | ✅ 切片 1 + 3 | 真 OIDC，Keycloak 自托管 |
| 3. Git 来源解析、候选 Skill 选择、安装至 Claude Code/Codex | ✅ 切片 4 | 解析在 CLI 本地，服务端接口推迟 |
| 4. 本地安装记录与 `list`/`status`/`doctor` | ⚠️ 部分 | `list`/`status`/`doctor` 在 v1；`installations` 表和登记 API 建表即在，注册上报推迟 |
| 5. 检查更新、差异预览、固定 commit 和回滚 | ⚠️ 部分 | `update --check`、`--locked` 固定 commit 在 v1；差异预览和 `rollback` 推迟 |
| 6. 私有云端备份、changeset 幂等、离线队列和基础冲突记录 | ❌ 推迟 | changeset 相关表建表即在，端点推迟到 v2 |
| 7. 敏感信息扫描、哈希验证、审计事件与最低限度的团队权限 | ⚠️ 部分 | SHA-256 哈希验证在 v1；扫描、审计事件、团队权限推迟 |

**结论**：§4.5 的 7 条里，2 条完整覆盖、4 条部分覆盖、1 条推迟。所有推迟项的表结构在 v1 建表时按最终设计建，避免 v2 改表。

## 9. 身份与用户模型

身份源与业务用户是两套东西，v1 刻意做得很薄。

### 身份源（Keycloak）

开发用的用户在 `infra/keycloak/realm-skillmesh.json` 里静态定义，`start-keycloak.sh` 首次启动时通过 `--import-realm` 导入。这是开发便利，**生产应接企业已有的 OIDC/LDAP/SSO**——SkillMesh 侧只认 issuer + subject，换 IdP 不改代码。

| 账号 | 密码 | 用途 |
| --- | --- | --- |
| `admin` | `admin` | Keycloak 自身的管理员（`KC_BOOTSTRAP_ADMIN_*`） |
| `dev-user` | `dev` | 唯一的 SkillMesh 用户，realm role `skillmesh-user` |

客户端两个：`skillmesh-cli`（public，浏览器授权码 + PKCE S256，CLI `login` 用）和 `skillmesh-test`（public，direct grant，**只给自动化测试**）。后者等价于「知道用户名密码就能换 token」，不能带到生产。

### 业务用户（`users` 表）

**v1 没有「创建用户」这个操作**。`CurrentUserService.provision()` 在第一次调 `GET /api/v1/me` 时按 token 自动建档：

1. 用 `(oidc_issuer, oidc_subject)` 查 `users` 表
2. 查不到就插一行：`display_name` ← token 的 `name` claim（退到 `preferred_username`，再退到 `sub`）、`email` ← token 的 `email` claim、`status` = `'active'`
3. 每次访问更新 `last_login_at`

于是「能通过 Keycloak 认证」就等于「在 SkillMesh 有账号」。v1 没有注册/邀请流程（realm 里 `registrationAllowed: false`），也没有组织/团队概念——`GET /me` 的 `organizations` 恒为空数组。

显式用户管理（邀请、禁用、团队角色分配）属于 v2 的团队治理（见 §5「团队角色矩阵与组织/团队管理」），v1 只保证 `users` 表结构与最终设计一致，v2 不用改表。

### 已知的生产前置项

| 项 | 原因 |
| --- | --- |
| `dev-user` / `dev` | 明文弱口令且静态写在仓库里 |
| `skillmesh-test` 的 direct grant | 只能在测试环境存在 |
| Keycloak 自身 | 生产应换企业 IdP |
| `SM_KEYCLOAK_ADMIN_PASSWORD` | `.env` 中的 Keycloak 管理员口令 |
