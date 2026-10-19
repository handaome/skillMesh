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

# 从 Git 仓库安装（来源必须是远程地址）
./skillmesh.exe source resolve git@github.com:owner/agent-skills
./skillmesh.exe add git@github.com:owner/agent-skills.git \
  --path skills/java-debug --agent claude-code --scope user --install

# 从注册中心安装（SkillMesh 云端已发布的版本）
./skillmesh.exe install <skill-id> --agent claude-code --scope user

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

### 从 Git 仓库装一个 Skill：两步

#### 第一步 `source resolve` —— 先看一眼仓库里有什么

```bash
./skillmesh.exe source resolve git@github.com:owner/agent-skills
```

**只读，不装任何东西。** 浅克隆到临时目录 → 扫描出所有含 `SKILL.md` 的子目录 → 打印后删掉临时目录。

```text
仓库 git@github.com:owner/agent-skills
commit 9eb54157e27447d184ca40d5b189c1d8

子目录            SKILL.md
skills/java-debug    有
skills/redis-cache   有
```
```

**它的用途是让你知道下一步 `--path` 该填什么。** 一个仓库里通常有好几个 Skill 目录，不先看一眼只能一个个试。

加 `--ref` 可以指定分支、标签或 commit：

```bash
./skillmesh.exe source resolve git@github.com:owner/agent-skills --ref v1.2.0
```

#### 第二步 `add` —— 登记并装上

```bash
./skillmesh.exe add git@github.com:owner/agent-skills.git \
  --path skills/java-debug --agent claude-code --scope user --install
```

| 参数 | 作用 |
| --- | --- |
| `<repo-url>` | 仓库地址。**必须是远程地址**——`NormalizeURL` 按设计拒绝 `file://` 和本地路径。支持 `git@owner:repo.git`、`ssh://`、`https://` 三种形式，**不能混写**（`git@` 形式不能带 `https://` 前缀） |
| `--path` | 仓库里哪个子目录是 Skill。**只有候选恰好一个时才能省略**；有多个会报错并列出候选让你挑 |
| `--agent` | 装给哪个 Agent。`claude-code` → `~/.claude/skills/`，`codex` → `~/.codex/skills/` |
| `--scope` | `user` 装到用户级（你所有项目都能用）；`project` 装到当前项目里（提交进版本控制，团队共享） |
| `--ref` | 分支、标签或 commit。不填用仓库默认分支 |
| `--install` | **关键开关**。不加只"登记"来源，不碰 Agent 目录；加了才真正安装 |
| `--replace` | 目标位置已有同名目录时强制覆盖（见下方"同名目录不覆盖"） |
| `--credential-ref` | 私有仓库用本机凭据管理器里的凭据引用，**不接受明文令牌** |

执行的步骤：

```text
clone 到临时目录
  → 校验：必须有 SKILL.md / 没有逃出目录的符号链接 / 文件数和大小不超限
  → 原子安装：先复制到暂存目录，校验过了再切换到目标路径
  → 写 .skillmesh-install 标记（表示这个目录归 SkillMesh 管）
  → 写 skillmesh.lock.yaml（记下 commit、路径、来源）
```

输出：

```text
来源 git@github.com:owner/agent-skills.git
子目录 skills/java-debug
commit 9eb54157e27447d184ca40d5b189c1d8
Skill java-debug
已安装到 C:\Users\23929\.claude\skills\java-debug
锁文件 skillmesh.lock.yaml
```

**原子安装的意义**：装到一半失败不会毁掉你原来的版本——旧版本先被备份，新版本校验通过才切换，切换失败自动回滚。

#### 同名目录默认不覆盖

目标位置已经有同名目录、但它不是 SkillMesh 装的（没有 `.skillmesh-install` 标记），CLI 会拒绝：

```text
目标已存在且不受 SkillMesh 管理: C:\Users\23929\.claude\skills\java-debug（用 --replace 覆盖）
```

这是防止误删你手写的 Skill。确认要覆盖就加 `--replace`。反过来，`uninstall` 也不会删不受管理的目录，除非加 `--force`。

### 从注册中心装一个 Skill

```bash
./skillmesh.exe install <skill-id> --agent claude-code --scope user
```

从 SkillMesh 云端拉已发布的版本。`--version` 指定版本（不填取最新已发布的），`--dry-run` 只演练不落盘。

CI 里没有浏览器也没有凭据管理器，用环境变量传令牌：

