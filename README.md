# SkillMesh

Agent Skill 的协作与分发平台。中心化管理、客户端拉取、本地执行：云端管元数据/权限/版本/制品，本地负责发现、校验与安装，Agent 继续读自己的原生 Skill 目录。

- 产品设计：[doc/设计文档.md](doc/设计文档.md)
- 接口与数据库规范：[doc/详细设计.md](doc/详细设计.md)
- MVP 范围裁剪：[docs/superpowers/specs/2026-10-19-mvp-v1-design.md](docs/superpowers/specs/2026-10-19-mvp-v1-design.md)

## 目录结构

```text
apps/api/     Spring Boot 3.5 · Java 21 · JPA · Flyway · PostgreSQL · MinIO SDK · Keycloak OIDC
apps/cli/     Go · cobra · OIDC 授权码+PKCE · 调系统 git
apps/web/     Vue 3 · Vite · TypeScript
db/migrations/ V1__init.sql（19 张表，与设计文档 §1.2 一致）
infra/        本机 MinIO / Keycloak 启动脚本与 realm 定义
api-spec/     OpenAPI 契约（手写，不是从代码生成）
```

## 前置依赖

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | 21 | Spring Boot 3.5.3 要求 |
| Go | 1.24+ | 用系统 go 即可 |
| Node / pnpm | 24 / 10 | 前端 |
| git | 2.x | CLI 通过系统 git 解析来源 |
| unzip | 任意 | 启动脚本解压 Keycloak |

**不需要** Docker：MinIO 与 Keycloak 以本机原生进程运行（MinIO 官方二进制已下架，见下方「已知环境约束」）。

数据库用的是远程 PostgreSQL 18.4，不需要本地安装。

## 快速开始

### 1. 配置凭据

```bash
cp .env.example .env
# 编辑 .env，填入 SM_DB_PASSWORD
```

`.env` 已在 `.gitignore` 里，不会进仓库。

### 2. 启动基础设施

```bash
bash infra/scripts/start-minio.sh      # MinIO      127.0.0.1:9100（控制台 9101）
bash infra/scripts/start-keycloak.sh   # Keycloak   127.0.0.1:8180/kc
bash infra/scripts/smoke.sh            # 健康检查，四项全 OK 才继续
```

停止：`bash infra/scripts/stop-all.sh`

Keycloak 首次启动会导入 `infra/keycloak/realm-skillmesh.json`，其中含 `dev-user` / `dev` 与两个客户端：

- `skillmesh-cli`——public client，标准流程 + PKCE S256，供 CLI 登录
- `skillmesh-test`——开放 direct grant，仅供自动化测试取 token

### 3. 启动 API

```bash
cd apps/api && ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

首次启动时 Flyway 会在数据库建表。API 监听 `127.0.0.1:8080`，所有接口挂在 `/api/v1` 下。

### 4. 构建 CLI

```bash
cd apps/cli && go build -o skillmesh.exe ./cmd/skillmesh
```

### 5. 启动前端

```bash
cd apps/web && pnpm install && pnpm dev   # http://127.0.0.1:5174
```

前端没有登录页，把 CLI 登录拿到的 access token 粘到顶部输入框即可。

## CLI 用法

```bash
# 登录（浏览器授权码 + PKCE，令牌存进系统凭据管理器）
./skillmesh.exe login --issuer http://127.0.0.1:8180/kc/realms/skillmesh

# 搜索与查看
./skillmesh.exe search java
./skillmesh.exe info <skill-id>

# 从 Git 仓库安装（来源必须是远程 https/ssh）
./skillmesh.exe source resolve https://github.com/owner/agent-skills
./skillmesh.exe add https://github.com/owner/agent-skills.git \
  --path skills/java-debug --agent claude-code --scope user --install

# 本地项目状态
./skillmesh.exe list
./skillmesh.exe status
./skillmesh.exe update --check
./skillmesh.exe lock refresh
./skillmesh.exe uninstall java-debug

