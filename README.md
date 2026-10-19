# SkillMesh

Agent Skill 的协作与分发平台。中心化管理、客户端拉取、本地执行：云端管元数据/权限/版本/制品，本地负责发现、校验与安装，Agent 继续读自己的原生 Skill 目录。

- 产品设计：[设计文档](docs/设计文档.md)
- 接口与数据库规范：[详细设计](docs/详细设计.md)
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

# 搜索与查看（search 最后一列是完整 ID，直接喂给 install）
./skillmesh.exe search java
./skillmesh.exe info <skill-id>

# 从 Git 仓库安装（来源必须是远程地址）
./skillmesh.exe source resolve git@github.com:owner/agent-skills
./skillmesh.exe add git@github.com:owner/agent-skills.git \
  --path skills/java-debug --agent claude-code --scope user --install

# 从注册中心安装（SkillMesh 云端已发布的版本）
./skillmesh.exe install <skill-id> --agent claude-code --scope user

# 发布本地 Skill
./skillmesh.exe publish ./skills/java-debug
./skillmesh.exe rollback java-debug --to 1.0.0
./skillmesh.exe restore                    # 换设备：装回个人私有空间

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
| `--all` | 装仓库里的**全部** Skill。与 `--path` 互斥 |
| `--agent` | 装给哪个 Agent。`claude-code` → `~/.claude/skills/`，`codex` → `~/.codex/skills/`，`cursor` → `~/.cursor/skills/`（默认未启用，目录约定还没在真实 Cursor 上验证过） |
| `--scope` | `user` 装到用户级（你所有项目都能用）；`project` 装到当前项目里（提交进版本控制，团队共享） |
| `--ref` | 分支、标签或 commit。不填用仓库默认分支 |
| `--install` | **关键开关**。不加只"登记"来源，不碰 Agent 目录；加了才真正安装 |
| `--dry-run` | 只列出会装什么，不实际安装 |
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

#### 一次装整个仓库的 Skill

有些仓库本身就是「一堆 Skill 的集合」（比如一个产品方法论仓库里分了好几个 category、每个 category 下十几个 Skill）。用 `--all` 一次装完：

```bash
# 先看看会装什么
./skillmesh.exe add https://github.com/phuryn/pm-skills --all --agent claude-code --scope user --dry-run

# 确认后真装
./skillmesh.exe add https://github.com/phuryn/pm-skills --all --agent claude-code --scope user --install
```

批量安装的规则：

- **先全量校验，再全量安装**。一个 Skill 校验失败（比如有逃出目录的符号链接）只影响它自己，其他照装，最后汇总报告。
- **安装名冲突跳过并报告**，不静默覆盖。两个 Skill 的 frontmatter `name` 相同的话，后一个会被跳过并说明和谁冲突。
- **目标位置已被手写内容占用的跳过并报告**，要用 `--replace` 才覆盖。
- 锁文件里每个 Skill 一条记录，各记各的子目录，但共享同一个 `resolved_commit`。

装了几十个之后，`update` 依然很快——同一个仓库只发一次 `ls-remote`、只 clone 一次（69 个 Skill 的仓库，`update --check` 约 1.4 秒）。

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

装完会写进 `skillmesh.lock.yaml`（Skill ID、版本号、制品 SHA-256、作用域、目标 Agent），所以 `list` 立刻能看到它，CI 的 `--locked` 才有东西可比对。装完还会打印一行许可证识别结果和密钥风险提示——识别只是粗匹配，**不代表可以再分发**，条款要自己看。

CI 里没有浏览器也没有凭据管理器，用环境变量传令牌：

```bash
export SKILLMESH_TOKEN=<access-token>
./skillmesh.exe install <skill-id> --locked
```

`--locked` 会校验远端解析结果与 `skillmesh.lock.yaml` 是否一致，不一致直接失败，防止构建环境静默漂移。

### 把本地 Skill 发布到云端

```bash
# 先看看会发什么
./skillmesh.exe publish ./skills/java-debug --dry-run

# 真的发
./skillmesh.exe publish ./skills/java-debug
```

流程：

