# CLI 使用手册

`skillmesh` 的完整用法：装、发布、更新、同步、诊断。
安装与启动见 [README](../README.md#快速开始)，团队与审核见 [团队治理](团队治理.md)。

## 命令速查

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
./skillmesh.exe publish ./skills/java-debug --org acme --team platform  # 发到团队，要审核

# 回滚与换设备
./skillmesh.exe rollback java-debug --to 1.0.0
./skillmesh.exe restore                    # 换设备：装回个人私有空间
```

本地项目状态、更新、卸载、回收站、诊断的命令清单见下方「本地项目状态」与「诊断」两节。

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

**它的用途是让你知道下一步 `--path` 该填什么。**

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
| `--agent` | 装给哪个 Agent。`claude-code` → `~/.claude/skills/`，`codex` → `~/.codex/skills/`，`cursor` → 探测 `~/.cursor/skills/` 与 `~/.agents/skills/`，用本机真实存在的那个 |
| `--scope` | `user` 装到用户级（你所有项目都能用）；`project` 装到当前项目里（提交进版本控制，团队共享） |
| `--ref` | 分支、标签或 commit。不填用仓库默认分支 |
| `--install` | **关键开关**。不加只「登记」来源，不碰 Agent 目录；加了才真正安装 |
| `--dry-run` | 只列出会装什么，不实际安装 |
| `--replace` | 目标位置已有同名目录时强制覆盖（见下方「同名目录不覆盖」） |
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

**原子安装的意义**：装到一半失败不会毁掉你原来的版本——旧版本先被备份，切换失败自动回滚。

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

# 发到组织 / 团队（这类版本会停在待审核）
./skillmesh.exe publish ./skills/java-debug --org acme --team platform
```

流程：

```text
校验本地目录 → 扫密钥 → 打成 zip（SKILL.md 在包根）→ 算整包 SHA-256 与逐文件 manifest
  → 按 slug 查云端，没有就建
  → 建版本 → 拿 presigned URL 直传对象存储
  → 服务端重算 SHA-256、比对、转正为不可变制品
  → 服务端扫描：命中阻断项 → rejected
  → 个人/公开可见：置 published；团队/组织可见：置 pending_review，等人审
```

| 参数 | 作用 |
| --- | --- |
| `<path>` | 本地 Skill 目录。**在 Skill 目录里敲 `publish .` 就行**，slug 取目录名 |
| `--all` | 发布目录树里的**全部** Skill |
| `--version` | 版本号。不填就在云端已有版本上**累加 patch 位**（1.0.0 → 1.0.1） |
| `--visibility` | 可见性：`private`（默认）/ `public` / `team` / `organization`。`team` 与 `organization` 要配合 `--org`/`--team` |
| `--org` | 发布到的组织，**slug**（不是 id）。给了它，可见性就是 `organization` |
| `--team` | 发布到的团队，**slug**（不是 id）。**必须同时给 `--org`**，给了它，可见性就是 `team` |
| `--channel` | 发布通道：`stable`（默认）/ `beta` / `dev` |
| `--slug` | 覆盖 slug。默认取**目录名**（要符合 `^[a-z0-9][a-z0-9-]{1,63}$`，不满足时用这个覆盖） |
| `--name` | 覆盖展示名。默认取 `SKILL.md` frontmatter 的 `name` |
| `--description` | 覆盖描述。默认取 `SKILL.md` frontmatter 的 `description` |
| `--dry-run` | 只走完流程不真的发布 |
| `--skip-secret-scan` | 跳过密钥扫描。**只在确认命中是占位符时用** |

可见性与归属怎么定：

- 不给 `--org`/`--team`：可见性就是 `--visibility` 给的值，默认 `private`。
- 只给 `--org`：可见性 `organization`。
- 给 `--org` + `--team`：可见性 `team`。
- 又显式给了 `--visibility` 又给了归属：两者必须一致，不一致会被挡下（`--visibility team` 少了 `--team`、或与归属冲突都有明确报错）。

几个注意点：

- **往组织/团队发要组织角色 publisher 及以上**。CLI 拿 `GET /me` 里的组织角色判，服务端也按组织角色判，两边同口径。角色不够时报「你在组织 "acme" 的角色是 member，发布到组织/团队需要 publisher 及以上」。
- **团队与组织可见的版本不会直接发布**——停在 `pending_review` 等审核，CLI 会打印「已提交审核」而不是「已发布」。个人与公开的版本扫描通过即发布。版本被服务端的扫描拦下时报错退出（`rejected`），不会假装进了审核队列。
- **Skill 的归属只在新建时定，改不了**。给已有 Skill 发新版本时再带上**一样的** `--org`/`--team` 是允许的（重申同一个归属）；带了**不一样的**归属会报错说清这个 Skill 属于谁、你要发到哪，并提示「去掉 --org/--team 会沿用它已登记的归属」。想改可见性报「Skill "java-debug" 已存在，可见性改不了（现在是 private）」。
- **密钥会挡住上传**。扫到 AWS Key、GitHub Token、OpenAI Key、Slack Token、Google API Key 或私钥正文时直接拒绝发布，并指出文件:行号和命中规则。文档里的占位符（`your-key-here`、`<your-token>`）不误伤；真被误伤时确认一遍再用 `--skip-secret-scan`。
- **版本号自动累加依赖已有版本是语义化版本**。云端已有 `2024-01-01` 这种非语义化版本时，会报错让你用 `--version` 显式指定，不会瞎猜。
- **隐藏文件与忽略目录不进包**。`.skillmesh-install`（本机状态标记）、`.git`、`node_modules`、`__pycache__` 之类不会发到云端——发出去的文件与本地算摘要、扫密钥的是同一批，`update --dry-run` 里看不到的文件不会被悄悄上传。
- **Windows 上发布的包会丢掉脚本的可执行位**。Windows 没有 Unix 可执行位，Go 一律报 0666；Skill 里如果有 shell 脚本，在 Unix 上装完可能需要 `chmod +x`。

### 回滚到旧版本

```bash
./skillmesh.exe rollback <skill-name> --to 1.0.0
```

版本制品不可变，所以回滚不是「改回去」，而是**再装一次更早的版本**——服务端那份旧制品一直原样留着，随时能装回去。装完锁文件里的版本号、摘要一起回退。

只支持注册中心来源（`install` 装的）。Git 来源要固定版本用 `skillmesh add <url> --ref <tag>`，或 `update` 之后手动 checkout 到指定 commit。

### 我在哪些机器上装了什么

```bash
skillmesh installations                    # 跨设备看安装全貌
skillmesh installations --status conflict  # 只看有问题的
skillmesh installations --check            # 巡检本机磁盘，把真实状态写回服务端
```

`install` 和 `uninstall` 会自动登记（设备 + 目录 + 版本 + 制品摘要 + 有没有本地改过）。服务端只记事实，不替客户端决定装不装。

**`--check` 是客户端推状态**：服务端不会自己去扫你的磁盘——它看不到。巡检只动**这台机器**的记录，算出来的状态会写回服务端：

| 状态 | 什么时候是它 |
| --- | --- |
| `uninstalled` | 目标目录不在了 |
| `missing` | 目录还在，`SKILL.md` 不见了 |
| `conflict` | 本机内容被改过，云端也有内容不同的已发布版本 |
| `blocked` | 服务端记的那一版在云端已经不能用了（撤回、驳回） |
| `active` | 一切正常（内容对不上但云端没动时是 `active` + 「本地改过」） |

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
| `uninstall <name>` | 卸载（默认移进回收站，可找回） |
| `trash list` / `restore` / `purge` | 回收站：找回卸载掉的 Skill、清过期 |

`update` 默认只报不装——要动手得显式给 `--all` 或指定 Skill 名字。

作用域（`user` 还是 `project`）记在锁文件里，`update`/`uninstall`/`list` 都以记录为准，不会把项目级安装误当成用户级。

**`list` 和 `search` 看的不是一回事**：

- `list` = **本地装了什么**（读项目里的 `skillmesh.lock.yaml`）
- `search` = **云端有什么**（调 `/api/v1/skills`）

`publish` 只把 Skill 发到云端，**不在本地装任何东西**，所以发布完 `list` 仍然是空的——这是设计如此（锁文件记的是已安装 Skill 的解析结果）。发布完用 `search` 查。

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

判重和「本地有没有被改过」都用**内容摘要**（对「路径 + 逐文件 SHA-256」排序后哈希），不用 zip 的 sha256——同一份内容重新打包，压缩实现或文件权限位一变摘要就变，会把没变误判成变。装的时候摘要记进锁文件的 `installed_hash`。

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
skillmesh sync --skip-new        # 只同步已收编的，首次发现先留在本地
skillmesh sync --allow-delete    # 确认把「本地删掉的文件」也传播到云端
```

**本地删了文件不会静默传到云端**：同步发现「基线里有、本地没了、云端也没变」的文件时，
这次上传会**停下**，报「待确认」并列出是哪些文件。确认要删就加 `--allow-delete` 再跑一次。
（设计文档 §6.4 说的「软删除 + 回收期 + 可恢复」还没做，现在是「不静默传播 + 要人确认」。）

> 后台同步（`watch` / `daemon`）**永远不会传播删除**——它们不传 `--allow-delete`。
> 删除只能手动跑 `skillmesh sync --allow-delete` 确认。这是有意的：后台任务没人盯着，
> 不该替人做删除这种不可逆的决定。

**首次发现不等于收编**：新出现在授权目录里的 Skill 默认会备份到个人私有空间，**不会自动公开**；`--skip-new` 则一个都不收编，先留在本地等你看过再说。首次发现的时间记在 `sync-state.yaml` 的 `first_seen_at`。

判定看三份摘要：**基线**（上次同步时）、**本地**、**云端**。

| 本地 vs 云端 | 动作 |
| --- | --- |
| 一样 | 无变化 |
| 只有本地变了 | 上传新版本 |
| 只有云端变了 | 拉回云端版本 |
| 两边都变了 | **冲突**，不自动覆盖，等 `--resolve` |

**能合就合，合不了才问你**：冲突时先按文件来——本机改了 A、云端改了 B，两边都要；同一个文件两边都改，会做**行级三方文本合并**，合得上直接合，合不上的才生成冲突副本等你定夺（设计文档 §6.3）。合并需要上次同步时的基线（逐文件摘要 + 内容副本，存在配置目录的 `sync-base/`），没有基线就老实说合不了，不会瞎合。

云端也会再判一次（`POST /api/v1/sync/changesets`，带幂等键）：别的设备在两次请求之间动过内容的话，它会直接回冲突。断网失败的任务进持久队列，指数退避（1 分钟起步、翻倍、封顶 30 分钟），重试 5 次后标记放弃但仍留在队列里可查。

**上传时带基线**：同步上传会带上「本次决策时看到的云端摘要」，服务端锁住该 Skill 后重算一遍——
对不上说明云端在这期间被别人动过，这次上传会把那份改动静默盖掉，所以服务端直接回冲突，让 CLI 重新对账
（详细设计 §12 的 `expected_revision`）。普通 `skillmesh publish` 不带基线，行为不变。

```bash
skillmesh watch start --interval 5m   # 后台盯住目录，变了才同步
skillmesh watch status                # 在跑吗 / 暂停了吗
skillmesh watch pause                 # 只暂停同步，不动 Agent 的文件
skillmesh daemon install              # 写成用户级自启动（不需要管理员）
skillmesh daemon logs --tail 50
```

守护进程启动时先做一次全量对账，弥补错过的文件系统通知。触发是**文件监听 + 轮询兜底**：文件事件带 2 秒防抖（编辑器写文件常分好几次，立刻扫会扫到写了一半的内容），轮询每 `--interval` 兜一次（默认 30 秒）。

盯的是**用户级** Agent 目录（`~/.claude/skills` 这类），项目里的 `.claude/skills` 不在自动同步范围。

**令牌过期会自动续期**：access token 过期时，CLI 用登录时存下的 refresh token 换一个新的（续期后的令牌会写回凭据管理器）。
所以常驻同步不会因为令牌过期而停摆。只有 refresh token 也失效了（被撤销、或登出过）才需要重新 `skillmesh login`——
这时 watch/daemon 的日志里会写「令牌已过期且续期失败，请重新执行 skillmesh login」。

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

扫描分两档：**密钥命中会阻断发布**（版本停在 `rejected`），**可疑内容只提示**——脚本里的 `curl | sh`、`rm -rf /`、`chmod 777`、关证书校验、读凭据文件、装依赖、引用外部地址，都会连文件:行号一起列出来让你自己判断。只扫脚本与配置文件，`.md` 里的链接不算问题，否则告警会被噪音淹没。

`skillmesh scan` 会把这两类分开列（`疑似敏感` / `可疑内容`），`install` 时也会把可疑内容打出来。

扫描列显示 `未扫描` 的，是 v1.1 之前发布的版本，那会儿没有服务端扫描。

**制品签名**（设计文档 §10 制品完整性）：服务端在制品定版时对**清单摘要**做 Ed25519 签名，签名随版本列表返回。客户端装之前验两件事——签名对不对得上，以及装下来的内容摘要和清单对不对得上。`install` 会打印结果（`已验签（内容与签名一致）` / `未签名` / `验签失败：…`）；**验签不通过就不装**——失败会带着原因退出，东西不会进 Agent 目录。

客户端从 `GET /api/v1/keys/verify` 取公钥。要把「注册中心当前的密钥」变成「我信任的密钥」，在 `config.yaml` 里钉死它：

```yaml
verify:
  pinned_key_id: 2026-10
  pinned_public_key: <base64 原始 32 字节>
```

钉了之后，服务端换密钥而你没更新配置，验签会**硬失败**（不会只看「签名能不能验过」）。没钉也没配服务端密钥时，CLI 会明说「未验证来源」——能确认内容摘要对得上，但不能确认是谁签的。服务端怎么生成与配置签名密钥见 [部署与排障](部署与排障.md#制品签名密钥)。

**被撤回的版本**装不上（`install` 会直接拒绝并说明状态），已经装着的用户会在 `list` 时看到提示——SkillMesh 不会自动卸载、更不会删你本地的文件。

### 诊断

```bash
./skillmesh.exe doctor        # 服务端地址、配置目录、git 是否可用、登录状态、各 Agent 目录是否存在
./skillmesh.exe agents detect  # 各 Agent 的用户级/项目级目录表格
```

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

