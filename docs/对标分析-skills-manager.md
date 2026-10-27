# 对标分析：skills-manager

对标对象：[xingkongliang/skills-manager](https://github.com/xingkongliang/skills-manager)（MIT，Tauri 2 + Rust + React 19 + SQLite，桌面应用）。

分析方式：拉取对方源码逐模块拆解（核心模型 / 多 Agent 适配 / 合并引擎 / Git 备份 / 桌面 UI / CLI），再与本仓库 API、CLI、Web 三侧逐项比对；每条借鉴项都回到本仓库代码里查证过，确认是真缺口而非命名差异。

## 一、两个项目的定位不是竞品

| | skills-manager | skillMesh |
| --- | --- | --- |
| 形态 | 单机桌面应用 | 云端注册中心 + CLI + Web 控制台 |
| 核心动作 | 把 50+ 编码工具的 Skill 收进本机中心库，再部署回各 Agent 目录 | 云端管元数据/权限/版本/制品，本地只负责发现、校验、安装 |
| 信任模型 | 单机用户自己是信任边界 | 组织/团队为边界，有角色、审核、审计、签名 |
| 协作 | Git 仓库天然多端克隆 | 服务端对账（sync_states / sync_changesets） |

所以可借鉴的不是 UI 形态，而是**「技能从中心库落到 Agent 目录」这一层的工程设计**。桌面壳、托盘、纯 Git 备份这些形态类的东西不契合 skillMesh 的定位，明确不采纳。

## 二、skills-manager 里值得看的六个机制

1. **ToolAdapter 数据表**（`core/tool_adapters.rs`）——56 个 Agent 用一张纯数据表描述：部署目录、探测目录、额外发现根、是否递归扫描、用户级与项目级不对称路径。加一个 Agent 只改数据，不改代码。
2. **对象级三方合并**（`core/merge/`）——按「技能」而不是按文本行做合并。content / 路径 / attrs 各自独立判定，任一组件冲突则整个技能冲突，**绝不半合并**；冲突前打安全快照，每个决定可撤销。
3. **冲突三选一 + 机器可读错误信封**——保留本机 / 使用远端 / 两个都保留，失败输出 `{"ok":false,"code":"TARGET_CONFLICT","details":{"conflicts":[{"path","reason"}]}}`，退出码区分参数错与运行错。
4. **命令面板与 Agent 角标**（`CommandPalette.tsx`、卡片上的 Agent 图标角标）——卡片为每个已启用 Agent 显示一个角标，点角标直接装/卸，实时反映同步状态。信息密度比「一个技能一张表」高一个量级。
5. **Agent 反向驱动管理器**（`skills/manage-skills/SKILL.md` + `core/cli_bridge.rs`）——把产品能力写成 Agent 的操作契约，CLI 发布到固定路径（`~/.skills-manager/bin`）并最后写 `.version` 标记做原子发布，Agent 侧按「有 stamp / 有二进制无 stamp / PATH 上可能更旧」三态探测。
6. **日志脱敏与诊断包导出**（`core/log_sanitize.rs`）——家目录转 `~`、URL 内嵌账号密码剥离、令牌前缀（`ghp_` / `sk-` / `xox*`）遮蔽、邮箱遮蔽；再把日志打包成 zip 供用户提 issue。

## 三、本仓库已经有的（不要重复造轮子）

以下看着像缺口，实际本仓库已有等价实现，对方的做法只是形态不同：

- **部署归属登记**：本仓库有三套——本地 `skillmesh.lock.yaml`（含 `target_path`/`installed_hash`/`scope`/`update_policy`）、服务端 `installations` 表（`target_path`/`installed_sha256`/`local_modified`/`status`）、本机 `oplog` 流水。对方只是一张 `skill_targets` 表。
- **本地修改识别**：`update.go` 的 `locallyModified()` 拿现场摘要与锁文件 `installed_hash` 比对，改过就跳过并保留本地版本；`installcheck.go` 的 `JudgeInstall` 逐条判定 `local_modified`/`conflict`/`blocked`。
- **卸载保护**：默认进回收站（`trash.NewStore`），`--force/--purge` 才真删。
- **冲突报告路径**：目标冲突时错误信息已带绝对路径（`add.go` 的 `CheckTarget`）。
- **原子安装**：staging 临时目录 → rename → 失败回滚（`installer.go` 的 `StagingPrefix`）。
- **Agent 三态探测**：`skillmesh agents detect` 已输出「无配置 / 不存在 / 存在」。
- **技能名校验**：`cmd/skillname.go` 的 `CheckDirName` 是统一入口，且是拒绝式（fail closed），挂在 add/install/update 三条落盘路径上。
- **批量发版**：`skillmesh publish --all` 已实现，网页端本就不该做（控制台定位是「发布与安装走 CLI」）。
- **绝不半合并**：`conflict.go` 的 `stageMerged` 一旦有 conflicted/deleted 就不执行 writeBack。
- **签名与内容扫描、审核流、权限可见性、组织团队、回收站回滚**——这些是 skillMesh 的核心，对方完全没有。

## 四、借鉴清单

优先级按「值不值得现在做」排。标 ⚠ 的是验证时发现原建议描述有偏差、已收窄的。

### P0 — 建议优先做

**1. CLI 全局 `--json` 输出契约 + 分级退出码 + 结构化错误信封** —— ✅ **已实现（2026-10-27）**

`docs/详细设计.md` §3.2 已经写死了这套契约（`--json`、`--quiet`/`--verbose`/`--no-color`、0~9 分级退出码、「stdout 只放 JSON，日志与提示进 stderr」），原先**设计已定、实现为空**：只有 `status` 恒 JSON 和 `scan --json` 两处特例，list/search/info/installations/sync/logs 全是人读表格；错误出口只有 `fmt.Fprintln(os.Stderr, "错误:", err)`，退出码一律 1。

已落地：

- 新增 `cmd/exitcode.go`：0~9 退出码常量、按服务端 `code` 分类的映射表、错误信封 `errorPayload`（`ok`/`code`/`message`/`exit_code`/`details`）。
- 新增 `cmd/output.go`：全局 `--json`/`--quiet`、`writeJSON()`、`notice()`（提示一律走 stderr）。
- `apiclient` 增加 `NetworkError` 类型，把「没拿到答复」和「服务端明确拒绝」分开。
- `--json` 覆盖 `list`、`search`、`info`、`installations`、`logs`、`scan` 六个命令；其余命令加了 `--json` 会在 stderr 提示「暂不支持」，不会悄悄混进半个 JSON。
- 参数个数或 flag 写错退 `2`，信封 `code` 为 `INVALID_ARGUMENTS`。
- 错误码沿用服务端 problem+json 的口径，**没有引入对方那套 `kind`/`details` 词表**，避免两套错误码并存。
- 退出码由错误类型推出：服务端 `FORBIDDEN` 退 4、`NOT_FOUND` 退 9、`SYNC_CONFLICT` 退 7、`RATE_LIMITED` 退 5，认证失败退 3；没登记的错误码退回按 HTTP 状态判断。

还没做的：`--verbose`、`--no-color`、`--yes`、`--timeout`、`--profile`、`--project` 仍未实现（`--agent`/`--scope`/`--dry-run` 仍是各命令的局部参数，尚未提升为全局）。这些同样是 §3.2 写死、实现为空的设计欠账。


**2. Agent 反向驱动入口（收敛版）** —— ✅ **已实现（2026-10-27）**

补「Agent 可读的操作契约 + CLI 固定可发现路径 + 稳定 JSON 输出」，**复用已有的 agents detect / 原子安装 / dry-run 巡检，不新增命令树**。

已落地：

- 新增 `apps/cli/skills/manage-skills/SKILL.md`：带 frontmatter 的 Agent 操作契约，写清四层心智模型、不可逆操作的人工确认点、`--json` 与退出码口径。装进 Agent 的 skills 目录即可被自动加载。
- 契约里写死了红线：**不得让 Agent 执行 `review approve`**——审核人不能审自己发布的版本是双人复核的底线，让 Agent 自动审批等于绕开它。
- 新增 `skillmesh self-install` / `self-install --check`：把 CLI 发布到 `<配置目录>/bin/skillmesh`，**复制成功之后才写 `.version` 标记**，Agent 侧据此判三态（有标记 = 可用 / 只有二进制 = 上次复制失败 / 都没有 = 未发布）。
- 复用了已有的 `agents detect`（三态探测）与原子安装，没有新增命令树。对方把 `diff/report/installcheck/synclog` 做成独立子命令是命名差异——本仓库已由 `update --check --dry-run`、`installations --check`、`scan --json`、`publish --dry-run`、`doctor` 覆盖，照搬只会加剧命令树不一致。

还没做的：CLI 的分发形态（release 归档 / 容器镜像）没定，固定路径目前靠 `self-install` 命令发布，不是安装器自动完成。

**3. CLI 日志与 doctor 输出脱敏** —— ✅ **已实现（收敛版，2026-10-27）**

已落地：

- 新增 `apps/cli/internal/redact`：`Path()` 把家目录换成 `~`、家目录外的绝对路径只留末两段；`Text()` 处理 URL 内嵌账号密码、凭据前缀（`ghp_`/`sk-`/`xox*` 等）、邮箱、私钥块，以及夹在自由文本里的绝对路径。
- `oplog` 落盘前统一脱敏——一处生效，覆盖所有写入点。顺带把 `install`/`uninstall` 的 detail 里的路径挪到 `extra.path` 字段（结构化数据不该混在描述里）。
- `list` 默认显示脱敏后的路径，`--verbose` 才显示完整（详细设计 §3.9 的原话）。
- `doctor` 的错误文本脱敏 Token 与凭据；路径仍显示完整——doctor 是诊断命令，用户要看自己的路径。
- 新增 `cmd/panic.go` + `main.go` 的 recover：崩溃写 `<配置目录>/last_panic.log`，调用栈只留函数名（不带本机源码路径）。
- 服务端新增 `common/Redact`，把 `installation.unregister` 审计流水里的绝对路径压成末两段（`installation.register` 原本就不记路径，这次补齐了唯一的漏点）。

后来补上了诊断包导出：`skillmesh logs --bundle <路径>` 打包 version/config/agents/log 四样，日志逐行再脱敏一遍（脱敏功能上线前写的历史日志没脱敏过），错误信息里的绝对路径也一并脱敏。


**4. 控制台多选与批量操作** —— ✅ **已实现（2026-10-27）**

已落地（审核队列批量放行）：

- 新增 `apps/web/src/composables/useMultiSelect.ts`：多选状态机。筛选一变裁剪选择、作用域一变清空选择、条目被删自动摘掉——「选中的东西已经不在眼前」时按批量放行，放行的对象就和以为的不一样。
- 新增 `apps/web/src/components/MultiSelectToolbar.vue`：只在有选中时出现，2~3 个高频动作 + 「取消选择」。
- `ReviewQueuePage.vue` 接入：每行加勾选框，批量放行/拒绝有二次确认，结果分「过了几条 / 没过几条」报出来。
- 后端新增 `POST /api/v1/reviews/batch-decide`：**逐条独立**（每条一个独立事务，共用一个事务的话第一条失败会把已经放行的也撤回来），权限不放宽（每条照样走 reviewer 角色、不能审自己发布的版本、扫描结论阻断）。
- 测试 `ReviewBatchDecideTest`：一条过一条不过两边都算数，且过的那条版本状态真的变成 published。

后来补上了我的 Skill 批量打标签与批量删除：**权限口径就定为「与单条操作同一套」**——批量端点逐条复用单条的判定（改标签只有属主能改，删除是属主或团队/组织的 publisher 及以上），不需要新权限。服务端 `PATCH /skills/batch/tags` 与 `POST /skills/batch/archive`，同样逐条独立事务。

批量发版与批量改可见性按原建议排除：前者 CLI `publish --all` 已有，网页端加按钮与既有边界冲突；后者可见性创建时定死、接口层就不可能。


**5. 标签作为检索维度** —— ✅ **已实现（2026-10-27）**

已落地：

- 后端新增 `GET /api/v1/tags`：可见范围内的标签与各自数量（`mine=true` 时只统计我名下的）。口径与搜索一致：可见范围 + 未归档。**路径不能挂在 `/skills` 下**——`/skills/{skillId}` 那一段是变量，`tags` 会被当 UUID 解析，报出来的是「参数格式不对」，看不出是路由撞了。
- 后端 `GET /skills` 加 `untagged=true`：`cardinality(s.tags) = 0`，支持「未打标」筛选。
- 新增 `apps/web/src/components/TagFilter.vue`：多选标签（同时满足）+「未打标」，两者互斥。
- `MarketPage.vue` 与 `MySkillsPage.vue` 接入，市场页与「我的 Skill」页都能按标签筛。
- 测试 `SkillTagFilterTest`：标签清单只统计看得见的（别人的私有标签不泄露）、未打标筛出的是真的没标签的。

按原建议没做的：没有照搬 `pruneStaleTagFilters`——标签清单由服务端单独给（`/api/v1/tags`），前端不从一页数据里汇总，因此不存在「汇总不全导致静默空列表」的问题。


**6. 目标冲突的机器可读错误码** —— ✅ **已实现（2026-10-27）**

已落地：

- 新增 `apps/cli/internal/installer/target.go`：`ClassifyTarget` 用 `os.Lstat` 区分四种形态（不存在 / 真实目录 / 真实文件 / 符号链接）——用 Lstat 而不是 Stat 是因为 Stat 跟着链接走，看到的是链接指向的东西，而这里要判断的恰恰是「这个路径本身是不是链接」。
- 新增 `installer.TargetConflictError`：带 `target_path` 与 `reason`（`unmanaged` / `symlink` / `not_dir` / `managed_by_other`），错误信封给 `{"code":"TARGET_CONFLICT","target_path":...,"reason":...}`，退出码 7。脚本不再需要匹配中文文案——改一次文案就断一次。
- `CheckTarget` 改造：挡符号链接、文件、用户手写的同名目录，以及**装的是另一个 Skill** 的情况（marker 里记的 Skill ID 与这次要装的不一致）。这是最危险的一种——会覆盖掉用户的本地修改。
- `installer.InstallAtomic` 也拒绝符号链接目标：`os.Rename` 挪走的是链接本身，而链接指向的可能是用户自己的目录。
- 测试：`installer/target_test.go` 与 `cmd/target_test.go` 覆盖四种形态与四种冲突原因。

按原建议没做的：没有引入 `ReplacePolicy` 三档——对方是符号链接式安装模型，本仓库是复制 + 暂存原子替换（`CopyTree` 明确跳过符号链接），`LinkToSource`/`ForeignLink` 两大态不适用。


### P2 — 有余力再说


**7. Agent 适配层数据化** —— ✅ **已实现（2026-10-27）**

已落地（放开自定义 Agent）：

- 服务端 `installations.agent` 不再限定枚举：新增迁移 `V3__installation_agent_freedom.sql` 去掉 CHECK，改成「非空且不超过 64 字符」。Agent 目录是本机配置的事（config.yaml 的 `agents` 段），服务端不该替用户限定有哪些 Agent。
- `InstallationController.requireAgent` 取代 `requireOneOf`：不限取值，但仍挡空串与超长名。
- CLI 侧不再吞掉登记失败的 400：`reportInstallation` 返回错误，装完没登记成功会往 stderr 打提示——装完看着成功、跨设备却看不到这条记录，是最难查的一类问题。

- `agent.Supported` 的硬编码清单换成 `agent.Names(Cfg)`（读配置里的 agents 段）：`doctor` 与 `agents detect` 的 Agent 集合口径统一了，自定义 Agent 在 agents detect、scan --all-agents、sync 三处都看得见。

- config.yaml 的 agents 段支持 `extra_scan_dirs`（这个 Agent 自己的额外扫描根）与
  `recursive_scan`（不填就是递归；目录扁平时填 false，免得把技能自带的子目录也当成候选）。
  `skillscan.Candidates` 因此多了一个递归开关，scan / sync / watch 都按各自 Agent
  的配置走。

按原建议没做的：适配表由云端下发没做——要先定「路径属本机、约定属云端」的边界。
路径本来就是本机配置的事，云端下发只适合给「有哪些 Agent」这类清单。


**优先单独处理的缺陷**：`install.go:192` `RegisterInstallation` 的 400 被吞（`_, _ =`），导致自定义 Agent 装完不上报云端且无任何提示——本地成功、云端治理视角静默丢记。最小修法是检查错误并给出提示。

对方的「四层合并」（内置/路径覆盖/项目级覆盖/自定义新增）**语义不可直接移植**：那是「一个工具的多个来源目录按优先级合并成一份 skill 视图」，本仓库的模型是「按 Agent 装到唯一目标目录 + 按 (skill, agent) 各自记同步基线」。若要引入合并层，得先回答「同一 skill 同时存在于用户级与项目级时以谁为准、同步基线记在哪个空间」——这是 skillMesh 自己的设计题。

**8. 本地安装目录名的 Windows 合法性校验** —— ✅ **已实现（2026-10-27）**

已落地：

- `CheckDirName` 补齐 Windows 口径：保留字符 `<>"|?*`、控制字符、尾随的点与空格、设备名（`CON`/`NUL`/`COM1`…，带扩展名也算——`COM1.txt` 在 Win32 上照样指向串口设备）。
- **拒绝而不是替换**：替换会让安装目录名与声明名不一致，破坏 update 与锁文件的按名匹配。对方那套「替换成 _ 保单射」也不成立——`a<b` 与 `a>b` 都变成 `a_b`，仍是多对一。
- `publish` 侧也跑同一套校验：问题挡在上传前，而不是等终端用户装到 Windows 上失败、只看到一句原始的操作系统错误。
- 测试 `skillname_test.go` 覆盖 16 种形态。

另一半（同名塌缩）已由上一轮的 `CheckTarget` 覆盖：目标装的是另一个 Skill 时报 `managed_by_other`，不再静默整目录覆盖。


**不要照搬对方的「替换成 _」**：本仓库现有的是拒绝式校验（`CheckDirName` 拒绝而非替换），拒绝优于替换——替换会让安装目录名与声明名不一致，破坏 update/lockfile 的按名匹配。对方真正有价值的、而本仓库没有的是两点：**Windows 设备名加前缀**（CON/PRN/AUX/NUL/COM1/LPT1）与**尾部点/空格裁剪**。

- 现状 `CheckDirName` 只挡 `/ \ :`，全仓无 Windows 保留字符（`<>"|?*`）、控制字符、尾随点/空格的判定。含 `<>"|?*` 的名字在 Windows 上 MkdirAll 直接失败，报的是原始 OS 错误；`foo.` 在 Win32 语义下被裁成 `foo`，与已存在的 `foo` 目录同名，而 `CheckTarget` 只判断「是否受 SkillMesh 管理」，受管理就直接放行覆盖。
- 补字符集的同时，让 `publish` 侧也跑同一套校验，把问题挡在上传前而不是用户安装时。
- ⚠️ **同名塌缩是独立问题，不要并进这个字符集 PR**：本地目录名取发布方声明的 `SKILL.md` name，服务端身份是严格 slug，两者可以不同；`publish.go:211` 用目录基名派生 slug、用 `metadata.Name` 作展示名，全程不调 `CheckDirName`，所以注册中心允许两个不同 slug 声明同一个 name，二者装到同一个 `base/name`，第二次安装会整目录替换第一次，用户的本地修改随之丢失。`install.go:149` `MarkInstalled` 只写不比对，没有任何「这个目录属于另一个 skill」的守卫。这是**归属校验**问题，要单独一条任务。
- 顺带发现同源缺陷：`syncdelete.go:187` 用未校验的 `skillscan.SkillName` 构造回收站条目名，`trash.go:44` 的 `entry = name + 时间戳` 再 `filepath.Join`，name 里带 `/` 会让条目落到 trash 目录之外（`manage.go:191`、`update.go:418` 走的是已校验路径，不受影响）。

**9. 来源引用有序判别 + 本地目录与 zip 离线导入** —— ✅ **已实现（2026-10-27）**

已落地（判别与拒绝）：

- `gitx.NormalizeURL` 明确拒绝本地路径（`./x`、`../x`、`~/x`、POSIX 绝对路径、Windows 盘符、UNC）与 `owner/repo` 这样的简写，并给出可操作的提示。原先 POSIX 绝对路径能真的克隆成功，而手册写着「本地路径会被拒绝」——文档与实现对不上，且失败时报的是 `git: 'lsr' does not appear to be a git repository`，用户看不懂。
- 测试 `git_test.go` 覆盖远程地址放行、本地路径与简写拒绝、内嵌凭据拒绝。

- 本地目录与 zip 离线导入：`skillmesh add --local <目录>` 与
  `skillmesh install --file <zip>`。
  **口径是「本地来源不进注册中心」**：它们没有签名、没走过服务端的扫描与审核，
  登记上去等于让未验证的内容冒充发布方的版本。只装到本机，来源写进锁文件
  （`source_type: local`）；客户端遇到没签名的版本本来就只标注「无签名」而不阻断，
  签名闸不因此放松。两个不同的本地目录装同一个名字会被认成不同来源，不互相覆盖。

「有序判别表」没做成显式的数据结构——现在靠 `looksLikeLocalPath` 的规则集，
等真有第二种来源类型时再抽表。


- 对方的三分是「本地路径 / Git / skills.sh」，**skills.sh 那一支在 skillMesh 无对应物、应剔除**；应改成 skillMesh 自己的三分：本地路径 / Git 远程 / 注册中心 slug。
- 落地时必须同时修既有缺陷：`NormalizeURL` 对 POSIX 路径原样放行（实测 `./skills`、`../skills`、`/abs/path`、`~/skills` 全部 `err=nil`），而 `cli-使用手册.md:154` 明写「本地路径会被拒绝」；实测**绝对 POSIX 路径能真的克隆成功**。Windows 盘符挡住了，但 `git.go:29-32` 专门给 `C:\repo` 写的分支是死代码，报错口径误导（`只允许 https/ssh，实际为 c`）。
- 本仓库服务端模型其实预留了本地来源：`V1__init.sql:136` 的 `source_type` 枚举含 `local_import`、`provider` 含 `local`、`ref_type` 含 `local`，`SkillSourceInput.java:14` 也接受 `local_import`；`详细设计.md:1894` 明写 install 支持「已登记的本地来源名」——**模型层已就位，客户端入口缺失**。
- ⚠️ **边界**：本地目录/zip 离线装如果做成「经服务端登记的正式安装」，会绕过 `install.go:122` 的制品验签与安装登记，与治理模型冲突。建议按 `add` 的既有语义分层——本地来源只写本地锁文件、不产生服务端安装记录，或显式标注为「未验签来源」。

**10. 技能集合（Collection）** —— ✅ **已实现（2026-10-27）**

这不是技术欠账，是产品决策。

服务端可命名的一组技能 + 设备维度已装数量徽标。

- 缺口属实：`V1__init.sql` 的 21 张表里没有任何 group/preset/collection 表；唯一的「分组」味道是 `skills.tags`（扁平字符串标签，没有成员实体、没有顺序、没有「这一组在设备上的安装状态」）。
- 计数字段必须先定义口径：一条 installation 是 (user, device, target_path, skill, agent)，同一台机器可能装 3 个 agent。「3/5」按 distinct `device_id` 算还是按 distinct `(device_id, agent)` 算，UI 上要标清。
- 权限是新增的：团队级覆盖率意味着能看到「谁的哪台机器装了什么」，当前 installations 只对本人可见，需单独的团队/组织角色授权，不要顺手开放。
- 与 `add <repo> --all` 的重叠要说明：集合要卖的是「团队一次配好、逐台机按需装」，不是重复批量安装。
- 已落地：服务端新增 `skill_groups` 表与 `/api/v1/groups` 端点（成员存 uuid 数组，
  可见性口径与 Skill 同一套）；CLI 新增 `group list / show / create / install`；
  网页端新增「集合」页（列成员、标已装未装、给出装齐的命令）。
- **徽标只算「我装了几个」**：本人可见的数据。团队级的覆盖率（谁在哪台机器上装了多少）
  是另一种可见性，单独授权，没混进来——那是这套设计里唯一有产品决策风险的部分。

**11. 控制台 ⌘K 全局命令面板** —— ✅ **已实现（2026-10-27）**

已落地：

- 新增 `apps/web/src/components/CommandPalette.vue`：⌘K / Ctrl+K 打开，搜 Skill 或跳页面。
  搜 Skill 复用现成的 `GET /skills?q=`（服务端算真值），取前 8 条；页面动作是静态的五个入口。
  上下键移动、回车执行、Esc 关闭，命中项随鼠标悬停同步。
- `App.vue` 挂载，登录后任意页面都能唤起。
- 零后端改动。



## 五、明确不借鉴

| 对方能力 | 不借鉴的原因 |
| --- | --- |
| 桌面壳、系统托盘、应用内自更新 | skillMesh 是 Web + CLI，纯形态差异 |
| 纯 Git 远端备份 + 多设备克隆同步 | 备份诉求已由 trash/recycle/rollback 覆盖；采纳等于把产品拖成第二个桌面应用 |
| skills.sh 市场聚合 | skillMesh 是注册中心（发布方），不是聚合方 |
| i18n 多语言层 | 纯产品化补齐，与云端治理无直接关系，等客户明确要求再做 |
| 符号链接部署 + `ReplacePolicy` 三档 | 本仓库是复制 + 暂存原子替换，`CopyTree` 明确跳过符号链接 |
| 一次性把扫描发现全部灌进中心库 | 与治理模型冲突：逐条建 Skill、逐版本过扫描与审核、slug 全局唯一、密钥扫描阻断发布 |

可借鉴的一条边角：对方「任何恢复/冲突解决动作前先落一个可回退快照」——本仓库 recycle restore 产出新版本，可复用该流程。

## 六、能力差异速查

| 能力 | skills-manager | skillMesh | 判定 |
| --- | --- | --- | --- |
| 多 Agent 适配层 | 55 条 ToolAdapter 数据表 | 3 个硬编码字符串 + config.yaml 覆盖 | 形态不同，本仓库弱 |
| 部署写入模式 | symlink/copy 双模 + 三级降级 | 复制 + staging 原子替换 | 形态不同 |
| Preset / 场景分组 | scenarios 三元组 + 三态徽标 | 无 | 对方独有 |
| 标签体系 | skill_tags 表 + 筛选 + 批量打标 | tags text[] + `GET /skills?tag=`（CLI 也能查） | 本仓库后端有、前端缺 |
| 批量操作 | 多选状态机 + 工具条 | 无 | 对方独有 |
| 本地扫描发现 | discovered_skills 台账 + 批量收编 | scan 只报告，publish --all / sync 已能收编 | 形态不同 |
| 合并引擎 | 对象级三方合并 + 组件级判定 | 文件级判定 + 文件内行级合并 + 绝不半合并 | 本仓库不弱 |
| 冲突三选一 | keep local / remote / both | `--resolve` 四选一 | 都有 |
| Git 备份 | 本地 Git 仓库 + 快照 tag | 服务端 DB/制品备份 | 定位不同 |
| CLI 机器可读契约 | 全局 --json + 错误信封 + 分级退出码 | 仅两处特例，错误是中文句子 | 本仓库缺 |
| Agent 反向驱动 | SKILL.md 契约 + 固定路径 CLI | 无契约、无固定路径 | 对方独有 |
| 制品签名 / 内容扫描 / 审核流 / 权限 / 组织团队 / 回收站 | 无 | 齐备 | 本仓库独有 |
| 差异展示 | 前端 DP 求 LCS + hunks | 只到文件级增删行数 | 本仓库弱 |
| 命令面板 | ⌘K 跨四组 | ⌘K 跳页面 + 复用 `/skills?q=` 搜 Skill | 双方都有 |
| 技能集合 | 场景×技能×Agent 三元组 | 服务端集合 + CLI 装齐，徽标只算本人 | 双方都有（形态不同） |
| 本地/zip 来源 | 本地目录与 zip 直接装 | `add --local` / `install --file`，不进注册中心 | 双方都有（口径不同） |

## 七、一个规律

对方是**单机应用**，很多能力（本地文件库、仓库锁、内存全量列表、符号链接安装）建立在「用户自己是信任边界」之上；skillMesh 是**多租户云端治理平台**，信任边界在组织和团队，状态在服务端、客户端只做薄执行。

所以借鉴时的判断标准是：**这条能力在有服务端真值的前提下还缺不缺**。多数被否决的建议（部署归属表、合并粒度、批量收编、冲突分类），在 skillMesh 的模型里已经由服务端真值 + 本地锁文件解决了；而真正该借的，集中在两端——**客户端输出契约与安全边界**（--json、错误信封、脱敏、Windows 路径合法性、Agent 契约），以及**控制台的信息密度**（多选批量、标签筛选、命令面板、Diff 视图）。
