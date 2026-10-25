package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"sort"
	"strings"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/syncqueue"
	"github.com/spf13/cobra"
)

func newDoctorCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "doctor",
		Short: "诊断配置、目录权限、认证状态与 Git 可用性",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			fmt.Printf("服务端   %s\n", Cfg.Server)
			fmt.Printf("配置目录 %s\n", Cfg.ConfigDir)
			fmt.Printf("缓存目录 %s\n", Cfg.CacheDir)

			if gitPath, err := exec.LookPath("git"); err != nil {
				fmt.Println("git      未找到，来源解析不可用")
			} else if out, err := exec.Command(gitPath, "--version").Output(); err == nil {
				fmt.Printf("git      %s\n", strings.TrimSpace(string(out)))
			}

			if client, err := newClient(cmd.Context()); err != nil {
				// 如实说坏在哪：newClient 现在有四种失败（没登录、令牌过期续不上、
				// 取不到 issuer、存不回新令牌），一律说成「未登录」会把人引去重新登录，
				// 而真正的原因在别处。doctor 的职责就是说清楚哪里坏了。
				fmt.Printf("认证     失败: %v\n", err)
			} else if me, err := client.Me(cmd.Context()); err != nil {
				fmt.Printf("认证     失败: %v\n", err)
			} else {
				fmt.Printf("认证     已登录为 %s (%s)\n",
					me.User.DisplayName, short(me.User.ID))
			}

			for _, name := range sortedAgentNames() {
				agentCfg := Cfg.Agents[name]
				if !agentCfg.Enabled {
					fmt.Printf("agent %-12s 未启用\n", name)
					continue
				}
				fmt.Printf("agent %-12s %s（%s）\n", name, agentCfg.UserSkillsDir,
					DirState(agentCfg.UserSkillsDir))
			}
			reportSyncHealth()
			return nil
		},
	}
}

// DirState 说一个目录能不能用：不存在、存在但写不进去、还是正常。
//
// 「存在」不等于「能用」——目录在、权限只读时装 Skill 会在最后一步失败，
// 而那时候用户已经等完一轮下载了。设计文档 §13 要求 doctor 诊断「目录权限」。
func DirState(dir string) string {
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return "不存在，安装时创建"
	}
	// 真写一个临时文件：权限位骗人（ACL、只读挂载、容器里的 root），
	// 试写才准。写完立刻删，不留东西。
	probe, err := os.CreateTemp(dir, ".skillmesh-probe-*")
	if err != nil {
		return "存在，但写不进去"
	}
	name := probe.Name()
	probe.Close()
	os.Remove(name)
	return "存在，可写"
}

// reportSyncHealth 报同步队列概况（设计文档 §13：doctor 要能诊断同步队列）。
func reportSyncHealth() {
	queue, err := syncqueue.Load(Cfg.ConfigDir)
	if err != nil {
		fmt.Printf("同步队列 读取失败: %v\n", err)
		return
	}
	pending := queue.Pending()
	if len(pending) == 0 {
		fmt.Println("同步队列 空")
		return
	}
	waiting, abandoned := 0, 0
	for _, entry := range pending {
		if entry.Abandoned() {
			abandoned++
			continue
		}
		waiting++
	}
	fmt.Printf("同步队列 待重试 %d，已放弃 %d（用 sync --retry-only 只跑待重试的）\n",
		waiting, abandoned)
	for _, entry := range pending {
		if !entry.Abandoned() {
			continue
		}
		fmt.Printf("  已放弃 %s：%s\n", entry.Name, entry.LastError)
	}
}

func newAgentsCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "agents",
		Short: "Agent 适配器管理",
	}
	cmd.AddCommand(&cobra.Command{
		Use:   "detect",
		Short: "检测本机各 Agent 的 Skill 目录",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			projectRoot := mustGetwd()
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "AGENT\t启用\t用户目录\t项目目录\t目录状态")
			for _, name := range agent.Supported {
				agentCfg, ok := Cfg.Agents[name]
				if !ok {
					fmt.Fprintf(w, "%s\t无配置\t-\t-\t-\n", name)
					continue
				}
				projectDir := agentCfg.ProjectSkills
				if resolved, err := agentDirFor(name, lockfile.LockedSkill{}, projectRoot); err == nil {
					projectDir = resolved
				}
				// 目录状态要如实说：探测到的和「假设应该在这」的，含义不一样。
				state := "不存在，安装时创建"
				if agentCfg.Detected() {
					state = "存在（本机探测到）"
				}
				fmt.Fprintf(w, "%s\t%v\t%s\t%s\t%s\n",
					name, agentCfg.Enabled, agentCfg.UserSkillsDir, projectDir, state)
			}
			return w.Flush()
		},
	})
	return cmd
}

func sortedAgentNames() []string {
	names := make([]string, 0, len(Cfg.Agents))
	for name := range Cfg.Agents {
		names = append(names, name)
	}
	sort.Strings(names)
	return names
}
