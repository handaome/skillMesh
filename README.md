# SkillMesh

Agent Skill 的协作与分发平台。中心化管理、客户端拉取、本地执行：云端管元数据/权限/版本/制品，本地负责发现、校验与安装，Agent 继续读自己的原生 Skill 目录。

## 文档

| 文档 | 内容 |
| --- | --- |
| [CLI 使用手册](docs/cli-使用手册.md) | 从 Git 安装、发布到云端、更新与差异、同步与冲突、回收站、诊断 |
| [团队治理与安全](docs/团队治理.md) | 组织与团队、角色与可见性、发布审核、安装登记、审计、制品签名与可疑内容扫描 |
| [团队控制台使用手册](docs/团队控制台.md) | 浏览器登录、邀请入组、发布到团队、审核放行、安装与排错 |
| [部署、备份与排障](docs/部署与排障.md) | 备份与恢复验证、身份源、生产前清单、受限网络下访问 GitHub、端口 |
| [数据库 ER 图](docs/数据库ER图.md) | 20 张表的实体关系图（Mermaid），按身份组织、Skill 与制品、同步与安装、审计四个域拆开 |
| [设计文档](docs/设计文档.md) | 产品目标、用户场景、功能范围、同步引擎设计、安全治理、实施计划与验收标准 |
| [详细设计](docs/详细设计.md) | 数据库 DDL、API 契约、CLI 命令与配置、部署说明 |
| [审计与修复报告](docs/审计与修复报告.md) | 多轮审计的发现、处置与仍未做的项 |
| [对标分析：skills-manager](docs/对标分析-skills-manager.md) | 与桌面端 Skills 管理器的能力差异，以及分级借鉴清单 |

## 目录结构

```text
apps/api/     Spring Boot 3.5 · Java 21 · JPA · Flyway · PostgreSQL · MinIO SDK · Keycloak OIDC
apps/cli/     Go · cobra · OIDC 授权码+PKCE · 调系统 git
apps/web/     Vue 3 · Vite · TypeScript
infra/        本机服务的准备/启动/停止脚本与 realm 定义
docs/        设计文档、详细设计与使用手册
docker-compose.yml/ PostgreSQL + MinIO + Keycloak + API + 前端的容器编排
```

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | Spring Boot 3.5.3 要求 |
| Go | 1.24+ | 用系统 go 即可 |
| Node / pnpm | 24 / 10 | 前端 |
| git | 2.x | CLI 通过系统 git 解析来源 |
| unzip | 任意 | 启动脚本解压 Keycloak |
| Docker | 24+ | 只在走 compose 路线时需要 |

Go 拉模块走 `GOPROXY=https://goproxy.cn,direct`——直连 `proxy.golang.org` / `sum.golang.org` 在国内会超时。本机已用 `go env -w GOPROXY=https://goproxy.cn,direct` 写进全局配置；换机器时重设该值即可（`infra/scripts/prepare.sh` 内部也兜了同样的默认值）。

## 快速开始

### 1. 配置凭据

```bash
cp .env.example .env
# 编辑 .env，填入 SM_DB_PASSWORD
```

### 2. 启动基础设施

```bash
bash infra/scripts/start.sh         # MinIO 127.0.0.1:9100（控制台 9101）+ Keycloak 127.0.0.1:8180/kc
bash infra/scripts/smoke.sh         # 健康检查，四项全 OK 才继续
```

停止：`bash infra/scripts/stop.sh`（`stop.sh minio` / `stop.sh keycloak` 可只停一个）。

备份：`bash infra/scripts/backup.sh <备份目录>`——应用起不来时也能跑（绕开 Flyway 校验），
定期备份用 cron 调它，见[部署文档](docs/部署与排障.md)。

> 整套用容器跑（自带 PostgreSQL）见 [Docker Compose 路线](docs/部署与排障.md#docker-compose-路线)。两条路线抢同一批端口，不能同时开。

Keycloak 首次启动会导入 `infra/keycloak/realm-skillmesh.json`，其中含 `dev-user` / `dev` 与两个客户端：

- `skillmesh-cli`——public client，标准流程 + PKCE S256，供 CLI 登录
- `skillmesh-test`——开放 direct grant，仅供自动化测试取 token

### 3. 启动 API

```bash
cd apps/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

首次启动时 Flyway 会在数据库建表。API 监听 `127.0.0.1:8080`，所有接口挂在 `/api/v1` 下。

### 4. 构建 CLI

需要 Go 1.26+。

```bash
# Linux / macOS
cd apps/cli && go build -o skillmesh ./cmd/skillmesh

# Windows
cd apps/cli && go build -o skillmesh.exe ./cmd/skillmesh
```

也可以用交叉编译：`GOOS=linux GOARCH=amd64 go build -o skillmesh ./cmd/skillmesh`。

### 5. 启动前端

```bash
cd apps/web && pnpm install && pnpm dev   # http://127.0.0.1:5174
```

前端没有登录页，把 CLI 登录拿到的 access token 粘到顶部输入框即可。

## 端口

| 服务 | 地址 | 备注 |
| --- | --- | --- |
| API | 127.0.0.1:8080 | |
| MinIO | 127.0.0.1:9100 | 控制台 9101 |
| Keycloak | 127.0.0.1:8180 | relative path `/kc` |
| 前端 dev | 127.0.0.1:5174 |  |

所有本地服务只绑回环地址，不监听 `0.0.0.0`。

## MVP 范围

**明确未做**（[设计文档 §4](docs/设计文档.md) 有逐条说明）：文件系统监听只覆盖 Agent 根目录与每个 Skill 目录，更深层级靠所在 Skill 的摘要变化体现；多设备冲突可视化、制品签名策略与供应链证明（证书链）、SSO/LDAP、组织与团队之间的同步。
