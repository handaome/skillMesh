# SkillMesh MVP v1 设计（增量 spec）

> 日期：2026-10-19 | 状态：已确认 | 上游文档：[设计文档](../../doc/设计文档.md) V1.0、[详细设计](../../doc/详细设计.md)
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
| 3 | API：`GET /me`、`GET /skills`、`GET /skills/{id}`、`GET /skills/{id}/versions`、`POST /skills`、`POST /skills/{id}/versions`（上传制品）、`GET /artifacts/{id}/download` | 带 token 的 curl 跑通全链路；上传后 sha256 与 DB 记录一致；下载不返回公开 URL |
| 4 | CLI：`login` `search` `info` `source resolve` `add` `install` `list` `status` `uninstall` `doctor` `update --check` | 从 Git 仓库指定子目录安装进 `~/.claude/skills/`；lock 文件记录 commit + sha256；`update --check` 能报出上游新 commit |
| 5 | Web：搜索页 + 详情页（含版本列表） | 能搜到并查看 API 创建的 Skill 及其版本 |

## 4. 第一天就做、不可推迟的约束

这三条后补会破坏已有数据或已发布制品，因此必须在切片 2/3 完成：

1. **版本不可变**：`skill_versions` 的 `(skill_id, version)` 唯一约束 + 制品内容 SHA-256 校验，在首次建表时就位。
2. **制品下载必须授权**：`GET /artifacts/{id}/download` 先鉴权再流式返回，不返回带长期权限的公开 URL（详细设计 §12）。
3. **凭据不入库、不入日志**：数据库只存 Git 凭据的**引用**；令牌、密码只存在于 gitignore 的 `.env` / `application-local.yml` 与本机环境变量。

## 5. 推迟到 v2 的范围

明确不在 MVP v1：

- 本地同步引擎：daemon / `watch` / changeset 双向合并 / 离线队列 / 冲突解决
- 发布审批流：`submit` → `review` → `publish` 状态机（v1 的版本创建即发布）
- 团队角色矩阵与组织/团队管理（v1 只有单用户 + 一个个人空间）
- 审计事件全量记录
- 制品签名与供应链校验
- Cursor 适配器
- `rollback` 命令——v1 用 `install <skill>@<version>` 固定版本替代
- 服务端 `POST /sources/resolve` 与 `POST /sources/check-updates`——v1 的 `source resolve` 与 `update --check` 完全在 CLI 本地完成（clone 到临时目录探测 `SKILL.md`、`git ls-remote` 取 ref）

## 6. 实现要点

### CLI 与 Git

- `update --check` 用 `git ls-remote` 取远端 ref，不做完整 clone；只有 `add` / `install` / `update` 才 clone 到临时目录。
- 引入 go-git 或纯 exec 系统 git **以 exec 为主**：系统 git 已在，go-git 只在需要无 git 环境时才值得。MVP 不引入。
- 安装前校验：路径穿越（`..`、绝对路径、符号链接逃逸）、单文件大小上限、总大小上限、`SKILL.md` 必须存在。
- 安装用「临时目录 → 校验 → 原子替换」，失败不破坏当前版本。

### 制品存储（MinIO）

- bucket：`skillmesh-artifacts`；object key：`{skill_id}/{version}/{sha256}.tar.gz`。
- 数据库存 `artifact_key` + `sha256` + `size_bytes`，key 不可复用覆盖。

### 配置与凭据

- 仓库内只提交 `.env.example`；真实值在 `.env`（已 gitignore）。
- API 通过 Spring Profile `local` 读取 `.env`；`minio.endpoint`、`keycloak.issuer-uri`、`datasource.url` 全部外部化。

### API 约定

- 前缀 `/api/v1`，JSON 字段 `snake_case`，错误统一 problem+json（详细设计 §0.2、§2.1）。
- 列表接口用 `limit` + `cursor`。

## 7. 风险

| 风险 | 缓解 |
| --- | --- |
| 远程 PG 是共享服务器，DDL 会落在 `skill_mesh` 库 | 库当前为空且为专用库名；第一次 Flyway 前先人工确认 |
| Keycloak / MinIO 首轮下载体积大（合计约 500MB） | 只下载一次，脚本固化到 `infra/` |
| Go 为 386 32 位，跨平台分发受限 | MVP 只在本机开发，不做交叉编译发布；发版时换 amd64/arm64 |
| 上游详细设计的 DDL 未在 PG 18 实测 | 切片 2 的验收即为此项实测 |