```text
校验本地目录 → 扫密钥 → 打成 zip（SKILL.md 在包根）→ 算整包 SHA-256 与逐文件 manifest
  → 按 slug 查云端，没有就建（建完直接就是 published）
  → 建版本 → 拿 presigned URL 直传对象存储
  → 服务端重算 SHA-256、比对、转正为不可变制品、置 published
```

| 参数 | 作用 |
| --- | --- |
| `<path>` | 本地 Skill 目录。**在 Skill 目录里敲 `publish .` 就行**，slug 取目录名 |
| `--all` | 发布目录树里的**全部** Skill |
| `--version` | 版本号。不填就在云端已有版本上**累加 patch 位**（1.0.0 → 1.0.1） |
| `--visibility` | `private`（默认）或 `public`。v1 不支持 team/organization（需要组织概念） |
| `--channel` | 发布通道：`stable`（默认）/ `beta` / `dev` |
| `--slug` | 覆盖 slug。默认取**目录名**（要符合 `^[a-z0-9][a-z0-9-]{1,63}$`，不满足时用这个覆盖） |
| `--name` | 覆盖展示名。默认取 `SKILL.md` frontmatter 的 `name` |
| `--description` | 覆盖描述。默认取 `SKILL.md` frontmatter 的 `description` |
| `--dry-run` | 只走完流程不真的发布 |
| `--skip-secret-scan` | 跳过密钥扫描。**只在确认命中是占位符时用** |

**v1 是「创建即发布」**——没有 `draft → scanning → pending_review → published` 的审批流（那是 v2 的团队治理）。这是 spec 里明确标注的有意偏离。

几个注意点：

- **密钥会挡住上传**。扫到 AWS Key、GitHub Token、OpenAI Key、Slack Token、Google API Key 或私钥正文时直接拒绝发布，并指出文件:行号和命中规则。文档里的占位符（`your-key-here`、`<your-token>`）不误伤；真被误伤时确认一遍再用 `--skip-secret-scan`。
- **版本号自动累加依赖已有版本是语义化版本**。云端已有 `2024-01-01` 这种非语义化版本时，会报错让你用 `--version` 显式指定，不会瞎猜。
- **隐藏文件不进包**。`.skillmesh-install`（本机状态标记）、`.git` 之类不会发到云端。
- **Windows 上发布的包会丢掉脚本的可执行位**。Windows 没有 Unix 可执行位，Go 一律报 0666；Skill 里如果有 shell 脚本，在 Unix 上装完可能需要 `chmod +x`。

### 回滚到旧版本

```bash
./skillmesh.exe rollback <skill-name> --to 1.0.0
```

版本制品不可变，所以回滚不是"改回去"，而是**再装一次更早的版本**——服务端那份旧制品一直原样留着，随时能装回去。装完锁文件里的版本号、摘要一起回退。

只支持注册中心来源（`install` 装的）。Git 来源要固定版本用 `skillmesh add <url> --ref <tag>`，或 `update` 之后手动 checkout 到指定 commit。

### 我在哪些机器上装了什么

```bash
skillmesh installations                    # 跨设备看安装全貌
skillmesh installations --status conflict  # 只看有问题的
```

`install` 和 `uninstall` 会自动登记（设备 + 目录 + 版本 + 制品摘要 + 有没有本地改过）。服务端只记事实，不替客户端决定装不装。

### 团队与审核

```bash
skillmesh review submit  <skill-id> <version> --comment "初版"
skillmesh review approve <skill-id> <version> --comment "没问题"
skillmesh review reject  <skill-id> <version> --comment "第 3 节有危险命令"
```

个人与公开的 Skill 扫描通过即发布；**团队与组织可见的版本会停在待审核**（设计文档 §10.1），publisher 及以上提交，reviewer 及以上放行，且**不能审自己发的版本**。审核结论写 `skill_reviews`，同时进审计流水。

可见性规则：public 谁都能看，private 只有属主，`team` 要团队成员，`organization` 要组织成员——搜索也按这个过滤（不是查出来再在内存里筛）。

```bash
curl -X POST .../api/v1/organizations -d '{"slug":"acme","name":"Acme"}'
curl -X POST .../api/v1/organizations/<id>/teams -d '{"slug":"platform","name":"平台组"}'
curl -X POST .../api/v1/teams/<id>/members -d '{"user_id":"...","role":"reviewer"}'
```

