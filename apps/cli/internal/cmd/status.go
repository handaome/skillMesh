package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
	"github.com/handaome/skillmesh/apps/cli/internal/syncqueue"
	"github.com/spf13/cobra"
)

// newStatusCmd 汇总本机现状（详细设计 §3.9）。
//
// 只读：不触发下载，不动任何文件——「看一眼现在怎么样」不该改变什么。
func newStatusCmd() *cobra.Command {
	var skillName string
	var queueOnly bool
	cmd := &cobra.Command{
		Use:   "status",
		Short: "汇总登录、服务端、监听与同步状况",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if queueOnly {
				reportSyncHealth()
				return nil
			}
			reportIdentity(cmd)
			reportWatch()
			reportCounts()
			if skillName != "" {
				reportOneSkill(skillName)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&skillName, "skill", "", "只看这一个 Skill")
	cmd.Flags().BoolVar(&queueOnly, "sync-queue", false, "只看同步队列")
	return cmd
}

// reportIdentity 说清服务端通不通、身份是谁。
//
// 登录失败和连通失败是两回事：前者该重新登录，后者该看网络。
// 混成一句「未登录」会让人白登一次。
func reportIdentity(cmd *cobra.Command) {
	client, err := newClient(cmd.Context())
	if err != nil {
		fmt.Printf("服务端   %s（未登录：%s）\n", Cfg.Server, redact.Text(err.Error()))
		return
	}
	me, err := client.Me(cmd.Context())
	if err != nil {
		fmt.Printf("服务端   %s（连上了但取不到身份：%s）\n",
			Cfg.Server, redact.Text(err.Error()))
		return
	}
	fmt.Printf("服务端   %s（已登录为 %s）\n", Cfg.Server, me.User.DisplayName)
}

// reportWatch 说后台监听有没有在跑。
func reportWatch() {
	pid, running := watchRunning()
	if running {
		fmt.Printf("监听     运行中（PID %d）\n", pid)
		return
	}
	fmt.Println("监听     没在运行")
}

// reportCounts 报已管理数量、待同步任务与冲突副本。
func reportCounts() {
	lock, err := lockfile.Load(projectRootDir())
	if err != nil {
		fmt.Printf("已管理   读取失败: %v\n", err)
	} else {
		fmt.Printf("已管理   %d 个 Skill\n", len(lock.Skills))
	}

	queue, err := syncqueue.Load(Cfg.ConfigDir)
	if err != nil {
		fmt.Printf("待同步   读取失败: %v\n", err)
		return
	}
	waiting, abandoned := 0, 0
	for _, entry := range queue.Pending() {
		if entry.Abandoned() {
			abandoned++
			continue
		}
		waiting++
	}
	fmt.Printf("待同步   %d 个待重试，%d 个已放弃\n", waiting, abandoned)

	if conflicts := countConflictCopies(); conflicts > 0 {
		fmt.Printf("冲突     %d 份冲突副本等着处理\n", conflicts)
	}
}

// countConflictCopies 数 Agent 目录里留下的冲突副本。
//
// 副本是隐藏目录 `.名字.conflict-xxxxxxxx`，放在 Skill 目录的旁边
// （见 sync 的 keep-both 分支）。扫描会跳过隐藏目录，所以它不会变成
// 下一个待同步的 Skill——但它也不会自己消失，得人来处理。
func countConflictCopies() int {
	count := 0
	for _, name := range agent.Names(Cfg) {
		agentCfg := Cfg.Agents[name]
		if !agentCfg.Enabled {
			continue
		}
		dirs := []string{agentCfg.UserSkillsDir, agentCfg.ProjectSkills}
		dirs = append(dirs, agentCfg.ExtraScanDirs...)
		for _, dir := range dirs {
			if dir == "" {
				continue
			}
			entries, err := os.ReadDir(dir)
			if err != nil {
				continue
			}
			for _, entry := range entries {
				if entry.IsDir() && strings.Contains(entry.Name(), ".conflict-") {
					count++
				}
			}
		}
	}
	return count
}

// reportOneSkill 说清一个 Skill 的来龙去脉：装在哪、锁在哪、下次更新从哪来。
func reportOneSkill(name string) {
	lock, err := lockfile.Load(projectRootDir())
	if err != nil {
		fmt.Printf("%s 读取锁文件失败: %v\n", name, err)
		return
	}
	entry, ok := lock.Find(name)
	if !ok {
		fmt.Printf("%s 不在锁文件里\n", name)
		return
	}
	source := entry.Repository
	if source == "" {
		source = "本地目录"
	}
	fmt.Printf("%s 来源 %s\n", name, source)
	if entry.Ref != "" {
		fmt.Printf("  ref    %s（%s）\n", entry.Ref, entry.RefType)
	}
	if entry.ResolvedCommit != "" {
		fmt.Printf("  commit %s\n", entry.ResolvedCommit)
	}
	if entry.Version != "" {
		fmt.Printf("  版本   %s\n", entry.Version)
	}
	if entry.UpdatePolicy != "" {
		fmt.Printf("  策略   %s\n", entry.UpdatePolicy)
	}
	for _, agentName := range entry.Agents {
		target := entry.TargetPathOf(agentName)
		if target == "" {
			continue
		}
		fmt.Printf("  安装   %s → %s（%s）\n", agentName, target, DirState(filepath.Dir(target)))
	}
}