```bash
export SKILLMESH_TOKEN=<access-token>
./skillmesh.exe install <skill-id> --locked
```

`--locked` 会校验远端解析结果与 `skillmesh.lock.yaml` 是否一致，不一致直接失败，防止构建环境静默漂移。

### 本地项目状态

| 命令 | 作用 |
| --- | --- |
| `list` | 列出本项目锁文件里登记的安装：Skill、Agent、commit、安装路径、是否受管 |
| `status` | 同上，但输出 JSON（`status <name>` 只看某一个），适合脚本消费 |
| `update --check` | 检查远端有没有新 commit，**只报告不改动** |
| `update --all` | 应用全部更新 |
| `update <name>` | 只更新某一个 |
| `lock refresh` | 重新解析来源并刷新锁文件 |
| `uninstall <name>` | 移除安装内容与锁文件记录 |

`update` 默认只报不装——要动手得显式给 `--all` 或指定 Skill 名字。

### 诊断

```bash
./skillmesh.exe doctor        # 服务端地址、配置目录、git 是否可用、登录状态、各 Agent 目录是否存在
./skillmesh.exe agents detect  # 各 Agent 的用户级/项目级目录表格
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

## 受限网络下访问 GitHub

本机到 GitHub 的连通性是**分主机**的，不是简单的"能"或"不能"。2026-10-19 实测：

| 主机 / 端口 | 状态 | 备注 |
| --- | --- | --- |
| `github.com:443` | ❌ 不通 | `git clone https://github.com/...` 必然失败 |
| `github.com:22` | ✅ 通 | SSH 通道可用，缺的只是密钥 |
| `github.com:9418` | ❌ 不通 | git:// 协议 |
| `api.github.com` | ✅ 200 | REST API 可用 |
| `codeload.github.com` | ✅ 200 | 仓库归档 tarball 可用 |
| `raw.githubusercontent.com` | ❌ 不通 | 原始文件直取不可用 |
| `objects.githubusercontent.com` | ✅ 通 | release 资源 CDN |

结论：**SSH 和 API 两条路是通的，网页和 https clone 不通**。下面三条路径任选其一。

### 路径 A：配 git 代理（最省事）

代理客户端（Clash for Windows / v2rayN）启动后：

```powershell
# 全局生效
git config --global http.proxy  http://127.0.0.1:7890
git config --global https.proxy http://127.0.0.1:7890
```

想**只让 GitHub 走代理**，不影响其他流量（推荐）：

```powershell
git config --global http.https://github.com.proxy http://127.0.0.1:7890
```

撤销：

```powershell
git config --global --unset http.proxy
git config --global --unset https.proxy
git config --global --unset http.https://github.com.proxy
```

端口按你实际用的客户端：Clash for Windows 默认 **7890**（HTTP）/ 7891（SOCKS），v2rayN 常见 **10809**（HTTP）/ 10808（SOCKS）。`~/.m2/settings.xml` 里那两个 `active=false` 的代理条目指向 `127.0.0.1:7890` 和 `33210`，说明之前用的是 Clash。

配完直接用 https 形式的地址：

```powershell
cd D:\dev\MyProject\SkillMesh\apps\cli
.\skillmesh.exe source resolve https://github.com/phuryn/pm-skills
```

### 路径 B：SSH 密钥（一次配置，长期免代理）

好处是代理不开也能 clone。仓库里已备好 `~/.ssh/id_ed25519`（ed25519，无口令，注释 `skillmesh-dev`）——**私钥留在本机，权限 600，只把公钥注册到 GitHub**。

注册公钥需要一个带 `admin:public_key` 权限的 Personal Access Token。**建 PAT 本身要走 github.com 的网页，所以这一步仍然需要把代理开起来**——只有这一次。

**建 PAT**：

1. 代理开着的情况下访问 <https://github.com/settings/tokens>
2. 页面顶部点 **Generate new token** → **Generate new token (classic)**
3. **Note** 填 `skillmesh-dev`，**Expiration** 按需设（默认 30 天，到期后重注册）
4. 勾选权限里的 **`admin:public_key`**（描述是 "Full control of public keys"）——只需要这一个
5. 点 **Generate token**，**立刻复制** `ghp_...`（只显示这一次）

> 用 fine-grained token 也行：<https://github.com/settings/personal-access-tokens/new> → Permissions → Account permissions → **SSH public keys** → **Read and write**。

