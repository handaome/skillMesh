package cmd

import (
	"bufio"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// newShareCmd 让一个 Agent 共用另一个 Agent 的技能目录。
//
// 多个 Agent 各装一份同样的技能：磁盘上占双倍空间，改一份要记得改另一份，
// 同步时也多出一倍的条目。共用一份（实体只存一份，各 Agent 目录里放链接）
// 这些问题就都没了。
//
// 共享的粒度是「每个技能一个链接」，不是「整个目录换成链接」：
// Agent 自己的目录（Codex 的 .system 之类）不该被搬走。
func newShareCmd() *cobra.Command {
	var from string
	cmd := &cobra.Command{
		Use:   "share <agent>",
		Short: "让一个 Agent 共用另一个 Agent 的技能目录（磁盘上只存一份）",
		Long: "把目标 Agent 里的每个技能换成指向源 Agent 那一份的链接，两边看到的是同一份内容。\n" +
			"目标目录里独有的技能会先搬到源目录；内容一样的删掉目标目录里那份（共享后只需一份）；" +
			"同名但内容不同的会停下来报错，由你决定留哪份。\n" +
			"以后装了新技能，再跑一次这个命令补上链接。",
		Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			return runShare(args[0], from, globalDryRun)
		},
	}
	cmd.Flags().StringVar(&from, "from", "", "实体所在的 Agent（默认取第一个启用的其它 Agent）")
	return supportsDryRun(cmd)
}

// runShare 共享一次。
func runShare(targetName, fromName string, dryRun bool) error {
	toDir, err := agent.Dir(Cfg, targetName, "user", "")
	if err != nil {
		return err
	}
	if fromName == "" {
		fromName, err = pickShareSource(targetName)
		if err != nil {
			return err
		}
	}
	fromDir, err := agent.Dir(Cfg, fromName, "user", "")
	if err != nil {
		return err
	}
	if sameDir(fromDir, toDir) {
		return fmt.Errorf("%s 和 %s 已经指向同一个目录（%s），不用共享", fromName, targetName, toDir)
	}
	if info, err := os.Stat(fromDir); err != nil || !info.IsDir() {
		return fmt.Errorf("%s 的技能目录还不存在，没东西可共用：%s", fromName, fromDir)
	}
	plan, err := planShare(fromDir, toDir, targetName)
	if err != nil {
		return err
	}
	if len(plan.conflicts) > 0 {
		fmt.Fprintf(os.Stderr, "有 %d 个技能两边同名但内容不同，先处理掉再共享：\n",
			len(plan.conflicts))
		for _, item := range plan.conflicts {
			fmt.Fprintf(os.Stderr, "  %s（%s 与 %s）\n", item.name,
				item.path, filepath.Join(fromDir, item.name))
		}
		return fmt.Errorf("同名内容不同的技能没有动，其它也没动")
	}
	printSharePlan(targetName, fromName, toDir, fromDir, plan, dryRun)
	if dryRun {
		return nil
	}
	if !confirmPrompt("确认执行？") {
		fmt.Println("已取消，什么都没动。")
		return nil
	}
	if err := applyShare(fromDir, toDir, plan); err != nil {
		return err
	}
	fmt.Printf("%s 现在共用 %s 的技能内容：%s 里的每个技能都是指向 %s 的链接\n",
		targetName, fromName, toDir, fromDir)
	fmt.Println("跑一次 skillmesh sync 让基线跟上新的路径。")
	return nil
}

// pickShareSource 挑一个当实体来源的 Agent：第一个启用的、且不是目标自己的。
func pickShareSource(targetName string) (string, error) {
	for _, name := range agent.Names(Cfg) {
		if name == targetName {
			continue
		}
		if agentCfg, ok := Cfg.Agents[name]; ok && agentCfg.Enabled {
			return name, nil
		}
	}
	return "", fmt.Errorf("没有别的启用的 Agent 可选，用 --from 指定一个")
}

// shareItem 是共享时涉及的一个技能。
type shareItem struct {
	// name 是技能名（SKILL.md 里声明的）。
	name string
	// path 是它现在的路径：源目录里的实体，或目标目录里的那份。
	path string
	// link 是共享后目标目录里该放链接的位置。
	link string
}

