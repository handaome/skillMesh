package cmd

import (
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
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
				// 错误文本先脱敏：里面可能带带凭据的仓库地址（详细设计 §3.9）。
				fmt.Printf("认证     失败: %s\n", redact.Text(err.Error()))
			} else if me, err := client.Me(cmd.Context()); err != nil {
				fmt.Printf("认证     失败: %s\n", redact.Text(err.Error()))
			} else {
				fmt.Printf("认证     已登录为 %s (%s)\n",
					me.User.DisplayName, short(me.User.ID))
			}

			// Agent 目录一览：用户级、项目级、以及额外扫描根。
			// 这是原来 agents detect 的内容，并进 doctor 少一个命令。
			projectRoot := mustGetwd()
			for _, name := range agent.Names(Cfg) {
				agentCfg := Cfg.Agents[name]
				if !agentCfg.Enabled {
					fmt.Printf("agent %-12s 未启用\n", name)
					continue
				}
				fmt.Printf("agent %-12s 用户 %s（%s）\n", name, agentCfg.UserSkillsDir,
					DirState(agentCfg.UserSkillsDir))
				projectDir := agentCfg.ProjectSkills
				if resolved, err := agentDirFor(name, lockfile.LockedSkill{}, projectRoot); err == nil {
					projectDir = resolved
				}
				fmt.Printf("%-18s 项目 %s（%s）\n", "", projectDir, DirState(projectDir))
				if len(agentCfg.ExtraScanDirs) > 0 {
					fmt.Printf("%-18s 额外扫描 %s\n", "",
						strings.Join(agentCfg.ExtraScanDirs, "、"))
				}
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
	fmt.Printf("同步队列 待重试 %d，已放弃 %d（下次 sync 会重试待重试的任务）\n",
		waiting, abandoned)
	for _, entry := range pending {
		if !entry.Abandoned() {
			continue
		}
		fmt.Printf("  已放弃 %s：%s\n", entry.Name, redact.Text(entry.LastError))
	}
}
