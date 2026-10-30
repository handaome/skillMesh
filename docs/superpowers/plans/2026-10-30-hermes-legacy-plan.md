# 解决 Hermes 兼容的遗留

## 遗留与做法

### 1. 存量老 skill 被规范闸拦住

没有 frontmatter 的老 skill 现在装不上、也发不了云。

- **`skillmesh fix-skill <dir>`**：把不合规的 SKILL.md 补成合规的。
  有 frontmatter 就补缺的字段，没有就在文件最前面插入一段。
  `name` 取目录名清洗后的结果，`description` 取正文第一段（截到 200 字）。
- **显式豁免**：frontmatter 里写 `metadata.skillmesh.spec_exempt: true` 就跳过
  规范检查。给改不了源文件的第三方 skill 留个口子。
  豁免信息在 SKILL.md 里，打包会带上，服务端也认——两边口径一致。
  没有 frontmatter 的老 skill 用不了豁免（豁免声明本身要写在 frontmatter 里），
  只能先用 `fix-skill` 补。

### 2. Hermes 的分类分层布局

Hermes 官方布局是 `~/.hermes/skills/<category>/<name>/SKILL.md`。

- Agent 配置加 `category_dirs: true`（只有 hermes 开）。
- category 取 SKILL.md frontmatter 里的 `metadata.hermes.category`；没声明就还是一层。
- 路径只由「装」的那几处决定，装完记进锁文件的 `target_paths`（每个 Agent 一条）。
  `list` / `uninstall` 改用锁文件里记的路径，不再按配置重算——
  重算本来就是旧坑（改了 Agent 目录就卸错地方），一起修掉。

### 3. 真实 Hermes 验证

本机只有 `~/.hermes/config.yaml`，没有 Hermes 本体（GitHub 不通，装不了）。
改成按官方发现规则验证：递归扫到 SKILL.md、能按 frontmatter 的 name 认出来。

## 验收

- `fix-skill` 有单测：没 frontmatter 的补出合规的；缺 description 的补上；已有的不覆盖。
- 豁免两侧一致：Go 与 Java 都认 `metadata.skillmesh.spec_exempt`。
- category 分层：装了带 category 的 skill 后落在 `<skills_dir>/<category>/<name>/`；
  `list` / `uninstall` 用的是锁文件里记的路径。
- Go 与 Java 全量测试通过。

## 做完后与计划的两处出入

- 锁文件字段从 `target_path`（一个字符串）改成 `target_paths`（Agent 名 -> 路径）：
  装到几个 Agent 就有几个路径，只记一条的话卸载会漏掉别的 Agent 那份。
  老锁文件没有这个字段，按配置算的旧路径兜底。
- 服务端的豁免判断原来找错了层级（找顶层 `skillmesh`，
  实际在 `metadata.skillmesh` 下），补测试时才发现，一并修掉。
