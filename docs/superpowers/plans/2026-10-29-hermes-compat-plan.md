# 兼容 Hermes（agentskills.io 规范）

## 目标

SkillMesh 产出的 skill 在 Hermes 这类按 agentskills.io 规范扫的 Agent 里能直接用：
装得上、认得出、更新得到。

## 规范要点（来源：Hermes 官方 skills 文档与 ADR-076）

- `SKILL.md` 必须以 `---` 开头，frontmatter 是 YAML mapping，`\n---\n` 闭合
- `name` 必填且 ≤ 64 字符；`description` 必填且 ≤ 1024 字符
- frontmatter 之后的正文非空
- 整个文件 ≤ 100,000 字符
- 安装位置 `~/.hermes/skills/<name>/SKILL.md`（Hermes 递归扫，扁平即可识别）
- 扩展字段：`platforms` / `tags` / `license` / `author` / `metadata.hermes.*`

## 步骤

1. **规范校验**（CLI + 服务端双侧，不合规拦下来）
   - Go：`skillscan.ValidateSpec(dir)`，装前与发布前都过这道闸
   - Java：扫描器加一条阻断规则，缺 SKILL.md 或不合规的制品进隔离
2. **Hermes 作为目标 Agent**
   - 配置默认项 `hermes`：`~/.hermes/skills`，项目级 `.hermes/skills`
   - 扁平安装（Hermes 递归扫，一层子目录也能发现），不引入 category 层
3. **元数据字段互认**
   - Go 的 `Metadata` 扩展 platforms/tags/license/author/metadata
   - 发布时随 skill 的 metadata 上云，网页端可见
4. **导入 Hermes 生态的 skill**
   - 仓库里的 `skills/<category>/<name>` 能被解析端点列出（已有）
   - 新增批量导入：一次导多个，slug 取目录名、name/description 取 frontmatter

## 验收

- `skillscan` 的规范校验有单测：缺 frontmatter、name 超长、description 超长、正文空都拦
- 服务端扫描器对不合规制品给出阻断结论
- 全量 Go 测试与 Java 测试通过
