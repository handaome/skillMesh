package cmd

import (
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// watchState 是 watch 的运行状态，落在配置目录里。
const (
	pidFileName   = "watch.pid"
	pauseFileName = "watch.paused"
	logFileName   = "watch.log"
)

// defaultWatchInterval 是轮询间隔。
//
// ponytail: 用轮询而不是 fsnotify——少一个依赖、跨平台、行为可预测，
// 代价是空转时每轮都要 stat 一遍所有文件。要更省资源再上文件监听。
const defaultWatchInterval = 30 * time.Second

func newWatchCmd() *cobra.Command {
	var interval time.Duration

	cmd := &cobra.Command{
		Use:   "watch",
		Short: "后台盯住已授权目录，变了就同步",
		Long: "启动时先全量对账一次（弥补错过的文件系统通知），之后按间隔检查：\n" +
			"内容变了才触发同步，不变就什么都不做。\n" +
			"pause 只暂停同步，不影响 Agent 读取现有文件。",
		Args: cobra.NoArgs,
	}
	cmd.AddCommand(watchRunCmd(&interval), watchStartCmd(), watchStatusCmd(),
		watchPauseCmd("pause"), watchResumeCmd(), watchStopCmd())
	return cmd
}

func watchRunCmd(interval *time.Duration) *cobra.Command {
	cmd := &cobra.Command{
		Use:   "run",
		Short: "前台运行监听循环",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if *interval <= 0 {
				*interval = defaultWatchInterval
			}
			return runWatchLoop(cmd.Context(), *interval)
		},
	}
	cmd.Flags().DurationVar(interval, "interval", defaultWatchInterval, "检查间隔")
	return cmd
}

func watchStartCmd() *cobra.Command {
	var interval time.Duration
	cmd := &cobra.Command{
		Use:   "start",
		Short: "后台启动监听",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			if pid, running := watchRunning(); running {
				fmt.Printf("已经在运行（PID %d）。\n", pid)
				return nil
			}
			if _, paused := watchPaused(); paused {
				fmt.Println("当前是暂停状态，先 skillmesh watch resume。")
				return nil
			}
			exe, err := os.Executable()
			if err != nil {
				return err
			}
			logPath := watchLogPath()
			if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
				return err
			}
			logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
			if err != nil {
				return err
			}
			defer logFile.Close()

			child := exec.Command(exe, "watch", "run", "--interval", interval.String())
			child.Stdout = logFile
			child.Stderr = logFile
			// 独立进程组：父进程退出后它继续跑（Windows 上 DetachProcess 即可）。
			child.SysProcAttr = detachAttr()
			if err := child.Start(); err != nil {
				return err
			}
			if err := os.WriteFile(watchPidPath(), []byte(strconv.Itoa(child.Process.Pid)), 0o644); err != nil {
				return err
			}
			fmt.Printf("已后台启动（PID %d），日志 %s\n", child.Process.Pid, logPath)
			return nil
		},
	}
	cmd.Flags().DurationVar(&interval, "interval", defaultWatchInterval, "检查间隔")
	return cmd
}

func watchStatusCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "status",
		Short: "看监听是不是在跑、有没有暂停",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			pid, running := watchRunning()
			if running {
				fmt.Printf("运行中（PID %d）\n", pid)
			} else {
				fmt.Println("没在运行")
			}
			if _, paused := watchPaused(); paused {
				fmt.Println("已暂停")
			}
			fmt.Printf("日志 %s\n", watchLogPath())
			return nil
		},
	}
}

func watchPauseCmd(action string) *cobra.Command {
	return &cobra.Command{
		Use:   action,
		Short: "只暂停同步，不动 Agent 已有的文件",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			path := watchPausePath()
			if action == "pause" {
				if err := os.WriteFile(path, []byte(time.Now().Format(time.RFC3339)), 0o644); err != nil {
					return err
				}
				fmt.Println("已暂停同步。")
				return nil
			}
			if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
				return err
			}
			fmt.Println("已恢复同步。")
			return nil
		},
	}
}

func watchResumeCmd() *cobra.Command { return watchPauseCmd("resume") }

func watchStopCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "stop",
		Short: "停掉后台监听",
		Args:  cobra.NoArgs,
		RunE: func(_ *cobra.Command, _ []string) error {
			pid, running := watchRunning()
			if !running {
				fmt.Println("本来就没在跑。")
				return os.Remove(watchPidPath())
			}
			process, err := os.FindProcess(pid)
			if err != nil {
				return err
			}
			if err := process.Kill(); err != nil {
				return fmt.Errorf("结束 PID %d 失败: %w", pid, err)
			}
			_ = os.Remove(watchPidPath())
			fmt.Printf("已停止（PID %d）。\n", pid)
			return nil
		},
	}
}

