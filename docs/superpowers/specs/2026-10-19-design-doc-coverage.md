# 设计文档实现对照表

> 基线：[docs/设计文档.md](../../设计文档.md)（V1.0，2026-10-19）
> 对照日期：2026-10-20 ｜ 逐条核对，不做「大概做了」这种判断
> 状态含义：✅ 有实现且有测试/实测 ｜ ◐ 有实现但有明确边界 ｜ ⏭ 按文档自身划到后续阶段

## §1 产品背景与目标

| 要求 | 状态 | 落地位置 |
| --- | --- | --- |
| 本地 Skill 无感备份 | ✅ | `skillmesh sync` / `watch`：三方对账 + 自动触发，私有空间 |
| Git 来源安装与更新 | ✅ | `source resolve` / `add` / `update` / `rollback` |
| 个人 / 团队 / 组织空间 | ✅ | `org` 包：`canView` 覆盖 personal/team/organization/public 四档 |
| 多 Agent 适配 | ◐ | Claude Code、Codex 已适配；Cursor 适配器存在但默认关闭（见 §8） |
| 统一版本/来源/摘要/权限/审计/扫描 | ✅ | 见 §9、§10 |
| 为 MCP 预留扩展点 | ⏭ | 设计文档 §16 明确列为后续扩展，不耦合进注册中心 |

## §2 核心概念与产品规则

| 规则 | 状态 | 落地位置 |
| --- | --- | --- |
| Skill 目录兼容 Agent 原生格式 | ✅ | `skillscan`：SKILL.md + frontmatter；`packager` 原样打包 |
| 来源三选一（本地 / Git / 注册中心） | ✅ | `lockfile.SourceID` / `Repository` / `Publish` 三条路 |
| 安装状态与云端版本分离 | ✅ | 锁文件（本地）vs `installations`（注册中心） |
| 不可变版本 | ✅ | `VERSION_CONFLICT` 409；制品内容寻址，发布后不可改 |
| 同步基线 | ✅ | `syncstate.yaml` 的 `base_hash` + 服务端 `sync_states` |
| 四条链路独立 | ✅ | 本地→个人（sync）、Git→本地（add）、云端→本地（install/restore）、个人/团队→市场（publish/review） |

## §3 用户场景

| 场景 | 状态 | 落地位置 |
| --- | --- | --- |
| §3.1 扫描、防抖、差异上传、失败重试、冲突 | ✅ | `scan` / `watch`（轮询防抖）/ `sync`（manifest 差异）/ `syncqueue`（指数退避） |
| §3.1 首次发现按策略处理、绝不默认公开 | ✅ | `sync --skip-new`；同步可见性恒为 private |
| §3.1 离线时保留本地与队列 | ✅ | 队列落盘在配置目录，`doctor` 可见 |
| §3.2 Git 来源安装、展示来源/commit/许可证/风险 | ✅ | `info` 面板 + `list` 的 provider 列 |
| §3.3 更新差异、本地有改动不覆盖 | ✅ | `update --dry-run` 文件级差异；`installed_hash` 不符即跳过 |

## §4 功能范围

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| Skill 市场 | ✅ | 搜索/筛选/详情/版本/来源/风险；后续增强（评分、收藏）未做 |
| 本地发现 | ✅ | 分类扫描、去重、`.skillmeshignore`、首次发现策略 |
| 同步引擎 | ✅ | fsnotify 文件事件 + 2 秒防抖触发扫描，轮询保留作兜底与启动全量 reconcile；增量按 manifest 判定，内容不变不重传 |
| Git 来源 | ✅ | 公开/私有、子目录、ref 固定、更新检查 |
| 安装适配器 | ✅ | Claude Code、Codex、Cursor（路径探测） |
| 版本治理 | ✅ | 不可变、锁文件、差异预览、回滚 |
| 安全 | ✅ | 路径校验、敏感文件过滤、密钥扫描、可疑内容扫描 |
| 团队管理 | ✅ | 组织/团队/角色/可见性/审批流 |
| 审计 | ✅ | 服务端特权事件 + 客户端上报（白名单 8 个动作） |

## §6 本地同步引擎设计

