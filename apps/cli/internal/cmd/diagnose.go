package cmd

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"strings"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/redact"
	"github.com/handaome/skillmesh/apps/cli/internal/syncqueue"
	"github.com/spf13/cobra"
)

// doctorReport 是 doctor 的结构化结果。--json 输出它。
type doctorReport struct {
	Server    string        `json:"server"`
	ConfigDir string        `json:"config_dir"`
	CacheDir  string        `json:"cache_dir"`
	Git       string        `json:"git"`
	Auth      doctorAuth    `json:"auth"`
	Network   doctorNetwork `json:"network"`
	Agents    []doctorAgent `json:"agents"`
	Sync      doctorSync    `json:"sync"`
}

// doctorAuth 是认证状况。
type doctorAuth struct {
	OK      bool   `json:"ok"`
	User    string `json:"user,omitempty"`
	Failure string `json:"failure,omitempty"`
}

// doctorNetwork 是网络与系统状况。
type doctorNetwork struct {
	Reachable bool   `json:"reachable"`
	Failure   string `json:"failure,omitempty"`
	// ClockSkewSec 是本机与服务端的时钟偏差（秒）。拿不到就是 0。
	ClockSkewSec int  `json:"clock_skew_seconds"`
	SkewKnown    bool `json:"clock_skew_known"`
}

// doctorAgent 是单个 Agent 适配器的目录状况。
type doctorAgent struct {
	Name    string `json:"name"`
	Enabled bool   `json:"enabled"`
	UserDir string `json:"user_dir,omitempty"`
	State   string `json:"state,omitempty"`
}

// doctorSync 是同步相关的状况。
type doctorSync struct {
	DaemonRunning  bool `json:"daemon_running"`
	QueuePending   int  `json:"queue_pending"`
	QueueAbandoned int  `json:"queue_abandoned"`
	LockSkills     int  `json:"lock_skills"`
	// LockMissing 是锁文件里记了、但本机已经找不到的安装数。
	LockMissing int `json:"lock_missing_targets"`
}

func newDoctorCmd() *cobra.Command {
	var networkOnly, agentsOnly, syncOnly bool
	cmd := supportsJSON(&cobra.Command{
		Use:   "doctor",
		Short: "诊断配置、目录权限、认证状态与 Git 可用性",
		Long: "检查配置、凭据、服务端可达性、时钟偏差、Agent 目录可写性、\n" +
			"守护进程、同步队列与锁文件一致性。\n" +
			"不加参数时全都查；--network / --agents / --sync 只查其中一块。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			report := doctorReport{
				Server:    Cfg.Server,
				ConfigDir: Cfg.ConfigDir,
				CacheDir:  Cfg.CacheDir,
			}
			showAll := !networkOnly && !agentsOnly && !syncOnly

			report.Git = gitVersion()
			report.Auth = checkAuth(cmd.Context())
			if showAll || networkOnly {
				report.Network = checkNetwork(cmd.Context())
			}
			if showAll || agentsOnly {
				report.Agents = checkAgents()
			}
			if showAll || syncOnly {
				report.Sync = checkSync()
			}

			if jsonEnabled() {
				return writeJSON(report)
			}
			printDoctor(report, showAll, networkOnly, agentsOnly, syncOnly)
			return nil
		},
	})
	cmd.Flags().BoolVar(&networkOnly, "network", false, "只查网络与系统：服务端可达性、时钟偏差")
	cmd.Flags().BoolVar(&agentsOnly, "agents", false, "只查 Agent 目录：能不能写")
	cmd.Flags().BoolVar(&syncOnly, "sync", false, "只查同步：守护进程、同步队列、锁文件")
	return cmd
}

// gitVersion 报 git 的版本。找不到就说找不到——来源解析靠它。
func gitVersion() string {
	gitPath, err := exec.LookPath("git")
	if err != nil {
		return ""
	}
	out, err := exec.Command(gitPath, "--version").Output()
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(out))
}

// checkAuth 看登录态与令牌能不能用。
//
// 如实说坏在哪：newClient 现在有四种失败（没登录、令牌过期续不上、
// 取不到 issuer、存不回新令牌），一律说成「未登录」会把人引去重新登录，
// 而真正的原因在别处。doctor 的职责就是说清楚哪里坏了。
func checkAuth(ctx context.Context) doctorAuth {
	client, err := newClient(ctx)
	if err != nil {
		// 错误文本先脱敏：里面可能带带凭据的仓库地址（详细设计 §3.9）。
		return doctorAuth{Failure: redact.Text(err.Error())}
	}
	me, err := client.Me(ctx)
	if err != nil {
		return doctorAuth{Failure: redact.Text(err.Error())}
	}
	return doctorAuth{OK: true, User: me.User.DisplayName}
}