// runWatchLoop 是监听主循环。
// runWatchLoop 是监听主循环：文件事件与定时轮询都只是「触发扫描」的手段，
// 扫描与同步逻辑共用一套（设计文档 §6.2）。
//
// 文件事件快但不保证不丢（编辑器批量写、网络盘、休眠唤醒都可能漏），
// 所以轮询一直留着，兼作兜底和启动时的全量 reconcile。
func runWatchLoop(ctx context.Context, interval time.Duration) error {
	// 启动先全量对账：基线必须自己找回来，不能指望事件一条不漏。
	if err := reconcile(ctx); err != nil {
		logf("首次对账失败: %v", err)
	}
	last := skillSnapshot()

	watcher, err := watchEvents(ctx)
	var events <-chan struct{}
	if err != nil {
		logf("文件监听不可用（%v），只用轮询", err)
	} else {
		defer watcher.close()
		events = watcher.events
	}

	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-events:
			// 事件只说明「可能有变化」，别立刻扫——连续写入时先静默一小会儿（防抖）。
			if !sleepQuiet(ctx, debounceWindow) {
				return nil
			}
		case <-ticker.C:
		}
		if _, paused := watchPaused(); paused {
			continue
		}
		current := skillSnapshot()
		if sameSnapshot(last, current) {
			continue
		}
		logf("检测到内容变化，开始同步")
		if err := reconcile(ctx); err != nil {
			logf("同步失败: %v", err)
		}
		if watcher != nil {
			// 新装进来的 Skill 目录也得纳入监听
			watcher.refresh()
		}
		last = skillSnapshot()
	}
}

// debounceWindow 是事件后的静默期：编辑器写文件往往分好几次，
// 立刻扫会扫到写了一半的内容（设计文档 §6.2 防抖）。
const debounceWindow = 2 * time.Second

// dirWatcher 是目录监听器。
type dirWatcher struct {
	events  <-chan struct{}
	refresh func()
	close   func()
}

// watchEvents 监听 Agent 目录以及其中每个 Skill 目录。
//
// fsnotify 不是递归的：盯着根目录只能看到「新建/删除 Skill 目录」，
// 改 Skill 里的文件必须单独盯那个目录。
// refresh 在每次对账后调用，把新出现的 Skill 目录纳入监听。
func watchEvents(ctx context.Context) (*dirWatcher, error) {
	watcher, err := fsnotify.NewWatcher()
	if err != nil {
		return nil, err
	}
	watched := map[string]bool{}
	trigger := make(chan struct{}, 1)
	done := make(chan struct{})

	refresh := func() {
		for _, dir := range watchedDirs() {
			if watched[dir] {
				continue
			}
			if _, err := os.Stat(dir); err != nil {
				continue // 目录还不存在，install 之后 refresh 会补上
			}
			if err := watcher.Add(dir); err != nil {
				logf("监听 %s 失败: %v", dir, err)
				continue
			}
			watched[dir] = true
		}
	}
	refresh()
	if len(watched) == 0 {
		watcher.Close()
		return nil, fmt.Errorf("没有可监听的目录")
	}

	go func() {
		defer close(done)
		defer watcher.Close()
		for {
			select {
			case <-ctx.Done():
				return
			case event, ok := <-watcher.Events:
				if !ok {
					return
				}
				if event.Op&(fsnotify.Write|fsnotify.Create|fsnotify.Remove|
					fsnotify.Rename) == 0 {
					continue
				}
				select {
				case trigger <- struct{}{}: // 已在等就跳过，合并成一次扫描
				default:
				}
			case _, ok := <-watcher.Errors:
				if !ok {
					return
				}
			}
		}
	}()

	return &dirWatcher{
		events:  trigger,
		refresh: refresh,
		close:   func() { watcher.Close(); <-done },
	}, nil
}

// watchedDirs 列出该监听的目录：Agent 根目录 + 其中每个 Skill 目录 + 额外配置的目录。
func watchedDirs() []string {
	var dirs []string
	seen := map[string]bool{}
	add := func(root string) {
		for _, dir := range append([]string{root}, localSkills(root)...) {
			if seen[dir] {
				continue
			}
			seen[dir] = true
			dirs = append(dirs, dir)
		}
	}
	for _, agentName := range syncAgentNames(nil) {
		root, err := agent.Dir(Cfg, agentName, "user", "")
		if err != nil {
			continue
		}
		add(root)
	}
	// 额外配置的目录（项目级、自定义目录）同样要盯着（设计文档 §6.1）。
	for _, dir := range Cfg.Sync.ExtraDirs {
		add(dir)
	}
	return dirs
}