// sharePlan 是共享一次要做的动作。
type sharePlan struct {
	move      []shareItem // 目标目录独有：搬去源目录
	drop      []shareItem // 两边内容一样：删掉目标目录里那份
	link      []shareItem // 源目录有、目标目录没有：建链接
	conflicts []shareItem // 同名但内容不同：用户自己决定，命令不碰
}

// planShare 算共享要做的动作。
//
// 目标目录里放链接的位置按目标 Agent 的规则算：分类分层的 Agent
// （Hermes 的 <category>/<name>/）放分类下面，与安装时是同一套规则。
func planShare(fromDir, toDir, targetAgent string) (sharePlan, error) {
	plan := sharePlan{}
	fromItems, err := scanSkillDirs(fromDir)
	if err != nil {
		return plan, err
	}
	toItems, err := scanSkillDirs(toDir)
	if err != nil {
		return plan, err
	}
	fromByName := map[string]shareItem{}
	for _, item := range fromItems {
		fromByName[item.name] = item
	}
	toByName := map[string]shareItem{}
	for _, item := range toItems {
		toByName[item.name] = item
	}

	// 链接该放在哪儿：按名字算一次，两边同名的是同一个技能，位置一样。
	links := map[string]string{}
	linkOf := func(item shareItem) (string, error) {
		if path, ok := links[item.name]; ok {
			return path, nil
		}
		path, err := agent.InstallPath(Cfg, targetAgent, "user", "", item.name,
			skillscan.CategoryOf(item.path))
		if err != nil {
			return "", err
		}
		links[item.name] = path
		return path, nil
	}

	for _, item := range toItems {
		source, ok := fromByName[item.name]
		if !ok {
			// 目标目录独有：搬去源目录，那边才是实体。
			link, err := linkOf(item)
			if err != nil {
				return plan, err
			}
			item.link = link
			plan.move = append(plan.move, item)
			continue
		}
		same, err := sameSkillContent(item.path, source.path)
		if err != nil {
			return plan, err
		}
		link, err := linkOf(source)
		if err != nil {
			return plan, err
		}
		if same {
			item.link = link
			plan.drop = append(plan.drop, item)
			continue
		}
		plan.conflicts = append(plan.conflicts, item)
	}
	for _, item := range fromItems {
		if _, ok := toByName[item.name]; ok {
			continue
		}
		link, err := linkOf(item)
		if err != nil {
			return plan, err
		}
		item.link = link
		plan.link = append(plan.link, item)
	}
	return plan, nil
}

// scanSkillDirs 列出目录里的技能目录（含分类分层的那几层）。
//
// 递归着扫：分类分层的 Agent 目录里，技能在 <category>/<name>/ 下面，
// 只看直接子目录会以为那儿没有技能。
func scanSkillDirs(dir string) ([]shareItem, error) {
	if _, err := os.Stat(dir); err != nil {
		if os.IsNotExist(err) {
			return nil, nil // 目录还没建：那儿就是没有技能
		}
		return nil, err
	}
	candidates, err := skillscan.Candidates(dir, true)
	if err != nil {
		return nil, err
	}
	items := make([]shareItem, 0, len(candidates))
	for _, candidate := range candidates {
		name, err := skillDirName(candidate.Dir)
		if err != nil {
			continue // 名字不能当目录名用的，跳过
		}
		items = append(items, shareItem{name: name, path: candidate.Dir})
	}
	return items, nil
}

// skillNames 列出目录里的技能目录名。
func skillNames(dir string) ([]string, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, fmt.Errorf("读 %s 失败: %w", dir, err)
	}
	var names []string
	for _, entry := range entries {
		path := filepath.Join(dir, entry.Name())
		if isSkillDir(path) {
			names = append(names, entry.Name())
		}
	}
	return names, nil
}

// isSkillDir 这个目录里有没有 SKILL.md。
func isSkillDir(path string) bool {
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return false
	}
	_, err = os.Stat(filepath.Join(path, skillscan.SkillFile))
	return err == nil
}

// sameSkillContent 两个技能目录的内容是不是一样。
func sameSkillContent(left, right string) (bool, error) {
	leftDigest, err := skillscan.DirDigest(left)
	if err != nil {
		return false, err
	}
	rightDigest, err := skillscan.DirDigest(right)
	if err != nil {
		return false, err
	}
	return leftDigest == rightDigest, nil
}