# 诊断
./skillmesh.exe doctor
./skillmesh.exe agents detect
```

CI 里没有浏览器也没有凭据管理器，用环境变量传令牌：

```bash
export SKILLMESH_TOKEN=<access-token>
./skillmesh.exe install <skill-id> --locked
```

`--locked` 会校验远端解析结果与 `skillmesh.lock.yaml` 是否一致，不一致直接失败，防止构建环境静默漂移。

配置在 `%APPDATA%\SkillMesh\config.yaml`（Linux/macOS 走 XDG），Agent 的目录在配置里指定，不在代码里硬编码：

```yaml
agents:
  claude-code:
    enabled: true
    user_skills_dir: ~/.claude/skills
  codex:
    enabled: true
    user_skills_dir: ~/.codex/skills
```

## 用户与身份

身份源（Keycloak）和业务侧（SkillMesh `users` 表）是两套东西，别混。

### Keycloak 侧：身份源

开发用的用户在 [infra/keycloak/realm-skillmesh.json](infra/keycloak/realm-skillmesh.json) 里静态定义，`start-keycloak.sh` 首次启动时通过 `--import-realm` 导入。**这只是开发便利，生产不该这么建**——生产应接企业已有的 OIDC/LDAP/SSO。

| 账号 | 密码 | 用途 |
| --- | --- | --- |
| `admin` | `admin` | Keycloak 自身的管理员（`KC_BOOTSTRAP_ADMIN_*`），用来建 realm、配客户端 |
| `dev-user` | `dev` | 唯一的 SkillMesh 用户，realm role `skillmesh-user` |

两个客户端：

| client | 类型 | 登录方式 | 用途 |
| --- | --- | --- | --- |
| `skillmesh-cli` | public | 浏览器授权码 + **PKCE S256** | CLI `login` 走这个，令牌存进系统凭据管理器 |
| `skillmesh-test` | public | direct grant（用户名密码直取 token） | **只给自动化测试用** |

改 realm JSON 后，`.env` 里的 `SM_KEYCLOAK_TEST_USER` / `SM_KEYCLOAK_TEST_PASSWORD` 要同步改，否则后端测试取不到 token。

### SkillMesh 侧：业务用户

**没有"创建用户"这个操作**。[CurrentUserService.provision()](apps/api/src/main/java/com/skillmesh/api/auth/CurrentUserService.java) 在第一次调 `GET /api/v1/me` 时按 token 自动建档：

```text
用 (oidc_issuer, oidc_subject) 查 users 表 → 查不到就插一行
  ├─ display_name ← token 的 name claim → 退到 preferred_username → 再退到 sub
  ├─ email        ← token 的 email claim
  └─ status       ← 'active'