// sleepQuiet 静默一小会儿，返回 false 表示上下文已经取消。
func sleepQuiet(ctx context.Context, d time.Duration) bool {
	timer := time.NewTimer(d)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

// reconcile 跑一轮完整同步，并把 auto_safe 的 Git 来源也更新掉
// （设计文档 §7.2：自动更新只在「无本地冲突且策略允许」时发生）。
func reconcile(ctx context.Context) error {
	results, _, err := runSync(ctx, SyncOptions{})
	if err != nil {
		return err
	}
	if err := applyAutoSafeUpdates(ctx); err != nil {
		logf("自动更新失败: %v", err)
	}
	for _, result := range results {
		if result.Outcome == Failed || result.Outcome == Conflicted {
			logf("%s %s：%s", result.Name, result.Outcome, oneLine(result.Detail))
			continue
		}
		logf("%s %s：%s", result.Name, result.Outcome, oneLine(result.Detail))
	}
	return nil
}

// applyAutoSafeUpdates 更新标记了 auto_safe 的 Git 来源。
// 本地有改动的那些由 applyUpdates 里的守卫挡掉，不会被覆盖。
//
// notify 策略的来源只提示差异、不自动应用——
// 用户确认后才应用是 notify 的语义（设计文档 §7.2）。
// manual 的来源连查都不查。
func applyAutoSafeUpdates(ctx context.Context) error {
	projectRoot := mustGetwd()
	lock, err := lockfile.Load(projectRoot)
	if err != nil {
		return err
	}
	if autoSafe(lock) == 0 && countByPolicy(lock, "notify") == 0 {
		return nil
	}
	updates, err := CheckUpdatesBackground(ctx, lock)
	if err != nil {
		return err
	}
	// notify：只报差异，让用户自己决定。
	for _, update := range FilterByPolicy(lock, updates, "notify") {
		logf("%s 有新版本可用（本地 %s → 远端 %s）：skillmesh update %s",
			update.Name, short(update.LocalCommit), short(update.RemoteCommit), update.Name)
	}
	selected := FilterAutoSafe(lock, updates)
	if len(selected) == 0 {
		return nil
	}
	return applyUpdates(ctx, projectRoot, &lock, selected, false)
}

// countByPolicy 数一数有多少个来源声明了某个更新策略。
func countByPolicy(lock lockfile.Lock, policy string) int {
	count := 0
	for _, entry := range lock.Skills {
		if entry.Repository != "" && policyOf(lock, entry.Name) == policy {
			count++
		}
	}
	return count
}

// skillSnapshot 拍一张「每个 Skill 的内容摘要」快照。
func skillSnapshot() map[string]string {
	snapshot := map[string]string{}
	for _, agentName := range syncAgentNames(nil) {
		dir, err := agent.Dir(Cfg, agentName, "user", "")
		if err != nil {
			continue
		}
		for _, path := range localSkills(dir) {
			digest, err := skillscan.DirDigest(path)
			if err != nil {
				continue
			}
			snapshot[path] = digest
		}
	}
	return snapshot
}

func sameSnapshot(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for path, digest := range a {
		if b[path] != digest {
			return false
		}
	}
	return true
}

func watchPidPath() string   { return filepath.Join(Cfg.ConfigDir, pidFileName) }
func watchPausePath() string { return filepath.Join(Cfg.ConfigDir, pauseFileName) }
func watchLogPath() string   { return filepath.Join(Cfg.CacheDir, logFileName) }

// watchRunning 看 PID 文件里的进程还活着没。
func watchRunning() (int, bool) {
	data, err := os.ReadFile(watchPidPath())
	if err != nil {
		return 0, false
	}
	pid, err := strconv.Atoi(strings.TrimSpace(string(data)))
	if err != nil || pid <= 0 {
		return 0, false
	}
	return pid, processAlive(pid)
}

func watchPaused() (time.Time, bool) {
	data, err := os.ReadFile(watchPausePath())
	if err != nil {
		return time.Time{}, false
	}
	at, err := time.Parse(time.RFC3339, strings.TrimSpace(string(data)))
	if err != nil {
		return time.Time{}, true
	}
	return at, true
}

func logf(format string, args ...any) {
	line := fmt.Sprintf("%s %s\n", time.Now().Format(time.RFC3339), fmt.Sprintf(format, args...))
	path := watchLogPath()
	// 缓存目录可能还不存在——守护进程是第一个跑起来的东西，不能假设它在了。
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err == nil {
		if file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err == nil {
			fmt.Fprint(file, line)
			file.Close()
		}
	}
	fmt.Print(line)
}
