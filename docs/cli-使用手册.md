# CLI 使用手册

`skillmesh` 的完整用法：登录、同步、安装、发布、更新、排障。
安装与启动见 [README](../README.md#快速开始)，团队与审核见 [团队治理](团队治理.md)。

每个命令的参数说明看 `skillmesh <命令> --help`，本文只讲**行为、规则和坑**。
示例按 Windows 写成 `./skillmesh.exe`，Linux/macOS 上换成 `./skillmesh`。

## 命令速查

日常只有这几条：登录 → 发布 → 同步。

```bash
./skillmesh.exe login http://127.0.0.1:8080/api/v1   # 登录（浏览器授权码 + PKCE）
./skillmesh.exe publish ./skills/java-debug          # 把本地 Skill 发到云端
./skillmesh.exe sync                                 # 与云端双向同步
./skillmesh.exe search java                          # 找 Skill
./skillmesh.exe info <skill-id>                      # 看详情
```

`skillmesh --help` 只列这些常用命令。其余命令（Git 来源、云端删除、回收站、
后台监听、团队集合、诊断等）**仍然可用**，只是不占帮助版面——
`skillmesh help <命令>` 或看下面各节。

## 登录

```bash
skillmesh login http://127.0.0.1:8080/api/v1   # 浏览器授权码 + PKCE
skillmesh logout                                # 清除本地登录态
```

登录所需的 OIDC issuer 从服务端的公开配置里取，也可以用环境变量 `SM_KEYCLOAK_ISSUER` 直接指定。

### 无浏览器登录（Linux 服务器 / SSH）

`login` 默认打开本机浏览器。服务器没有图形界面时加 `--manual`：

```bash
skillmesh login --manual http://your-server/api/v1
```

终端会打印授权地址。把地址复制到**任何一台有浏览器的机器**上打开、完成登录，浏览器会跳到 `http://127.0.0.1:53682/callback?code=...`——页面打不开没关系，把地址栏里的完整 URL 复制下来，粘回终端回车即可。`state` 校验不通过会直接拒绝。

也可以走 SSH 端口转发，让回调直接回到服务器，不用手动粘贴：

```bash
ssh -L 53682:127.0.0.1:53682 <服务器>
```

### 令牌过期自动续期

access token 过期时，CLI 用登录时存下的 refresh token 换一个新的（续期后的令牌会写回凭据管理器），所以常驻同步不会因为令牌过期而停摆。只有 refresh token 也失效了（被撤销、或登出过）才需要重新 `skillmesh login`——这时 watch 的日志里会写「令牌已过期且续期失败，请重新执行 skillmesh login」。

## 同步：两端对齐，冲突不自动覆盖

它把本机 Agent 目录和个人私有空间拉成一样：

| 情况 | 动作 |
| --- | --- |
| 本机有、云端没有 | 传上去 |
| **云端有、本机没有** | **装下来** |
| 两边都有、内容一样 | 不动 |
| 只有一边变了 | 顺着变 |
| 两边都变了 | 冲突，不自动覆盖，等你决定 |

先看本机状况（不碰网络）：`skillmesh scan`。

```bash
skillmesh sync --dry-run              # 只报会做什么，不落盘
skillmesh sync --only-existing        # 只处理已收编的，云端新增的不装
skillmesh sync --direction upload     # 只上传 / download 只下载
skillmesh sync --resolve keep-local   # 冲突时保留本地并上传
skillmesh sync --resolve keep-cloud   # 冲突时保留云端并拉回
skillmesh sync --resolve keep-both    # 本地备份一份，两边都留
skillmesh sync --allow-delete         # 确认把「本地删掉的文件」也传播到云端
skillmesh sync --propagate-delete     # 确认把「本地删掉的整个 Skill」也从云端删掉
```

**本地删了文件不会静默传到云端**：同步发现「基线里有、本地没了、云端也没变」的文件时，这次上传会**停下**，报「待确认」并列出是哪些文件。确认要删就加 `--allow-delete` 再跑一次。

> 后台同步（`watch`）**永远不会传播删除**——它们不传 `--allow-delete`。删除只能手动跑 `skillmesh sync --allow-delete` 确认：后台任务没人盯着，不该替人做删除这种不可逆的决定。

**本地删掉整个 Skill，默认也不会删云端**：目录扫不到可能只是路径变了、网盘没挂上、Agent 目录搬了家——扫不到不等于你删了它。想让删除也传过去，加 `--propagate-delete`：

```bash
skillmesh sync --propagate-delete --dry-run   # 先看会删掉云端哪些 Skill
skillmesh sync --propagate-delete             # 真删（云端软删除，回收期内能还原）
```

判定的是「本地确实删了」，不是「本地扫不到」——四条都满足才动云端：基线里记着这个 Skill 的云端 id、那个目录确实没了、本机别处没有同一份、路径还在本次扫描范围内。同一个 Skill 装在多个 Agent 目录下时，删掉一份不算删（别的目录里还装着它）；改了 Agent 目录导致老路径扫不到，也不算删。

云端那边是**软删除**：版本和制品都留着，回收期内 `skillmesh cloud restore <slug>` 能找回来。

> `--propagate-delete` 管的是**整个 Skill**；包里少了几个文件是另一回事，那个走 `--allow-delete`。
>
> 想让后台同步（`watch`）也照做，在配置里写 `sync.propagate_delete: true`——这是「明确配置并确认后才传播删除」里的那个「明确配置」。

**首次发现不等于收编**：新出现在授权目录里的 Skill 默认会备份到个人私有空间，**不会自动公开**；`--only-existing` 则一个都不收编，先留在本地等你看过再说。首次发现的时间记在 `sync-state.yaml` 的 `first_seen_at`。

**能合就合，合不了才问你**：冲突时先按文件来——本机改了 A、云端改了 B，两边都要；同一个文件两边都改，会做**行级三方文本合并**，合得上直接合，合不上的才生成冲突副本等你定夺（[详细设计 §6.3](详细设计.md)）。冲突副本落在 Skill 目录旁的隐藏目录 `.名字.conflict-<摘要>/`，里面是带 `<<<<<<< 本地` 标记的内容。隐藏目录不会被扫成 Skill，也不会传到云端。合并需要上次同步时的基线（逐文件摘要 + 内容副本，存在配置目录的 `sync-base/`），没有基线就老实说合不了，不会瞎合。

云端也会再判一次（`POST /api/v1/sync/changesets`，带幂等键）：别的设备在两次请求之间动过内容的话，它会直接回冲突。断网失败的任务进持久队列，指数退避（1 分钟起步、翻倍、封顶 30 分钟），重试 5 次后标记放弃但仍留在队列里可查。

**上传时带基线**：同步上传会带上「本次决策时看到的云端摘要」，服务端锁住该 Skill 后重算一遍——对不上说明云端在这期间被别人动过，这次上传会把那份改动静默盖掉，所以服务端直接回冲突，让 CLI 重新对账（[详细设计 §12](详细设计.md) 的 `expected_revision`）。普通 `skillmesh publish` 不带基线，行为不变。

**删掉的文件能找回来**（[设计文档 §6.4](设计文档.md) 的软删除 + 回收期 + 可恢复）：

```bash
skillmesh cloud recycle list               # 云端某个 Skill 里被删掉的文件
skillmesh cloud recycle restore old.md     # 把它放回去，产出一个新版本
skillmesh cloud recycle list --skill demo  # 不在 Skill 目录里时用 --skill 指定
```

回收期 30 天。恢复走的是**正常发版流程**——扫描、审核那道闸一样要过，恢复回来的内容不会被绕开审核直接上线。

## 多个 Agent 共用一份技能

Claude Code、Codex 各有一份技能目录，同一个技能就存了两遍：磁盘占双倍空间，
改一份要记得改另一份，同步时也多出一倍的条目（`sync` 的进度条会数到两遍）。

让它们共用一份：实体只留在一个 Agent 的目录里，另一个 Agent 的目录里
每个技能换成一个链接，指向那一份。

```bash
./skillmesh.exe share codex --from claude-code --dry-run   # 先看会怎么动
./skillmesh.exe share codex --from claude-code             # 真改（会问一次）
```

具体做了什么：

| 情况 | 处理 |
| --- | --- |
| 目标目录独有的技能 | 搬到源目录（那边才是实体） |
| 两边内容一样 | 删掉目标目录里那份，换成链接 |
| 源目录有、目标目录没有 | 在目标目录建链接 |
| 同名但内容不同 | **停下来报错**，什么都不动，由你决定留哪份 |
| Agent 自己的目录（Codex 的 `.system`） | 原样留着，不碰 |

共享完跑一次 `sync` 让基线跟上新的路径，同步条目会从两份变回一份。
以后装了新技能，再跑一次 `share` 补链接（命令是幂等的，重复跑安全）。

链接用的是符号链接（Windows 上退而求其次会用目录联接），
删掉链接就能还原成普通目录。

## 删掉云端的 Skill

```bash
skillmesh cloud delete java-debug       # 删（软删除）
skillmesh cloud deleted                 # 看自己删过哪些
skillmesh cloud restore java-debug      # 找回来
```

删除是**软删除**：版本、制品、安装记录都留着，Skill 不再出现在列表里、也不能再被安装，回收期内随时能还原。删掉之后 **slug 让了出来**——可以用同一个名字重新建一个新的。

`skillmesh cloud deleted` 的最后一列是**现状**：删掉之后又用同一个名字重建了一个的，那条记录会标成「已重建（id）」，不是「还没恢复」。

删除权限与发布权同档：个人 Skill 只有属主能删，团队/组织的要 publisher 及以上。

**各端同步时，本地那份会一起删掉**：下一次 `skillmesh sync` 看到云端明确删过了，就把本地目录移进本机回收站（`skillmesh trash list` 能找回），并清掉这条同步基线——留着基线的话，下一轮同步会把删掉的东西又传回云端。

同步只认服务端明确记下的删除，不会靠「云端查不到」去推断：换台服务器、连了个空库，都不会把本机的 Skill 误删。

**云端删过的东西不会被重新上传上去**。这条按 slug 判，不看本机基线：

- 本机这份正是被删的那个（基线里记的 Skill ID 与删除记录对得上）→ **移进本机回收站**，基线一起清掉。
- 对不上（另一台机器上装的副本、本机基线里没记过、或只是同名的另一个 Skill）→ **本地保留，但不上传**——云端明确删过，上传就是复活。

删完又用同一个 slug 重建了一个的，云端那个是新的 Skill：本地被删的那份会先清理掉，随后 `sync` 把重建的那个拉下来。

> 重建过之后，本机基线里没记过的那份不会再被旧的删除记录挡住：同步会先看这个 slug 现在有没有活着的 Skill，有就按正常对账处理——本机那份和云端那份比内容，一样就什么都不做，不一样就以本机为准上传一版。

> **拿不到「已删除清单」就不上传**：同步开头先拉这份清单。拉不到（服务端版本太旧、没有 `/deleted-skills` 接口，或者网络不通）时，本次同步**一个都不上传**，直接报失败。原因是老逻辑里「云端查不到 = 该上传」——那时候没有删除的概念，上传会在云端新建一个同名的 Skill，把人家删掉的复活。宁可这次不传，等清单拿到了再说。

> 后台同步（`watch`）也会跟着删——但删掉的东西是进了本机回收站，不是直接抹掉，误删还能找回来。

## 撤回云端的某个版本

```bash
skillmesh cloud revoke docker-deploy-check 1.0.0 --reason "制品发错了"
```

版本发错了（打包成了不是 zip 的东西、扫描没过、内容有问题），但又不想把整个 Skill 删掉时用**撤回**：Skill 还在，只是这一个版本不再对外可用——装不上了，也不能再被送审或转正。

撤回**不可逆**：要恢复只能发一个新版本。制品与文件仍留在云端，只是不再对外可用；已经装到别人机器上的内容不会被无声删除，他们下次同步会看到撤回提示。

只有属主能撤回自己的版本。撤回原因会记进服务端审计流水，本机操作日志里也记一条 `version.revoke`。

## 后台同步

```bash
skillmesh watch start --interval 5m   # 后台盯住目录，变了才同步
skillmesh watch status                # 在跑吗 / 暂停了吗
skillmesh watch pause                 # 只暂停同步，不动 Agent 的文件
skillmesh watch install               # 写成用户级自启动（不需要管理员）
skillmesh watch logs --tail 50
```

监听启动时先做一次全量对账，弥补错过的文件系统通知。触发是**文件监听 + 轮询兜底**：文件事件带 2 秒防抖（编辑器写文件常分好几次，立刻扫会扫到写了一半的内容），轮询每 `--interval` 兜一次（默认 30 秒）。

盯的是**用户级** Agent 目录（`~/.claude/skills` 这类），项目里的 `.claude/skills` 不在自动同步范围。令牌过期由 CLI 自动续期，见[登录](#登录)。

## 换设备装回自己的 Skill

新机器上装好 CLI、登录，然后跑一次同步：

```bash
./skillmesh.exe login http://127.0.0.1:8080/api/v1
./skillmesh.exe sync --dry-run    # 先看会装什么
./skillmesh.exe sync              # 云端有、本机没有的全部装下来
```

`sync` 走的是和 `install` 一样的路径：校验 → 验签 → 原子替换 → 写锁文件。单个 Skill 失败不中断整批。

四条口径：

- **已经是同一份就不重装**：本地内容与云端一致时跳过，重复跑不会每次都覆盖一遍。
- **本地是别的东西就不碰**：本地有同名目录但不受 SkillMesh 管理时跳过，不替人做决定。
- **本地有未同步的改动就跳过**：不覆盖你可能还没上传的修改，提示你用 `sync` 的 `--resolve` 决定。
- **装完记下同步基线**：之后云端更新了，下次 `sync` 直接拉回来，不会误报成冲突。

## 安装前先看来源与风险

```bash
./skillmesh.exe info <skill-id>
```

`info` 给出来源、可见性、最新版本与状态、许可证、版本列表（通道 / 状态 / 扫描结果 / SHA256）和风险提示。

**扫描是服务端做的**：制品定版后立刻解包扫一遍，结论写进 `skill_scans`，随版本列表返回。命中凭据（AWS Key、GitHub Token、OpenAI Key、Slack Token、Google API Key、私钥正文）或路径穿越的包**不会发布**，版本状态停在 `rejected`。客户端也扫一遍，但只用来当场提示——绕开 CLI 直接调 API 一样会被服务端拦下。

扫描分两档：**密钥命中会阻断发布**（版本停在 `rejected`），**可疑内容只提示**——脚本里的 `curl | sh`、`rm -rf /`、`chmod 777`、关证书校验、读凭据文件、装依赖、引用外部地址，都会连文件:行号一起列出来让你自己判断。只扫脚本与配置文件，`.md` 里的链接不算问题，否则告警会被噪音淹没。`skillmesh scan` 会把这两类分开列（`疑似敏感` / `可疑内容`），`install` 时也会把可疑内容打出来。

扫描列显示 `未扫描` 的，是 v1.1 之前发布的版本，那会儿没有服务端扫描。

**制品签名**（[设计文档 §10](设计文档.md) 制品完整性）：服务端在制品定版时对**清单摘要**做 Ed25519 签名，签名随版本列表返回。客户端装之前验两件事——签名对不对得上，以及装下来的内容摘要和清单对不对得上。`install` 会打印结果（`已验签（内容与签名一致）` / `未签名` / `验签失败：…`）；**验签不通过就不装**——失败会带着原因退出，东西不会进 Agent 目录。

客户端从 `GET /api/v1/keys/verify` 取公钥。要把「注册中心当前的密钥」变成「我信任的密钥」，在 `config.yaml` 里钉死它：

```yaml
verify:
  pinned_key_id: 2026-10
  pinned_public_key: <base64 原始 32 字节>
```

钉了之后，服务端换密钥而你没更新配置，验签会**硬失败**（不会只看「签名能不能验过」）。没钉也没配服务端密钥时，CLI 会明说「未验证来源」——能确认内容摘要对得上，但不能确认是谁签的。服务端怎么生成与配置签名密钥见 [部署与排障](部署与排障.md#制品签名密钥)。

**被撤回的版本**装不上（`install` 会直接拒绝并说明状态），已经装着的用户会在 `list` 时看到提示——SkillMesh 不会自动卸载、更不会删你本地的文件。

## 从注册中心装一个 Skill

```bash
./skillmesh.exe install <skill-id> --agent claude-code --scope user
```

从 SkillMesh 云端拉已发布的版本。`--version` 指定版本（不填取最新已发布的），`--dry-run` 只演练不落盘。

装完会写进 `skillmesh.lock.yaml`（Skill ID、版本号、制品 SHA-256、作用域、目标 Agent），所以 `list` 立刻能看到它。装完还会打印一行许可证识别结果和密钥风险提示——识别只是粗匹配，不代表可以再分发，条款要自己看。

CI 里没有浏览器也没有凭据管理器，用环境变量传令牌：

```bash
export SKILLMESH_TOKEN=<access-token>
./skillmesh.exe install <skill-id> --locked
```

`--locked` 会校验远端解析结果与 `skillmesh.lock.yaml` 是否一致，不一致直接失败，防止构建环境静默漂移。

## 从 Git 仓库装一个 Skill：两步

### 第一步 `source resolve` —— 先看一眼仓库里有什么

```bash
./skillmesh.exe source resolve git@github.com:owner/agent-skills
```

**只读，不装任何东西**：浅克隆到临时目录、扫出所有含 `SKILL.md` 的子目录、打印后删掉临时目录。

它的唯一用途是让你知道下一步 `--path` 该填什么。加 `--ref` 可以指定分支、标签或 commit。

### 第二步 `add` —— 登记并装上

```bash
./skillmesh.exe add git@github.com:owner/agent-skills.git \
  --path skills/java-debug --agent claude-code --scope user
```

装之前先弄清几条规则：

- **仓库地址必须是远程地址**。`file://`、本地路径（`./x`、`/abs/x`、`C:\repo`）和 `owner/repo` 这样的简写都会被拒绝，并明确告诉你该怎么写；`git@owner:repo.git`、`ssh://`、`https://` 三种形式不能混写。
- `--path` **只有候选恰好一个时才能省略**，有多个会报错并列出候选让你挑。
- `--all` 装仓库里的全部 Skill，与 `--path` 互斥。
- `--agent` 决定装到哪：`claude-code` → `~/.claude/skills/`，`codex` → `~/.codex/skills/`，`cursor` → 探测 `~/.cursor/skills/` 与 `~/.agents/skills/` 里本机真实存在的那个（默认关着，要在 config.yaml 里打开）。**也可以填 config.yaml 里自定义的 Agent**（`agents` 段下的键），登记到注册中心时照原样记下，不受服务端枚举限制。
- `--scope user` 装到用户级（所有项目都能用）；`--scope project` 装到当前项目里、提交进版本控制给团队共享。
- `--credential-ref` 给私有仓库用本机凭据管理器里的凭据引用，**不接受明文令牌**。
- `--dry-run` 只列出会装什么，不落盘。

装的过程是「校验 → 原子安装 → 写 `.skillmesh-install` 标记 → 写 `skillmesh.lock.yaml`」。原子安装的意义是装到一半失败不会毁掉你原来的版本：旧版本先备份，切换失败自动回滚。

### 一次装整个仓库的 Skill

有些仓库本身就是一堆 Skill 的集合（方法论仓库里分了好几个 category，每个 category 下十几个 Skill），用 `--all` 一次装完。批量安装的规则：

- **先全量校验，再全量安装**。一个 Skill 校验失败只影响它自己，其他照装，最后汇总报告。
- **安装名冲突跳过并报告**，不静默覆盖。两个 Skill 的 frontmatter `name` 相同时，后一个会被跳过并说明和谁冲突。
- **目标位置已被手写内容占用的跳过并报告**，要用 `--replace` 才覆盖。
- 锁文件里每个 Skill 一条记录，各记各的子目录，但共享同一个 `resolved_commit`。

装了几十个之后 `update` 依然很快——同一个仓库只发一次 `ls-remote`、只 clone 一次（69 个 Skill 的仓库，`update --check` 约 1.4 秒）。

### 同名目录默认不覆盖

目标位置已有同名目录、但它不是 SkillMesh 装的（没有 `.skillmesh-install` 标记），CLI 会拒绝并提示用 `--replace` 覆盖。

这是防止误删你手写的 Skill。反过来，`uninstall` 也不会删不受管理的目录，除非加 `--force`。

## 从本地目录或 zip 装

内网、离线或者还没进注册中心的东西，也能装：

```bash
# 从本地目录装
./skillmesh.exe add --local ./my-skills --agent claude-code --scope user

# 从本地 zip 装
./skillmesh.exe install --file ./my-skill.zip --agent claude-code --scope user
```

**这类安装不进注册中心。** 本地来源没有签名、没走过服务端的扫描与审核，
登记上去等于让未验证的内容冒充发布方的版本。它们只装到本机，
来源写进锁文件（`source_type: local`），更新与卸载照常按锁文件走。

两个不同的本地目录装同一个名字会被认成不同来源，不会互相覆盖。

## 技能集合

集合是「命名的一组 Skill」：团队把一套规范收成一个集合，新人一键装齐。

```bash
./skillmesh.exe group list                       # 列出我可见的集合
./skillmesh.exe group show team-baseline        # 成员与我装了几个
./skillmesh.exe group create team-baseline     --name "团队基线" --skill dogfood --skill skill-creator
./skillmesh.exe group install team-baseline --agent claude-code   # 装齐
```

- 成员用 `--skill` 指定，slug 或 id 都行；可重复。
- 建集合与改成员走 CLI，网页端看集合与已装情况——跟发布走 CLI 是同一个道理。
- 徽标只算「我装了几个」：本人可见的数据。团队级覆盖率（谁在哪台机器上装了多少）
  是另一种可见性，单独授权，不在这里混进来。
- `group install` 逐个装：一个装不上不影响后面的，结果逐条报出来。

## 把本地 Skill 发布到云端

```bash
# 先看看会发什么
./skillmesh.exe publish ./skills/java-debug --dry-run

# 真的发
./skillmesh.exe publish ./skills/java-debug

# 发到组织 / 团队（这类版本会停在待审核）
./skillmesh.exe publish ./skills/java-debug --org acme --team platform
```

流程是：校验本地目录 → 扫密钥 → 打成 zip（`SKILL.md` 在包根）→ 算整包 SHA-256 与逐文件 manifest → 按 slug 查云端、没有就建 → 建版本 → 把 zip 上传到 API（由 API 写入对象存储）→ 服务端重算 SHA-256 并比对，转正为不可变制品 → 服务端扫描决定发布还是驳回。

可见性与归属怎么定：

- 不给 `--org`/`--team`：可见性就是 `--visibility` 给的值，默认 `private`。
- 只给 `--org`：可见性 `organization`。
- 给 `--org` + `--team`：可见性 `team`。
- 又显式给 `--visibility` 又给归属：两者必须一致，不一致会被挡下。

几个注意点：

- **往组织/团队发要组织角色 publisher 及以上**。CLI 拿 `GET /me` 里的组织角色判，服务端也按组织角色判，两边同口径。角色不够时报「你在组织 "acme" 的角色是 member，发布到组织/团队需要 publisher 及以上」。
- **团队与组织可见的版本不会直接发布**——停在 `pending_review` 等审核，CLI 打印「已提交审核」而不是「已发布」。个人与公开的版本扫描通过即发布。被服务端扫描拦下的版本报 `rejected` 直接退出，不会假装进了审核队列。
- **Skill 的归属只在新建时定，改不了**。给已有 Skill 发新版本时带上**一样的** `--org`/`--team` 是允许的（重申同一个归属）；带了**不一样的**归属会报错说清这个 Skill 属于谁、你要发到哪，并提示「去掉 --org/--team 会沿用它已登记的归属」。想改可见性则报「Skill "java-debug" 已存在，可见性改不了」。
- **密钥会挡住上传**。扫到 AWS Key、GitHub Token、OpenAI Key、Slack Token、Google API Key 或私钥正文时直接拒绝发布，并指出文件:行号和命中规则。文档里的占位符（`your-key-here`、`<your-token>`）不误伤；真被误伤时确认一遍再用 `--skip-secret-scan`。
- **版本号自动累加依赖已有版本是语义化版本**。云端已有 `2024-01-01` 这种非语义化版本时会报错，让你用 `--version` 显式指定，不会瞎猜。
- **隐藏文件与忽略目录不进包**。`.skillmesh-install`、`.git`、`node_modules`、`__pycache__` 之类不会发到云端——发出去的文件与本地算摘要、扫密钥的是同一批。
- **Windows 上发布的包会丢掉脚本的可执行位**。Windows 没有 Unix 可执行位，Go 一律报 0666；Skill 里如果有 shell 脚本，在 Unix 上装完可能需要 `chmod +x`。

## 更新与回滚

### 更新前先看差异，本地改过就不覆盖

```bash
./skillmesh.exe update <name> --dry-run
```

输出是文件级的：哪些文件修改了（带行数增减）、新增了哪些、删了哪些。

判重和「本地有没有被改过」都用**内容摘要**（对「路径 + 逐文件 SHA-256」排序后哈希），不用 zip 的 sha256——同一份内容重新打包，压缩实现或权限位一变摘要就变，会把没变误判成变。装的时候摘要记进锁文件的 `installed_hash`。

本地有未提交修改时，`update` 会打印「跳过 xxx —— 本地有改动，保留本地版本」，不动你的文件、锁文件也不改，退出码仍是 0（其余 Skill 照常更新）。

### 回滚到旧版本

```bash
./skillmesh.exe install <skill-name> --version <旧版本>
```

版本制品不可变，所以回滚不是「改回去」，而是**再装一次更早的版本**——服务端那份旧制品一直原样留着。装完锁文件里的版本号、摘要一起回退。

只支持注册中心来源（`install` 装的）。Git 来源要固定版本用 `skillmesh add <url> --ref <tag>`。

## 我在哪些机器上装了什么

```bash
skillmesh installations                    # 跨设备看安装全貌
skillmesh installations --status conflict  # 只看有问题的
skillmesh installations --check            # 巡检本机磁盘，把真实状态写回服务端
```

`install` 和 `uninstall` 会自动登记（设备 + 目录 + 版本 + 制品摘要 + 有没有本地改过）。服务端只记事实，不替客户端决定装不装。

`--check` 是客户端推状态：服务端不会自己去扫你的磁盘——它看不到。巡检只动**这台机器**的记录：

| 状态 | 什么时候是它 |
| --- | --- |
| `uninstalled` | 目标目录不在了 |
| `missing` | 目录还在，`SKILL.md` 不见了 |
| `conflict` | 本机内容被改过，云端也有内容不同的已发布版本 |
| `blocked` | 服务端记的那一版在云端已经不能用了（撤回、驳回） |
| `active` | 一切正常（内容对不上但云端没动时是 `active` + 「本地改过」） |

## 其余命令一览

| 命令 | 作用 |
| --- | --- |
| `list` | 列出本项目锁文件里登记的**本地安装**：Skill、Agent、版本/commit、安装路径、是否受管 |
| `update --check` | 检查远端有没有新 commit，**只报告不改动** |
| `update <name> --dry-run` | 看这次更新改了哪些文件，不落盘 |
| `scan` | 本机分类扫描：已管理 / 内容变化 / 疑似敏感 / 路径冲突 |
| `update --all` | 应用全部更新 |
| `update <name>` | 只更新某一个 |
| `install <name> --version <v>` | 回滚到指定版本（仅注册中心来源；名字取自锁文件） |
| `sync` / `sync --resolve` | 与个人私有空间对账，冲突按你的选择解决 |
| `watch start` / `stop` / `pause` | 后台同步监听 |
| `uninstall <name>` | 卸载（默认移进回收站，可找回） |
| `trash list` / `restore` / `purge` | 本机回收站：找回卸载掉的 Skill、清过期 |
| `cloud recycle list` / `restore <path>` | 云端回收站：找回云端某个 Skill 里被删掉的文件 |
| `cloud revoke <slug> <version>` | 撤回云端的某个版本（不可逆；要恢复只能发新版本） |
| `share <agent> --from <agent>` | 让一个 Agent 共用另一个 Agent 的技能目录（磁盘上只存一份） |

`update` 默认只报不装——要动手得显式给 `--all` 或指定 Skill 名字。作用域（`user` 还是 `project`）记在锁文件里，`update`/`uninstall`/`list` 都以记录为准，不会把项目级安装误当成用户级。

**`list` 和 `search` 看的不是一回事**：`list` 是**本地装了什么**（读项目里的 `skillmesh.lock.yaml`），`search` 是**云端有什么**（调 `/api/v1/skills`）。`publish` 只把 Skill 发到云端、**不在本地装任何东西**，所以发布完 `list` 仍然是空的——这是设计如此。

## 日志与审计：谁动过什么

两处记录，视角不同，别混：

| 记在哪 | 记什么 | 怎么看 |
| --- | --- | --- |
| 服务端审计流水 | 谁、什么时候、对哪个 Skill 做了什么（跨设备、权威） | 控制台「审计」页、`GET /api/v1/audit-events` |
| 本机操作日志 | 这台机器上发生过什么（本机细节） | `skillmesh logs` |

```bash
./skillmesh.exe logs                 # 最近 50 条
./skillmesh.exe logs --tail 200
./skillmesh.exe logs --skill yuanbao # 只看某个 Skill
```

日志在 `<缓存目录>/operations.log`（Windows 是 `%LOCALAPPDATA%\SkillMesh\Cache\operations.log`），一行一条 JSON，机器也读得动。

| 动作 | 什么时候记 |
| --- | --- |
| `sync.uploaded` | 同步把本地内容传上云端 |
| `sync.downloaded` | 同步把云端版本拉回本地 |
| `sync.local_deleted` | 云端删了，本地那份跟着进了回收站 |
| `sync.conflicted` / `sync.failed` | 冲突 / 失败 |
| `skill.published` | 发布 |
| `skill.installed` / `skill.uninstalled` | 安装 / 卸载 |
| `skill.archived` / `skill.restored` | 云端删除 / 还原 |

「无变化」不记：每次同步都刷一遍没有变化，日志会被噪声淹掉。

同步的这几类结果同时上报给服务端记审计——「本地那份被云端的删除带走了」只有客户端看得见，服务端那边只有版本记录。服务端审计的完整动作清单见[团队治理](团队治理.md#审计)。

## 全局参数

几个参数对所有命令都生效，写在子命令前面：

| 参数 | 作用 |
| --- | --- |
| `--server URL` | 覆盖服务端地址（连测试服、连本机时用） |
| `--json` | 输出稳定 JSON，供脚本调用（见下节） |
| `--quiet` | 减少普通提示，错误仍输出到 stderr |
| `--verbose` | 显示平时隐藏的完整绝对路径 |
| `--project PATH` | 指定项目根目录，不在项目目录里跑时用 |
| `--profile NAME` | 用指定的配置档（配置目录下的 `profiles/<名字>.yaml`） |
| `--timeout DURATION` | 命令整体超时，如 `30s`、`2m` |
| `--no-color` | 禁用 ANSI 色彩（本 CLI 输出本来就没有色彩，留着是为了脚本调用时不必判断） |
| `--yes` | 对可安全自动确认的提示采用默认值（**不**绕过安全阻断、权限与破坏性操作） |

**项目根目录**：默认从当前目录向上找带 `.git` 或 `skillmesh.yaml` 的目录——在仓库的子目录里跑命令，锁文件和项目级安装目录跟着仓库走，不跟着当前子目录走。`--project` 可以直接指定：

```bash
./skillmesh.exe --project D:/work/my-app list   # 看那个项目的安装清单
```

**配置档**：配置档是叠在 `config.yaml` 之上的一层，只写要改的项——换个服务端测一下不用把整套目录配置重抄一遍。放在配置目录的 `profiles/<名字>.yaml`：

```yaml
# %APPDATA%\SkillMesh\profiles\staging.yaml（Windows）
# ~/.config/skillmesh/profiles/staging.yaml（Linux/macOS）
server: http://127.0.0.1:8080/api/v1
agents:
  cursor:
    enabled: false
```

```bash
./skillmesh.exe --profile staging search react
```

配置档只写要改的项，没写的沿用 `config.yaml`。`agents.<名字>.enabled` 写 `false` 是显式关掉（布尔零值分不出「没写」与「显式关掉」，所以这一项按「写了就算」处理）。

**整体超时**：`--timeout` 管的是整条命令（`network.timeout_seconds` 管的是单个请求）。CI 里给一条命令封顶时间很有用：

```bash
./skillmesh.exe --timeout 2m sync     # 最多跑两分钟，超了就算失败
```

## 脚本与 CI 调用

`--json` 是全局参数，作用在 stdout 与退出码上：stdout 只出 JSON，提示和错误都走 stderr，失败时按错误类型给出不同的退出码。脚本靠这两个判断「成没成」「该不该重试」。

```bash
./skillmesh.exe --json list                 # 本地安装清单
./skillmesh.exe --json search react          # 云端搜索
./skillmesh.exe --json info <skill-id>      # 详情、版本、扫描结论
./skillmesh.exe --json installations        # 各台机器上装了什么
./skillmesh.exe --json logs --tail 20       # 本机操作日志
./skillmesh.exe --json scan --path ./skills # 本机扫描结果
```

目前支持 `--json` 的就是上面这六个命令。给别的命令加 `--json` 会在 stderr 提示「暂不支持」，输出仍是人读格式——不会悄悄混进半个 JSON。

失败时 stderr 给一个信封：

```json
{
  "ok": false,
  "code": "VALIDATION_FAILED",
  "message": "参数格式不对: skillId = not-exist-id-xyz",
  "exit_code": 2,
  "details": {
    "http_status": 400,
    "request_id": "req_39b67aa6e71f4880b7e56d2c76797467"
  }
}
```

`code` 沿用服务端 problem+json 的口径，同一个 `code` 在服务端和 CLI 里指的是同一件事。

退出码：

| 退出码 | 含义 | 脚本通常怎么办 |
|---:|---|---|
| `0` | 成功 | 继续 |
| `1` | 未分类错误 | 记下来人工看 |
| `2` | 参数或配置错误 | 改命令重试 |
| `3` | 未登录/认证失败 | 先登录或换令牌 |
| `4` | 权限不足 | 换账号，别重试 |
| `5` | 网络或服务端不可用 | 退避后重试 |
| `6` | 安全策略阻断 | 别重试，要人来处理 |
| `7` | 检测到冲突 | 要人来定夺 |
| `8` | 目标 Agent 或目录未检测到/不可写 | 检查环境 |
| `9` | 目标资源不存在 | 换目标 |

参数个数或 flag 写错也是 `2`，信封里 `code` 是 `INVALID_ARGUMENTS`。

CI 里没有浏览器，用环境变量传令牌并加 `--locked`，见[从注册中心装一个 Skill](#从注册中心装一个-skill)。

## 让 Agent 来管理 Skill

Claude Code、Codex 这类 Agent 可以替你查 Skill、装 Skill、把 Skill 发到团队——
但只能走 CLI，不能直接往 Agent 的 skills 目录写文件。直接写会绕过可见性、
审核与版本治理：装的东西不进注册中心，下次同步还会被当成「未受管的目录」。

### 1. 把 CLI 放到 Agent 找得到的地方

Agent 每次问 PATH 找 CLI 太靠不住，PATH 上的还可能是别的版本。用 `self-install`
把当前这个 CLI 发布到固定路径：

```bash
./skillmesh.exe self-install
# 已发布 C:\Users\<你>\AppData\Roaming\SkillMesh\bin\skillmesh.exe（版本 0.1.0）

./skillmesh.exe self-install --check
# 固定路径  C:\Users\<你>\AppData\Roaming\SkillMesh\bin\skillmesh.exe
# 版本      0.1.0
# 状态      可用
```

版本标记（`.version`）是**复制成功之后才写**的，所以状态只有三种：

| 状态 | 含义 | 怎么办 |
| --- | --- | --- |
| 有二进制且有 `.version` | 完整可用 | 直接用 |
| 有二进制没有 `.version` | 上次复制没成功 | 重跑一次 `self-install` |
| 什么都没有 | 还没发布过 | 跑一次 `self-install` |

### 2. 把操作契约装给 Agent

操作契约在 `apps/cli/skills/manage-skills/SKILL.md`，是一份带 frontmatter 的
Agent skill。复制到 Agent 的 skills 目录即可被自动加载：

```bash
# Claude Code
cp apps/cli/skills/manage-skills/SKILL.md ~/.claude/skills/manage-skills/SKILL.md
```

契约里写清了三件事：

- **心智模型**——库 / 可见性 / 版本 / 审核，Agent 才知道该发到哪里去。
- **不可逆操作要人工确认**——真删、软删除、发到团队、回滚版本，都得先问过人。
- **一条红线**——不许执行 `review approve`。审核人不能审自己发布的版本是
  双人复核的底线，让 Agent 自动审批等于绕开它。

契约里也带了 `--json` 输出与退出码的说明，Agent 脚本化调用时不用再猜。

## 诊断

### 诊断包

提交 issue 时把诊断包一起给出去，省得来回问「你的配置长什么样」：

```bash
./skillmesh.exe logs --bundle ./diagnostics.zip
```

包里四样东西：`version.txt`（版本与运行环境）、`config.yaml`（脱敏后的配置）、
`agents.txt`（各 Agent 的目录与可写状态）、`operations.log`（本机日志）。

**里面已经脱敏**：家目录换成 `~`，令牌、邮箱、带凭据的 URL 全部遮蔽，
日志逐行再脱敏一遍（脱敏功能上线前写的历史日志没有脱敏过）。
诊断包是要往外发的，别把原始文件直接贴出来。

### 环境检查与配置

```bash
./skillmesh.exe doctor        # 服务端地址、配置目录、git、登录状态、各 Agent 目录能否写
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

# 额外要扫描的目录：项目级、自定义目录。
# Agent 适配器给出的用户级目录默认就扫；这里放的是别的地方。
sync:
  extra_dirs:
    - ~/projects/app/.claude/skills
    - /srv/shared/skills
  # 本地删掉了整个 Skill，要不要也往云端传删除（软删除）。
  # 不填就是 false：目录扫不到不等于你删了它。
  # 开了之后后台同步（watch）也会照做。
  propagate_delete: false
```

`sync` 与 `watch` 都会扫 `extra_dirs` 里的目录——放了目录，变更一样会被同步。