```

也就是说：**只要能通过 Keycloak 认证，SkillMesh 侧就自动有账号**。v1 没有注册/邀请流程（realm 里 `registrationAllowed: false`），也没有团队/组织概念——`GET /me` 返回的 `organizations` 恒为空数组。

显式的用户管理（邀请、禁用、团队角色分配）要等 v2 的团队治理才有。

### 上生产前必须处理

| 项 | 为什么 |
| --- | --- |
| `dev-user` / `dev` | 明文弱口令，且是静态写死在仓库里的 |
| `skillmesh-test` 客户端的 direct grant | 等价于"知道用户名密码就能换 token"，只能留在测试环境 |
| Keycloak 整体 | 生产应换成企业 IdP，SkillMesh 侧只认 OIDC issuer，代码不用改 |
| `SM_KEYCLOAK_ADMIN_PASSWORD` | `.env` 里的 Keycloak 管理员口令 |

## 测试

```bash
cd apps/api && ./mvnw test        # 17 个用例：真实 Keycloak token、真实 MinIO、真实 PG
cd apps/cli && go test ./...      # CLI 各包
cd apps/web && pnpm test          # 前端组件
```

后端测试连的是**独立的 `skillmesh_test` 库**，与应用的 `skill_mesh` 库不共享 schema，测试里的清表操作碰不到应用数据。

## 端口

| 服务 | 地址 | 备注 |
| --- | --- | --- |
| API | 127.0.0.1:8080 | |
| MinIO | 127.0.0.1:9100 | 控制台 9101；9000/9001 被 SonarQube 占用 |
| Keycloak | 127.0.0.1:8180 | relative path `/kc` |
| 前端 dev | 127.0.0.1:5174 | 5173 已被占用 |

所有本地服务只绑回环地址，不监听 `0.0.0.0`。

## 已知环境约束

这几条是本机实测踩出来的，换机器可能不适用：

1. **MinIO 官方二进制已下架**（社区版 2025-10 停更，`dl.min.io` 全部返回 410）。`infra/scripts/build-minio.sh` 用 Go 工具链从源码构建。
2. **本机默认 Go 是 32 位**，链 amd64 大二进制会 OOM。需要一个 amd64 版 Go：
   ```bash
   curl -fSL -o go.zip https://dl.google.com/go/go1.24.9.windows-amd64.zip
   unzip -q go.zip -d /d/tmp/goamd64
   SKILLMESH_GO_BIN=D:/tmp/goamd64/go/bin/go.exe bash infra/scripts/build-minio.sh
   ```
3. **Git Bash 下 Keycloak 需要 `.cmd` 包装器**。形如 `/kc` 的参数会被 MSYS 当成 Unix 路径转成 `C:\Program Files\Git\kc`，Keycloak 会报 `Specified path is an invalid URI`；`.cmd` 文件还必须纯 ASCII，因为 cmd 按 OEM 码页（GBK）读批处理，中文注释会解出元字符。
4. **JVM 堆要显式压住**。`apps/api/.mvn/jvm.config` 与 surefire `argLine` 都设了 `-Xmx768m`，否则 fork 起来申请 G1 虚拟空间会直接失败。
5. **GitHub 不可达**时 Keycloak 走 Maven Central 的 `org.keycloak:keycloak-quarkus-dist`，Go 模块走 goproxy.cn。
6. **github.com 的 443 不通，但 22（SSH）通**。本机已生成 `~/.ssh/id_ed25519`（无口令，注释 `skillmesh-dev`），把公钥注册到 GitHub 后，git 走 SSH 就能用，不依赖代理：

   PowerShell：

   ```powershell
   cd D:\dev\MyProject\SkillMesh
   $env:GITHUB_TOKEN = "<带 admin:public_key 权限的 PAT>"
   .\infra\scripts\register-github-ssh-key.ps1
   ```

   Git Bash：

   ```bash
   export GITHUB_TOKEN=<带 admin:public_key 权限的 PAT>
   bash infra/scripts/register-github-ssh-key.sh
   ```

   注册后 CLI 用 `git@github.com:owner/repo.git` 形式即可（`NormalizeURL` 已支持 `git@` 写法）。注意建 PAT 和加密钥都要走 github.com 的 web UI，所以**第一次仍然需要把代理开起来**。

   不想用 SSH 的话，开着代理直接配 git 更省事：

   ```powershell
   git config --global http.proxy  http://127.0.0.1:7890
   git config --global https.proxy http://127.0.0.1:7890
   ```

## MVP 范围

v1 做：本地基础设施、数据库迁移、OIDC 鉴权、Skill 元数据与搜索、MinIO 制品存储与不可变版本、授权下载、CLI 全套命令、前端搜索与详情两页。

v1 不做（推迟到 v2，表结构已按最终设计建好）：本地同步引擎与 daemon、发布审批流、团队角色矩阵、审计事件、制品签名、Cursor 适配器、`rollback` 命令（用 `install --version` 替代）。
