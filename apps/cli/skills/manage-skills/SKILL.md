---
name: manage-skills
description: 通过 SkillMesh CLI 查询、安装、发布 Agent Skill。当用户要装 skill、查有哪些 skill、把 skill 发到团队、或排查安装问题时使用。必须走 CLI，不要直接写 Agent 的 skills 目录——那样会绕过可见性、审核与版本治理。
---

# 用 SkillMesh CLI 管理 Skill

SkillMesh 是团队共享 Skill 的注册中心：云端管元数据、权限、版本与审核，
CLI 负责发现、校验、安装。

**唯一的入口是 `skillmesh` CLI。** 不要直接往 `~/.claude/skills` 之类的
Agent 目录写文件——那样装的东西不进注册中心、不受版本与审核管辖，
下次同步会被当成「未受管的目录」，用户以为装好了其实没登记。

## 先找到 CLI

固定路径优先，其次 PATH：

| 状态 | 含义 | 怎么办 |
| --- | --- | --- |
| `<配置目录>/bin/skillmesh` 存在且同级有 `.version` | 完整可用，版本可信 | 直接用 |
| 只有 `<配置目录>/bin/skillmesh`，没有 `.version` | 上次复制没成功，不可用 | 让用户跑一次 `skillmesh self-install` |
| PATH 上有 `skillmesh` | 可能是别的版本 | 用 `skillmesh --version` 确认，或先 `skillmesh self-install` |

`<配置目录>` 是 `skillmesh doctor` 打印的「配置目录」那一行。

## 装一个 Skill

```bash
skillmesh search <关键字>      # 先看有什么
skillmesh info <skill-id>      # 看来源、许可证、扫描结论
skillmesh install <skill-id>   # 装到本机
```

装之前先看 `info`：来源不明的、扫描有命中的、许可证未提供的，都要说给用户听，
让用户决定。**不要替用户跳过这些确认。**

## 发布一个 Skill

```bash
skillmesh publish <本地目录>              # 发到个人私有空间
skillmesh publish <本地目录> --org <组织>  # 发到组织，停在待审核
skillmesh publish <本地目录> --team <团队>
```

发到组织或团队会进入审核队列，**这是流程的不可逆起点**：提交后作者本人
不能再改那一份的内容，只能等审核结果或撤回重发。要发之前先跟用户确认。

## 不可逆操作：必须让用户确认

以下操作会删东西或改状态，执行前必须得到用户明确同意：

- `skillmesh uninstall <name>` —— 默认进回收站（`skillmesh trash restore` 找得回），
  但 `--purge` 是真删，找不回来。
- `skillmesh cloud delete <slug>` —— 云端软删除。
- `skillmesh publish ... --org/--team` —— 进入审核队列。
- `skillmesh rollback <name> --to <version>` —— 换版本。

## 红线

**不要执行 `skillmesh review approve` 或 `review reject`。** 审核必须由人来做：
SkillMesh 的规则是审核人不能审自己发布的版本，这条线是双人复核的底线。
Agent 只负责把审核材料准备好，交给用户。

## 脚本与 CI

所有命令支持全局 `--json`：stdout 出 JSON，提示和错误走 stderr。

```bash
skillmesh --json search <关键字>
skillmesh --json install <skill-id>
```

失败时 stderr 给一个信封：

```json
{"ok": false, "code": "VALIDATION_FAILED", "message": "...", "exit_code": 2}
```

按退出码决定下一步：

| 退出码 | 含义 | 你该做什么 |
| --- | --- | --- |
| 0 | 成功 | 继续 |
| 2 | 参数或配置错误 | 改命令重来 |
| 3 | 未登录 | 让用户 `skillmesh login`，或设 `SKILLMESH_TOKEN` |
| 4 | 权限不足 | 换账号，别重试 |
| 5 | 网络或服务端不可用 | 退避后重试 |
| 6 | 安全策略阻断 | 别重试，报给用户 |
| 7 | 检测到冲突 | 交给用户定夺 |
| 8 | 目标 Agent 或目录不可用 | 检查环境 |
| 9 | 资源不存在 | 换目标 |

`code` 沿用服务端 problem+json 的词表，常用的有：`NOT_FOUND`、`FORBIDDEN`、
`VALIDATION_FAILED`、`SLUG_TAKEN`、`VERSION_CONFLICT`、`SYNC_CONFLICT`、
`DIGEST_MISMATCH`、`RATE_LIMITED`。

## 目标被占

装到某个 Agent 目录时，如果那儿已经有不是 SkillMesh 装的东西，命令会拒绝覆盖，
退出码 7，错误里带具体路径。**不要自作主张加 `--replace`**——那会覆盖用户
自己的文件。先把路径告诉用户，让用户决定是换目录还是确认要覆盖。