角色从高到低：owner / admin / publisher / reviewer / member / viewer。建组织的人自动是 owner。`GET /api/v1/me` 会列出你所在的组织和角色。

### 本地项目状态

| 命令 | 作用 |
| --- | --- |
| `list` | 列出本项目锁文件里登记的**本地安装**：Skill、Agent、版本/commit、安装路径、是否受管 |
| `status` | 同上，但输出 JSON（`status <name>` 只看某一个），适合脚本消费 |
| `update --check` | 检查远端有没有新 commit，**只报告不改动** |
| `update <name> --dry-run` | 看这次更新改了哪些文件，不落盘 |
| `scan` | 本机分类扫描：已管理 / 内容变化 / 疑似敏感 / 路径冲突 |
| `update --all` | 应用全部更新 |
| `update <name>` | 只更新某一个 |
| `rollback <name> --to <version>` | 回滚到指定版本（仅注册中心来源） |
| `sync` / `sync --resolve` | 与个人私有空间对账，冲突按你的选择解决 |
| `watch start` / `stop` / `pause` | 后台同步监听 |
| `lock refresh` | 重新解析来源并刷新锁文件 |
| `uninstall <name>` | 移除安装内容与锁文件记录 |

`update` 默认只报不装——要动手得显式给 `--all` 或指定 Skill 名字。

作用域（`user` 还是 `project`）记在锁文件里，`update`/`uninstall`/`list` 都以记录为准，不会把项目级安装误当成用户级。

**`list` 和 `search` 看的不是一回事**：

- `list` = **本地装了什么**（读项目里的 `skillmesh.lock.yaml`）
- `search` = **云端有什么**（调 `/api/v1/skills`）

`publish` 只把 Skill 发到云端，**不在本地装任何东西**，所以发布完 `list` 仍然是空的——这是设计如此（锁文件记录的是"已安装的 Skill 的解析结果"，用来让 CI 复现同样的安装）。发布完用 `search` 查。

### 更新前先看差异，本地改过就不覆盖

```bash
./skillmesh.exe update <name> --dry-run
```

```text
demo-skill —— 3 个文件有变化
    修改 SKILL.md（+2/-1 行）
    新增 new.md
    删除 old.md
```

判重和"本地有没有被改过"都用**内容摘要**（对「路径 + 逐文件 SHA-256」排序后哈希），不用 zip 的 sha256——同一份内容重新打包，压缩实现或文件权限位一变摘要就变，会把没变误判成变。装的时候摘要记进锁文件的 `installed_hash`。

本地有未提交修改时，`update` 会打印「跳过 xxx —— 本地有改动，保留本地版本」，不动你的文件、锁文件也不改，退出码仍是 0（其余 Skill 照常更新）。差异大到不值得逐行算时只报文件级，二进制文件同样只报文件级。

### 同步：三方对账，冲突不自动覆盖

```bash
skillmesh scan                    # 先看本机有什么问题（不碰网络）
skillmesh sync                    # 对账并同步
skillmesh sync --direction upload # 只上传 / download 只拉取
skillmesh sync --resolve keep-local   # 冲突时保留本地并上传
skillmesh sync --resolve keep-cloud   # 冲突时保留云端并拉回
skillmesh sync --resolve keep-both    # 本地备份一份，两边都留
skillmesh sync --retry-only      # 只重试断网失败的任务
```

判定看三份摘要：**基线**（上次同步时）、**本地**、**云端**。

| 本地 vs 云端 | 动作 |
| --- | --- |
| 一样 | 无变化 |
| 只有本地变了 | 上传新版本 |
| 只有云端变了 | 拉回云端版本 |
| 两边都变了 | **冲突**，不自动覆盖，等 `--resolve` |

云端也会再判一次（`POST /api/v1/sync/changesets`，带幂等键）：别的设备在两次请求之间动过内容的话，它会直接回冲突。断网失败的任务进持久队列，指数退避（1 分钟起步、翻倍、封顶 30 分钟），重试 5 次后标记放弃但仍留在队列里可查。

```bash
skillmesh watch start --interval 5m   # 后台盯住目录，变了才同步
skillmesh watch status                # 在跑吗 / 暂停了吗
skillmesh watch pause                 # 只暂停同步，不动 Agent 的文件
skillmesh daemon install              # 写成用户级自启动（不需要管理员）
skillmesh daemon logs --tail 50
```