// printSharePlan 把要做的动作说清楚。
func printSharePlan(targetName, fromName, toDir, fromDir string, plan sharePlan, dryRun bool) {
	prefix := "将要"
	if dryRun {
		prefix = "演练：会"
	}
	fmt.Printf("%s让 %s 共用 %s 的技能内容：%s -> %s\n",
		prefix, targetName, fromName, toDir, fromDir)
	if len(plan.move) > 0 {
		fmt.Printf("  搬过去 %d 个技能（源目录没有的）：%s\n", len(plan.move), summarize(plan.move))
	}
	if len(plan.drop) > 0 {
		fmt.Printf("  删掉 %d 个重复的（两边内容一样，只留一份）：%s\n", len(plan.drop), summarize(plan.drop))
	}
	if len(plan.link) > 0 {
		fmt.Printf("  建 %d 个链接（源目录有、这边没有的）：%s\n", len(plan.link), summarize(plan.link))
	}
}

// summarize 把一串名字压成一行，太多就只给个数。
func summarize(items []shareItem) string {
	names := make([]string, 0, len(items))
	for _, item := range items {
		names = append(names, item.name)
	}
	if len(names) <= 5 {
		return strings.Join(names, "、")
	}
	return fmt.Sprintf("%s 等 %d 个", strings.Join(names[:5], "、"), len(names))
}

// confirmPrompt 问一句要不要真改。
//
// 读不到输入（CI、后台任务）时返回 false：宁可不动，也不要卡住别人的流水线。
func confirmPrompt(question string) bool {
	if assumeYes {
		return true
	}
	fmt.Printf("%s (y/N) ", question)
	line, err := bufio.NewReader(os.Stdin).ReadString('\n')
	if err != nil && strings.TrimSpace(line) == "" {
		return false
	}
	answer := strings.ToLower(strings.TrimSpace(line))
	return answer == "y" || answer == "yes"
}

// applyShare 按计划把目标目录里的技能换成链接。
func applyShare(fromDir, toDir string, plan sharePlan) error {
	// 目标目录独有的技能：搬去源目录，那边才是实体。
	for _, item := range plan.move {
		if err := movePath(item.path, filepath.Join(fromDir, item.name)); err != nil {
			return err
		}
	}
	// 内容一样的：目标目录里那份删掉，换成链接。
	for _, item := range plan.drop {
		if err := os.RemoveAll(item.path); err != nil {
			return fmt.Errorf("删 %s 失败: %w", item.path, err)
		}
	}
	// 源目录里的每个技能，在目标目录里都要有对应的链接。
	for _, item := range plan.link {
		if err := linkSkill(item.path, item.link); err != nil {
			return err
		}
	}
	// 搬过来的那几个也要放链接：搬走之后目标目录里就没它了。
	for _, item := range plan.move {
		if err := linkSkill(filepath.Join(fromDir, item.name), item.link); err != nil {
			return err
		}
	}
	return nil
}

// linkSkill 在 link 位置建一个指向 target 的目录链接。
//
// 已经有的不动：那儿可能有用户自己放的东西。
// 分类分层的链接要建在 <category>/ 下面，父目录得先有。
func linkSkill(target, link string) error {
	if _, err := os.Lstat(link); err == nil {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(link), 0o755); err != nil {
		return fmt.Errorf("建 %s 失败: %w", filepath.Dir(link), err)
	}
	if err := skillscan.LinkDir(target, link); err != nil {
		return fmt.Errorf("建链接 %s 失败: %w", link, err)
	}
	return nil
}

// movePath 搬一个目录：同盘改名，跨盘复制后再删。
func movePath(src, dst string) error {
	if err := os.Rename(src, dst); err == nil {
		return nil
	}
	if err := copyDir(src, dst); err != nil {
		return fmt.Errorf("复制 %s 到 %s 失败: %w", src, dst, err)
	}
	return os.RemoveAll(src)
}

// sameDir 两个路径是不是同一个目录。
func sameDir(left, right string) bool {
	return skillscan.ResolveLink(left) == skillscan.ResolveLink(right)
}
