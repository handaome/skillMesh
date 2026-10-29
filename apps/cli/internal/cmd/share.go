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
	var dryRun bool
	cmd := &cobra.Command{
		Use:   "share <agent>",
		Short: "让一个 Agent 共用另一个 Agent 的技能目录（磁盘上只存一份）",
		Long: "把目标 Agent 里的每个技能换成指向源 Agent 那一份的链接，两边看到的是同一份内容。\n" +
			"目标目录里独有的技能会先搬到源目录；内容一样的删掉目标目录里那份（共享后只需一份）；" +
			"同名但内容不同的会停下来报错，由你决定留哪份。\n" +
			"以后装了新技能，再跑一次这个命令补上链接。",
		Args: cobra.ExactArgs(1),
		RunE: func(_ *cobra.Command, args []string) error {
			return runShare(args[0], from, dryRun)
		},
	}
	cmd.Flags().StringVar(&from, "from", "", "实体所在的 Agent（默认取第一个启用的其它 Agent）")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只看会怎么动，不真改")
	return cmd
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
	plan, err := planShare(fromDir, toDir)
	if err != nil {
		return err
	}
	if len(plan.conflicts) > 0 {
		fmt.Fprintf(os.Stderr, "有 %d 个技能两边同名但内容不同，先处理掉再共享：\n",
			len(plan.conflicts))
		for _, name := range plan.conflicts {
			fmt.Fprintf(os.Stderr, "  %s（%s 与 %s）\n", name,
				filepath.Join(toDir, name), filepath.Join(fromDir, name))
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

// sharePlan 是共享一次要做的动作。
type sharePlan struct {
	move      []string // 目标目录独有：搬去源目录
	drop      []string // 两边内容一样：删掉目标目录里那份
	link      []string // 源目录有、目标目录没有：建链接
	conflicts []string // 同名但内容不同：用户自己决定，命令不碰
	leftovers []string // 目标目录里不是技能的东西：原样留着
}

// planShare 算共享要做的动作。
func planShare(fromDir, toDir string) (sharePlan, error) {
	plan := sharePlan{}
	toNames, err := skillNames(toDir)
	if err != nil {
		return plan, err
	}
	fromNames, err := skillNames(fromDir)
	if err != nil {
		return plan, err
	}
	fromSet := map[string]bool{}
	for _, name := range fromNames {
		fromSet[name] = true
	}
	for _, name := range toNames {
		if !fromSet[name] {
			plan.move = append(plan.move, name)
			continue
		}
		same, err := sameSkillContent(filepath.Join(toDir, name), filepath.Join(fromDir, name))
		if err != nil {
			return plan, err
		}
		if same {
			plan.drop = append(plan.drop, name)
		} else {
			plan.conflicts = append(plan.conflicts, name)
		}
	}
	toSet := map[string]bool{}
	for _, name := range toNames {
		toSet[name] = true
	}
	for _, name := range fromNames {
		if !toSet[name] {
			plan.link = append(plan.link, name)
		}
	}
	return plan, nil
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
		fmt.Printf("  搬过去 %d 个技能（源目录没有的）：%s\n",
			len(plan.move), summarize(plan.move))
	}
	if len(plan.drop) > 0 {
		fmt.Printf("  删掉 %d 个重复的（两边内容一样，只留一份）：%s\n",
			len(plan.drop), summarize(plan.drop))
	}
	if len(plan.link) > 0 {
		fmt.Printf("  建 %d 个链接（源目录有、这边没有的）：%s\n",
			len(plan.link), summarize(plan.link))
	}
	if len(plan.leftovers) > 0 {
		fmt.Printf("  %s 里 %d 个不是技能的东西原样留着：%s\n",
			toDir, len(plan.leftovers), summarize(plan.leftovers))
	}
	if len(plan.move) == 0 && len(plan.drop) == 0 && len(plan.link) == 0 {
		fmt.Println("  两边已经一致，不用动。")
	}
}

// summarize 把一串名字压成一行，太多时只给个数。
func summarize(names []string) string {
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
	for _, name := range plan.move {
		if err := movePath(filepath.Join(toDir, name), filepath.Join(fromDir, name)); err != nil {
			return err
		}
	}
	// 内容一样的：目标目录里那份删掉，换成链接。
	for _, name := range plan.drop {
		if err := os.RemoveAll(filepath.Join(toDir, name)); err != nil {
			return fmt.Errorf("删 %s 失败: %w", filepath.Join(toDir, name), err)
		}
	}
	// 源目录里的每个技能，在目标目录里都要有对应的链接。
	names, err := skillNames(fromDir)
	if err != nil {
		return err
	}
	for _, name := range names {
		link := filepath.Join(toDir, name)
		if _, err := os.Lstat(link); err == nil {
			continue // 已经是链接或实体了
		}
		if err := skillscan.LinkDir(filepath.Join(fromDir, name), link); err != nil {
			return fmt.Errorf("建链接 %s 失败: %w", link, err)
		}
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