守护进程启动时先做一次全量对账，弥补错过的文件系统通知。实现上用**轮询**触发扫描而不是文件监听：少一个依赖、跨平台，代价是空转时每轮 stat 一遍所有文件。

### 换设备装回自己的 Skill

```bash
./skillmesh.exe search --mine     # 只看个人私有空间
./skillmesh.exe restore           # 全部装回本机
./skillmesh.exe restore java-debug --agent claude-code --scope project
```

`restore` 走的是和 `install` 完全一样的路径：校验 → 原子替换 → 写锁文件。单个 Skill 失败不中断整批，最后给「已恢复 / 跳过 / 失败」三段汇总。

### 装之前看一眼来源与风险

```bash
./skillmesh.exe info <skill-id>
```

```text
来源
  来源      SkillMesh 注册中心
  可见性    private
  最新版本  1.0.1（状态 published）
  许可证    未提供（注册中心不存这个字段，识别仅供参考）

版本     通道      状态         扫描      commit  SHA256
1.0.1  stable  published  passed  -       bf5f82c1b89e

风险
  静态扫描只能帮你发现风险，不能证明这份 Skill 安全。
```

**扫描是服务端做的**：制品定版后立刻解包扫一遍，结论写进 `skill_scans`，随版本列表返回。命中凭据（AWS Key、GitHub Token、OpenAI Key、Slack Token、Google API Key、私钥正文）或路径穿越的包**不会发布**，版本状态停在 `rejected`。客户端也扫一遍，但只用来当场提示——绕开 CLI 直接调 API 一样会被服务端拦下。

扫描列显示 `未扫描` 的，是 v1.1 之前发布的版本，那会儿没有服务端扫描。

**制品签名**（设计文档 §10 制品完整性）：服务端在制品定版时对**清单摘要**做 Ed25519 签名，签名随版本列表返回。客户端装之前验两件事——签名对不对得上，以及装下来的内容摘要和清单对不对得上。`install` 会打印结果（`已验签（内容与签名一致）` / `未签名` / `验签失败：…`）。

服务端不配密钥就不签（空签名不会被误当成验过了）：

```bash
openssl genpkey -algorithm ed25519 -out skillmesh-signing.pem
openssl pkey -in skillmesh-signing.pem -pubout -outform DER | tail -c 32 | base64 -w0   # 公钥
openssl pkcs8 -topk8 -nocrypt -in skillmesh-signing.pem -outform DER | base64 -w0          # 私钥
```

```bash
SM_SIGNING_PRIVATE_KEY=<私钥 base64>
SM_SIGNING_PUBLIC_KEY=<公钥 base64>
SM_SIGNING_KEY_ID=2026-10
```

客户端从 `GET /api/v1/keys/verify` 取公钥。**验签证明的是「这份摘要确实出自注册中心当前的密钥」，不等于注册中心本身可信**——那需要固定公钥或证书链，目前没有。

**被撤回的版本**装不上（`install` 会直接拒绝并说明状态），已经装着的用户会在 `list` 时看到提示——SkillMesh 不会自动卸载、更不会删你本地的文件。

### 审计

发布、撤回、制品下载都写 `audit_events`（谁、什么时候、对哪个对象、结果、请求 ID）。

```bash
curl -H "Authorization: Bearer $TOKEN" \
  http://127.0.0.1:8080/api/v1/skills/<skill-id>/audit
```

审计写入失败不会回滚业务动作——事情已经发生了，记不上是另一回事，会在日志里留一条失败记录。

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

v1 不做（推迟到 v2，表结构已按最终设计建好）：发布审批流、团队角色矩阵、制品签名、Cursor 适配器。

同步引擎（scan / sync / watch / daemon / 冲突解决 / 断网重试 / 同步变更集接口）在 v1.1 已交付，但只覆盖「本地 ↔ 个人私有空间」这一条链路；组织与团队之间的同步要等团队治理那批。

密钥扫描（发布阻断）与许可证识别（安装后展示）在 v1 已做，但都只是**本地粗匹配**：扫描在 CLI 侧、规则是固定的高置信度特征；许可证识别不做法律判断。要变成服务端强制，得等 v2 的 `skill_scans` 表和审核流。