**注册公钥**（令牌只从环境变量读，不落盘、不进 shell 历史）：

```powershell
cd D:\dev\MyProject\SkillMesh
$env:GITHUB_TOKEN = "ghp_你的令牌"
.\infra\scripts\register-github-ssh-key.ps1
```

Git Bash 等价写法：

```bash
export GITHUB_TOKEN=ghp_你的令牌
bash infra/scripts/register-github-ssh-key.sh
```

脚本会 POST 到 `api.github.com/user/keys`，然后列出账号当前的密钥列表。已注册过会提示 `already_exists` 而不是报错。

**验证**：

```powershell
ssh -T git@github.com
# 期望：Hi <你的用户名>! You've successfully authenticated, but GitHub does not provide shell access.
```

**用起来**——CLI 走 SSH 地址：

```powershell
cd D:\dev\MyProject\SkillMesh\apps\cli
.\skillmesh.exe source resolve git@github.com:phuryn/pm-skills
.\skillmesh.exe add git@github.com:phuryn/pm-skills.git --path skills/xxx --agent claude-code --install
```

`gitx.NormalizeURL` 已支持 `git@owner:repo.git` 形式（也支持 `ssh://`）。注意 SSH 地址**不能带 `https://` 前缀**，两种形式不能混写。

### 路径 C：codeload 归档（未实现）

`codeload.github.com` 是通的，所以理论上可以在 git clone 失败时回退到 `https://codeload.github.com/{owner}/{repo}/tar.gz/{ref}` 取快照。**这还没做**，而且会丢掉 commit 粒度（只能按 ref 取树，commit SHA 得另外从 API 拿），属于产品特性而非 bug 修复。

### 故障排查

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `Permission denied (publickey)` | 公钥没注册，或 ssh 没找到私钥 | `ssh -v -T git@github.com`，看有没有 `Will attempt key: ... id_ed25519` |
| 上面那行没有出现 | 私钥路径不对 | 确认在 `C:\Users\23929\.ssh\id_ed25519`；或 `ssh -i <路径>` 指定 |
| `Host key verification failed` | known_hosts 里没有 github.com | `ssh-keyscan github.com >> $env:USERPROFILE\.ssh\known_hosts` |
| `UNPROTECTED PRIVATE KEY FILE` | 私钥 ACL 对其他用户开放 | 见下方 ACL 收紧命令 |
| `Could not read from remote repository` + `Connection reset` | 走的是 https，443 不通 | 换 `git@github.com:owner/repo.git`，或配代理（路径 A） |
| `Repository not found`（明明存在） | 同上，https 走了不通的链路 | 同上 |
| API 返回 401 | PAT 无效或过期 | 重建 PAT |
| API 返回 403 且提示缺 scope | PAT 没勾 `admin:public_key` | 重建 PAT，注意勾对权限 |
| API 返回 422 `already_exists` | 该公钥已注册 | 脚本会识别为正常情况，可直接继续 |

私钥 ACL 收紧（Windows OpenSSH 拒绝权限过开的私钥）：

```powershell
icacls "$env:USERPROFILE\.ssh\id_ed25519" /inheritance:r /grant:r "$env:USERNAME:(R)"
```

本机当前状态已经是对的（`SYSTEM` / `Administrators` / 本人，无 `Everyone` / `Users` 组），Git Bash 的 ssh 和 `C:\Windows\System32\OpenSSH\ssh.exe` 都正常认这把钥匙。

### 现状

本机已生成 `~/.ssh/id_ed25519`，**但公钥还没注册到 GitHub 账号**（注册需要 PAT，需要先开代理建 PAT）。所以目前 `git clone https://github.com/...` 仍然会失败，CLI 会给出带代理提示的错误（见 `gitx` 的网络失败处理）。

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
6. **github.com 的 443 不通，但 22（SSH）和 api.github.com 通**。完整的三条应对路径、建 PAT 步骤与故障排查表见 [受限网络下访问 GitHub](#受限网络下访问-github)。

## MVP 范围

v1 做：本地基础设施、数据库迁移、OIDC 鉴权、Skill 元数据与搜索、MinIO 制品存储与不可变版本、授权下载、CLI 全套命令、前端搜索与详情两页。

v1 不做（推迟到 v2，表结构已按最终设计建好）：本地同步引擎与 daemon、发布审批流、团队角色矩阵、审计事件、制品签名、Cursor 适配器、`rollback` 命令（用 `install --version` 替代）。