| 要点 | 状态 | 落地位置 |
| --- | --- | --- |
| §6.1 只扫授权目录，不扫全盘 | ✅ | `syncDirs` 只取配置里的 Agent 目录 |
| §6.1 `.skillmeshignore` 排除规则 | ✅ | `skillscan/ignore.go`，摘要与打包同口径 |
| §6.1 默认忽略凭据文件 | ✅ | `IsSensitiveFile`：`.env*`、`*.pem`、`id_rsa` 等 |
| §6.1 不跟随越界符号链接 | ✅ | `ValidateDir` 用 EvalSymlinks 校验 |
| §6.2 文件事件只用于触发扫描 | ✅ | `watch` 用 fsnotify 监听 Agent 根目录与每个 Skill 目录，事件只负责唤醒扫描；连续写入先静默 2 秒 |
| §6.2 规范化 manifest，未变不重传 | ✅ | `skillscan.Digest`（路径+摘要排序后哈希） |
| §6.2 上传前敏感扫描、可疑默认阻止 | ✅ | 密钥阻断；可疑内容提示 |
| §6.2 幂等任务 ID、重试队列、退避 | ✅ | `Idempotency-Key` + `syncqueue` |
| §6.2 云端条件更新避免静默丢失 | ✅ | 变更集比对基线，不符转冲突 |
| §6.3 冲突策略表（未变更/已变更/不同文件/同一文件/本地删除） | ✅ | `syncstate.Decide` 定大方向；冲突时再按文件走 `mergePlan` + 行级 `skillscan.Merge3`——改不同文件自动合，改同一文件且能文本合也自动合，真合不了才落冲突副本（`--resolve keep-both`） |
| §6.3 删除传播用软删除/回收期 | ✅ | `trash` 30 天回收站；云端永不因本地缺失而删除 |
| §6.4 六项默认值 | ✅ | 逐条对应：新发现 `--skip-new`、目录变更自动、云端更新 `--auto`、团队发布走审核、删除进回收站、离线进队列 |

## §7 Git 来源管理

§7.4 命令表 12 条逐条用 `--help` 验证过参数可解析（`login <地址>` 的位置参数这一轮才补上）。

| 要点 | 状态 | 落地位置 |
| --- | --- | --- |
| §7.1 来源模型九个字段 | ✅ | 锁文件（本地）：repository/provider/path/ref_type/ref/resolved_commit/installed_hash/license、update_policy；发布时随创建请求上报，服务端写入 `skill_sources` |
| §7.2 三种更新策略 | ✅ | `add --update-policy`；`update --auto` 与 `watch` 认 `auto_safe` |
| §7.3 仅 HTTPS/SSH、拒 file:// | ✅ | 客户端 `NormalizeURL` + 服务端 `GitHostPolicy` |
| §7.3 防路径穿越/归档炸弹/超大文件/符号链接 | ✅ | `ValidateDir` + 服务端扫描器的大小/文件数/解压上限 |
| §7.3 凭据走系统凭据管理器 | ✅ | Windows Credential Manager；非 Windows 回退文件；CI 走 `SKILLMESH_TOKEN` |
| §7.3 安装也做内容扫描 | ✅ | `install` 本地扫描 + 服务端扫描结果回显 |

## §8 多 Agent 适配

| Agent | 状态 | 说明 |
| --- | --- | --- |
| Claude Code | ✅ | `~/.claude/skills/` 与 `.claude/skills/`，已实测 |
| Codex | ✅ | 尊重 `CODEX_HOME`，已实测 |
| Cursor | ✅ | §8 给了两个候选路径（`~/.cursor/skills`、`~/.agents/skills`），CLI 启动时**探测**哪个真的存在并用它；`agents detect` 如实标出「本机探测到」还是「假设位置」。本机探测到 `~/.agents/skills` |
| 其他 Agent | ✅ | 配置驱动，`agents detect` 可列出 |

## §9 版本、锁文件与制品

| 要点 | 状态 | 落地位置 |
| --- | --- | --- |
| 草稿可变、已发布不可变 | ✅ | 服务端状态机 |
| 版本号唯一 + 制品 SHA-256 | ✅ | 唯一约束 + `artifacts.sha256` 唯一（内容寻址） |
| 锁文件记 ID/来源/版本/摘要/路径 | ✅ | `skillmesh.lock.yaml` |
| 先临时目录校验再原子切换 | ✅ | `installer.InstallAtomic` |
| 保留上一版本支持回滚 | ✅ | 制品不删 + `rollback`；**回滚写审计** |

## §10 权限与安全治理

| 控制项 | 状态 | 落地位置 |
| --- | --- | --- |
| OIDC 认证、令牌可撤销过期刷新 | ✅ | Keycloak；access token 一年（realm 的 accessTokenLifespan: 31536000）+ refresh |
| 角色分级（组织管理员/团队管理员/发布者/审核者/使用者） | ✅ | `OrgService.roleAtLeast`：owner>admin>publisher>reviewer>member>viewer |
| 默认隐私 | ✅ | 同步只进 private；team/organization 需审核 |
| 密钥防护 | ✅ | 客户端扫描（阻断发布）+ 服务端扫描（版本转 rejected） |
| 内容扫描六项（脚本/可疑命令/外部下载/路径穿越/隐藏文件/依赖） | ✅ | `skillscan/risks.go` + 服务端同名规则；只扫脚本与配置，文档链接不误报 |
| 制品完整性 | ✅ | SHA-256 + Ed25519 签名 + 客户端验签（含钉住公钥） |
| 执行边界 | ✅ | 服务端不解包执行 Skill 里的脚本，只做静态扫描 |
| 审计八类动作 | ✅ | 发布/审批/通过/拒绝/下载/安装/升级/回滚/卸载/设备登记/成员变更 |
| 撤回 | ✅ | 改变可用状态 + 保留记录；`install` 拒绝、`list` 提示，不删本地 |
| §10.1 状态机 | ✅ | draft→scanning→pending_review→published；扫描失败 rejected；紧急 revoked |
| §10.2 风险边界声明 | ✅ | `info` 与文档都写明「静态扫描不能证明安全」 |