// checkNetwork 看服务端通不通、本机时钟偏不偏。
//
// 时钟偏差是个隐形坑：本地快一小时，令牌刚拿到就「过期」了（设计文档 §3.9）。
func checkNetwork(ctx context.Context) doctorNetwork {
	result := doctorNetwork{}
	client, err := newClient(ctx)
	if err != nil {
		result.Failure = redact.Text(err.Error())
		return result
	}
	if _, err := client.Me(ctx); err != nil {
		result.Failure = redact.Text(err.Error())
		return result
	}
	result.Reachable = true
	if serverTime, err := client.ServerTime(ctx); err == nil {
		skew := int(time.Since(serverTime).Seconds())
		result.ClockSkewSec = skew
		result.SkewKnown = true
	}
	return result
}

// checkAgents 看各 Agent 的目录能不能写。
func checkAgents() []doctorAgent {
	projectRoot := projectRootDir()
	rows := []doctorAgent{}
	for _, name := range agent.Names(Cfg) {
		agentCfg := Cfg.Agents[name]
		row := doctorAgent{Name: name, Enabled: agentCfg.Enabled}
		if agentCfg.Enabled {
			row.UserDir = agentCfg.UserSkillsDir
			row.State = DirState(agentCfg.UserSkillsDir)
			if resolved, err := agentDirFor(name, lockfile.LockedSkill{}, projectRoot); err == nil {
				row.State += "；项目 " + DirState(resolved)
			}
		}
		rows = append(rows, row)
	}
	return rows
}

// checkSync 看守护进程、同步队列与锁文件。
func checkSync() doctorSync {
	result := doctorSync{}
	if _, running := watchRunning(); running {
		result.DaemonRunning = true
	}
	if queue, err := syncqueue.Load(Cfg.ConfigDir); err == nil {
		for _, entry := range queue.Pending() {
			if entry.Abandoned() {
				result.QueueAbandoned++
				continue
			}
			result.QueuePending++
		}
	}
	lock, err := lockfile.Load(projectRootDir())
	if err != nil {
		return result
	}
	result.LockSkills = len(lock.Skills)
	projectRoot := projectRootDir()
	for _, skill := range lock.Skills {
		for _, agentName := range skill.Agents {
			target, err := targetPathOf(agentName, skill, projectRoot)
			if err != nil {
				continue
			}
			if _, err := os.Stat(target); err != nil {
				result.LockMissing++
			}
		}
	}
	return result
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

// printDoctor 打印人读诊断结果。
func printDoctor(report doctorReport, showAll, networkOnly, agentsOnly, syncOnly bool) {
	fmt.Printf("服务端   %s\n", report.Server)
	fmt.Printf("配置目录 %s\n", report.ConfigDir)
	fmt.Printf("缓存目录 %s\n", report.CacheDir)
	if report.Git == "" {
		fmt.Println("git      未找到，来源解析不可用")
	} else {
		fmt.Printf("git      %s\n", report.Git)
	}
	if report.Auth.OK {
		fmt.Printf("认证     已登录为 %s\n", report.Auth.User)
	} else {
		fmt.Printf("认证     失败: %s\n", report.Auth.Failure)
	}

	if showAll || networkOnly {
		if report.Network.Reachable {
			fmt.Println("网络     服务端可达")
		} else {
			fmt.Printf("网络     不可达: %s\n", report.Network.Failure)
		}
		if report.Network.SkewKnown {
			fmt.Printf("时钟     与服务端相差 %d 秒\n", report.Network.ClockSkewSec)
		} else {
			fmt.Println("时钟     偏差未知（服务端没给时间）")
		}
	}

	if showAll || agentsOnly {
		for _, row := range report.Agents {
			if !row.Enabled {
				fmt.Printf("agent %-12s 未启用\n", row.Name)
				continue
			}
			fmt.Printf("agent %-12s 用户 %s（%s）\n", row.Name, row.UserDir, row.State)
		}
	}

	if showAll || syncOnly {
		pid, running := watchRunning()
		if running {
			fmt.Printf("监听     运行中（PID %d）\n", pid)
		} else {
			fmt.Println("监听     没在运行")
		}
		fmt.Printf("同步队列 待重试 %d，已放弃 %d\n",
			report.Sync.QueuePending, report.Sync.QueueAbandoned)
		fmt.Printf("锁文件   记着 %d 个 Skill，其中 %d 个本机已经找不到\n",
			report.Sync.LockSkills, report.Sync.LockMissing)
	}
}