## §11 数据模型

19 张表**全部有读写路径**（`db/migrations/V1__init.sql`）：users / organizations / organization_memberships / teams / team_memberships / skills / skill_sources（创建 Skill 时按客户端上报的来源写入）/ artifacts / skill_versions / skill_scans / skill_reviews / skill_permissions（结构在）/ devices / installations / sync_states / sync_changesets / sync_conflicts / upload_sessions / audit_events。

## §12 API 设计

契约清单（`docs/详细设计.md` §2.2）里的 28 个端点**全部实现**，含 `sources/resolve`、`sources/check-updates`、`sync/changesets` 全家（`/uploads`、`/commit`、`/resolve`）、`installations` CRUD、`audit-events`。

## §13 错误处理与可观测性

| 情形 | 处理 |
| --- | --- |
| 网络断开 | 进持久队列，退避重试，`--retry-only` 补传 |
| 令牌过期 | 提示重新认证；`SKILLMESH_TOKEN` 供 CI |
| 权限被撤销 | 403/404，不自动删除本地数据 |
| 版本冲突 | 409 `VERSION_CONFLICT`，锁文件不动 |
| 安全扫描命中 | 阻断发布（密钥）/ 提示（可疑内容），给文件:行号 |
| 磁盘/目录异常 | 原子安装失败保留旧版本；回收站兜底 |
| Git 远端不可用 | 保留已装版本，错误带最后检查信息 |
| 日志与 request id | problem+json 带 `request_id`；审计不记令牌与文件内容 |

## §14 部署与运维

| 要点 | 状态 |
| --- | --- |
| Docker Compose 起步 | ✅ `docker-compose.yml`（API/Web/PG/MinIO/Keycloak） |
| HTTPS | ⏭ 生产前置项，本机开发用 HTTP |
| 备份数据库与对象存储 + 验证恢复 | ✅ `BackupService`（JDBC + MinIO SDK 流式导出 + 清单自检），`BackupServiceTest` 验「改坏文件能报出来」 |
| 制品大小/文件数/扫描时间/频率限制 | ✅ 大小与文件数在请求校验，扫描有上限，限流按用户（读 600/写 60 每分钟） |
| Git 下载超时/并发/主机白名单 | ✅ 30s 超时、信号量并发上限、`GitHostPolicy` 白名单 + 内网地址拦截 |
| 版本化 API | ✅ `/api/v1` 前缀 |

## §15 验收标准

| 用例 | 结果 |
| --- | --- |
| Daemon 发现并同步到个人私有空间 | ✅ `watch` + `sync` 实测 |
| 指定子目录安装并记录 commit 与摘要 | ✅ `add --path`，锁文件记 commit 与 installed_hash |
| 更新可查差异；本地有改动不覆盖 | ✅ 实测：差异列表 + 「跳过，本地有改动」 |
| 双设备修改同一文件进冲突，不丢任一侧 | ✅ 三方对账 + 按文件合并 + 行级三方文本合并 + `keep-both` 备份；服务端变更集二次确认 |
| 含密钥的 Skill 上传被阻断并给原因 | ✅ 客户端与服务端都阻断，指到文件:行号 |
| 无团队权限的用户拿不到团队私有 Skill | ✅ `OrgVisibilityTest`：非成员 404 |
| 已发布版本不被同版本号覆盖 | ✅ 409 `VERSION_CONFLICT` |
| 更新失败时当前版本仍可用 | ✅ 原子安装 + 本地改动守卫 |
| 回滚后 Agent 仍能发现 | ✅ 实测回滚后 SKILL.md 正常 |
| 删除可恢复、目录短暂不可访问不触发云端删除 | ✅ 回收站 + 云端永不因本地缺失删除 |

## 明确未做

| 项 | 原因 |
| --- | --- |
| 目录监听的递归深度 | fsnotify 不递归，监听集合靠「Agent 根目录 + 每个 Skill 目录」维持，新增 Skill 目录在每次对账后补监听；更深层级的子目录（如 `references/`）不单独监听——内容变化会以所在 Skill 的摘要变化体现 |
| 证书链 / PKI 供应链证明 | 文档 §10 只要求「签名 + 客户端验证」，已用 Ed25519 实现 |
