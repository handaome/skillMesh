package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncqueue"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
	"github.com/spf13/cobra"
)

// cliVersion 报给注册中心，方便知道谁在提交同步。
const cliVersion = "0.1.0"

// syncVisibility 是同步用的可见性。
// 个人云端备份默认私有，不等于团队发布（设计文档 §2 的概念区分）。
const syncVisibility = "private"

// syncOutcome 是单个 Skill 的同步结果。
type syncOutcome string

const (
	synced     syncOutcome = "已同步"
	skipped    syncOutcome = "无变化"
	uploaded   syncOutcome = "新版本"
	fetched    syncOutcome = "已拉取"
	conflicted syncOutcome = "冲突"
	failed     syncOutcome = "失败"
)

// syncOptions 是一次同步的可调项。
type syncOptions struct {
	Agents    []string
	Only      string // --skill，只同步这一个
	Direction string // upload / download / both
	SkipNew   bool   // 只同步「已经收编过」的，新发现的先留在本地
	Resolve   string // keep-local / keep-cloud / keep-both，空表示不处理冲突
	RetryOnly bool   // 只重试队列里到点的任务
	DryRun    bool
}

func newSyncCmd() *cobra.Command {
	options := syncOptions{}
	cmd := &cobra.Command{
		Use:   "sync",
		Short: "把本地 Agent 目录里的 Skill 与个人私有空间对账",
		Long: "扫描已授权的 Agent 目录，比较「基线 / 本地 / 云端」三份内容摘要：\n" +
			"只有一边变了就顺着变，两边都变了按冲突处理，绝不自动覆盖。\n\n" +
			"这是「本地 ↔ 个人云端」那条链路（设计文档 §3.1、§6.3），不是团队发布。\n" +
			"只同步、从不删除云端内容——目录扫不到不该等于云端被删。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			if options.Direction != "upload" && options.Direction != "download" &&
				options.Direction != "both" {
				return fmt.Errorf("--direction 只能是 upload / download / both")
			}
			results, queue, err := runSync(cmd.Context(), options)
			if len(results) == 0 && err == nil {
				if options.SkipNew {
					fmt.Println("没有「已经收编过」的 Skill；首次发现的留在本地，" +
						"去掉 --skip-new 才会备份。")
					return nil
				}
				fmt.Println("授权目录里没有找到含 SKILL.md 的目录。")
				return nil
			}
			reportErr := reportSync(results, queue)
			if err != nil {
				return err
			}
			return reportErr
		},
	}
	cmd.Flags().StringSliceVar(&options.Agents, "agent", nil, "只同步指定 Agent 的目录，不填用全部启用的")
	cmd.Flags().StringVar(&options.Only, "skill", "", "只同步这一个 Skill")
	cmd.Flags().StringVar(&options.Direction, "direction", "both", "同步方向：upload / download / both")
	cmd.Flags().BoolVar(&options.SkipNew, "skip-new", false,
		"只同步已经收编过的 Skill，新发现的先留在本地（设计文档 §3.1 首次发现策略）")
	cmd.Flags().StringVar(&options.Resolve, "resolve", "",
		"冲突解决方式：keep-local / keep-cloud / keep-both")
	cmd.Flags().BoolVar(&options.RetryOnly, "retry-only", false, "只重试失败队列里到点的任务")
	cmd.Flags().BoolVar(&options.DryRun, "dry-run", false, "只报出会同步什么，不落盘")
	return cmd
}

// syncResult 是一条同步结果。
type syncResult struct {
	Name    string
	Path    string
	Outcome syncOutcome
	Detail  string
}

func runSync(ctx context.Context, options syncOptions) ([]syncResult, syncqueue.Queue, error) {
	targets, err := syncTargets(options)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	client, err := newClient(ctx)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	state, err := syncstate.Load(cfg.ConfigDir)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	queue, err := syncqueue.Load(cfg.ConfigDir)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	targets = filterByBackoff(targets, queue, options)

	var results []syncResult
	// 同一份内容可能同时出现在多个位置：多个 Agent 目录、符号链接目录、
	// 甚至是某个 Skill 内部的嵌套子 Skill。按内容摘要去重，只同步一次。
	uploaded := map[string]string{}
	for _, target := range targets {
		digest, digestErr := skillscan.DirDigest(target.Path)
		if digestErr == nil {
			if first, seen := uploaded[digest]; seen {
				results = append(results, syncResult{Name: target.Name, Path: target.Path,
					Outcome: skipped,
					Detail:  "与 " + first + " 内容一致，已同步过，跳过"})
				continue
			}
			uploaded[digest] = target.Name
		}
		result, entry, err := syncOne(ctx, client, target, options)
		if err != nil {
			results = append(results, failedResult(target, err.Error()))
			continue
		}
		if result.Outcome == failed || result.Outcome == conflicted {
			// 没同步成的不算数，别把后面同内容的也一起挡掉
			delete(uploaded, digest)
		}
		if result.Outcome == failed && !options.DryRun {
			// 只有网络类失败才值得重试；校验不通过重试一百次也一样。
			if isTransient(result.Detail) {
				queue.Record(target.Name, target.Agent, target.Path, result.Detail)
			}
		}
		if result.Outcome != failed && !options.DryRun {
			queue.Clear(target.Name, target.Agent)
		}
		// entry 为空表示这次没同步过（冲突没解决），基线不动。
		if entry.Name != "" && !options.DryRun {
			state.Upsert(entry)
		}
		results = append(results, result)
	}
	if !options.DryRun {
		if err := state.Save(cfg.ConfigDir); err != nil {
			return results, queue, err
		}
		if err := queue.Save(cfg.ConfigDir); err != nil {
			return results, queue, err
		}
	}
	return results, queue, nil
}

// queueKey 拼队列/基线的主键。用分隔符避免名字里带分隔符时撞车。
func queueKey(name, agentName string) string {
	return name + "/" + agentName
}

// filterByBackoff 按退避时间安排任务：
//   - 默认：还在退避期内的先不碰，避免网络不通时每次运行都硬撞一遍
//   - --retry-only：只留下队列里到点且没放弃的任务
func filterByBackoff(targets []syncTarget, queue syncqueue.Queue, options syncOptions) []syncTarget {
	pending := map[string]syncqueue.Entry{}
	for _, entry := range queue.Pending() {
		pending[queueKey(entry.Name, entry.Agent)] = entry
	}
	if options.RetryOnly && len(pending) == 0 {
		return nil
	}
	now := time.Now()
	kept := make([]syncTarget, 0, len(targets))
	for _, target := range targets {
		entry, queued := pending[queueKey(target.Name, target.Agent)]
		if options.RetryOnly {
			if queued && !entry.Abandoned() && entry.Ready(now) {
				kept = append(kept, target)
			}
			continue
		}
		if queued && !entry.Abandoned() && !entry.Ready(now) {
			continue
		}
		kept = append(kept, target)
	}
	return kept
}

// isTransient 判断失败值不值得进重试队列。
func isTransient(detail string) bool {
	for _, marker := range []string{
		"连接", "connection", "timeout", "deadline", "no such host", "EOF",
		"reset by peer", "temporarily", "TLS", "(500)", "(502)", "(503)", "(504)",
		// 429 是限流，等一会儿就能过——不进队列就等于白做了
		"(429)", "RATE_LIMITED", "rate limit", "too many requests",
	} {
		if strings.Contains(strings.ToLower(detail), strings.ToLower(marker)) {
			return true
		}
	}
	return false
}

// syncTarget 是一个待对账的 Skill。
type syncTarget struct {
	Name     string
	Agent    string
	Path     string
	BaseHash string
	SkillID  string
}

// syncTargets 列出所有要处理的 Skill，并带上各自的基线。
func syncTargets(options syncOptions) ([]syncTarget, error) {
	state, err := syncstate.Load(cfg.ConfigDir)
	if err != nil {
		return nil, err
	}
	var targets []syncTarget
	for _, agentName := range syncAgentNames(options.Agents) {
		dir, err := agent.Dir(cfg, agentName, "user", "")
		if err != nil {
			return nil, err
		}
		for _, path := range localSkills(dir) {
			name := skillscan.SkillName(path)
			if options.Only != "" && name != options.Only {
				continue
			}
			// 第一次看到就先记一笔，但不等于收编——收编由同步本身决定
			entry, known := state.Find(name, agentName)
			if !known {
				entry = state.Discover(name, agentName)
			}
			if options.SkipNew && state.New(name, agentName) {
				continue
			}
			targets = append(targets, syncTarget{
				Name: name, Agent: agentName, Path: path,
				BaseHash: entry.BaseHash, SkillID: entry.SkillID,
			})
		}
	}
	return targets, nil
}

func syncAgentNames(selected []string) []string {
	if len(selected) > 0 {
		return selected
	}
	var names []string
	for _, name := range agent.Supported {
		if agentCfg, ok := cfg.Agents[name]; ok && agentCfg.Enabled {
			names = append(names, name)
		}
	}
	return names
}

// localSkills 列出目录下的 Skill 候选（含目录本身）。
func localSkills(dir string) []string {
	if _, err := os.Stat(filepath.Join(dir, "SKILL.md")); err == nil {
		return []string{dir}
	}
	candidates, err := skillscan.Candidates(dir)
	if err != nil {
		return nil
	}
	dirs := make([]string, 0, len(candidates))
	for _, candidate := range candidates {
		dirs = append(dirs, candidate.Dir)
	}
	return dirs
}

// syncOne 对账一个 Skill。返回结果，以及同步成功后要写进基线的内容（没变就返回 nil）。
func syncOne(ctx context.Context, client *apiclient.Client, target syncTarget,
	options syncOptions) (syncResult, syncstate.Entry, error) {

	if err := skillscan.ValidateDir(target.Path, skillscan.DefaultLimits); err != nil {
		return failedResult(target, err.Error()), syncstate.Entry{}, nil
	}
	findings, err := skillscan.ScanSecrets(target.Path)
	if err != nil {
		return failedResult(target, err.Error()), syncstate.Entry{}, nil
	}
	if len(findings) > 0 {
		return failedResult(target, skillscan.FormatFindings(findings)), syncstate.Entry{}, nil
	}
	local, err := packager.Pack(target.Path)
	if err != nil {
		return failedResult(target, err.Error()), syncstate.Entry{}, nil
	}
	localHash := manifestDigest(local.Manifest)
	slug, err := skillscan.SlugFromDirName(filepath.Base(target.Path))
	if err != nil {
		return failedResult(target, err.Error()), syncstate.Entry{}, nil
	}

	skill, found, err := client.FindSkillBySlug(ctx, slug)
	if err != nil {
		return failedResult(target, err.Error()), syncstate.Entry{}, nil
	}
	cloudHash := ""
	if found {
		// 云端已经有这个 Skill，但本机状态可能没记它的 ID（换过机器、或
		// 之前是别的路径同步的）。补上，否则变更集会提交一个空 skill_id。
		if target.SkillID == "" {
			target.SkillID = skill.ID
		}
		cloudHash, err = cloudDigest(ctx, client, skill.ID, local.SHA256)
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
	}

	decision := syncstate.Decide(target.BaseHash, localHash, cloudHash)
	changeset := ""
	if found && decision != syncstate.UpToDate {
		// 云端也判一次：别的设备可能在两次请求之间动过内容（详细设计 §3.6）。
		var err error
		changeset, err = confirmWithCloud(ctx, client, target, local, localHash, decision, options.DryRun)
		if err != nil {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: conflicted,
				Detail: err.Error()}, syncstate.Entry{}, nil
		}
	}
	result, entry, err := applyDecision(ctx, client, target, decision, local, localHash,
		skill, found, options)
	if changeset != "" && result.Outcome == uploaded {
		result.Detail += "（云端记录 " + short(changeset) + "）"
	}
	return result, entry, err
}

// confirmWithCloud 把这次的基线和本地摘要报给服务端，由它回答还成不成立。
// 服务端说冲突就以它为准——它看得到别的设备，服务端之间才比得准。
func confirmWithCloud(ctx context.Context, client *apiclient.Client, target syncTarget,
	local packager.Artifact, localHash string, decision syncstate.Decision,
	dryRun bool) (string, error) {

	if dryRun {
		// --dry-run 的承诺是「什么都不改」：不注册设备、不提交变更集。
		return "", nil
	}
	device, err := ensureDevice(ctx, client)
	if err != nil {
		return "", err
	}
	direction := "bidirectional"
	if decision == syncstate.Upload {
		direction = "upload"
	} else if decision == syncstate.Download {
		direction = "download"
	}
	// 幂等键用「内容摘要 + 基线」：同样的内容重复提交只会有一条记录。
	key := localHash[:16] + "-" + short(target.BaseHash)
	response, err := client.SubmitChangeset(ctx, device, key, apiclient.SyncChangesetRequest{
		SkillID:   target.SkillID,
		Direction: direction,
		BaseHash:  target.BaseHash,
		LocalHash: localHash,
		LocalPath: target.Path,
		Manifest:  toManifest(local.Manifest),
	})
	if err != nil {
		// 服务端不可达不该挡住同步本身：本地三方比较已经够用，
		// 报不出错比拒绝同步好。
		if apiclient.CodeOf(err) == "" {
			return "", nil
		}
		return "", err
	}
	if response.Status == "conflict" {
		return "", fmt.Errorf("云端已判冲突（%s），不自动覆盖", response.Message)
	}
	return response.ChangesetID, nil
}

// ensureDevice 拿本机在注册中心的设备 ID，没有就注册一个。
func ensureDevice(ctx context.Context, client *apiclient.Client) (string, error) {
	device, err := client.RegisterDevice(ctx, apiclient.MachineKey(), runtime.GOOS, cliVersion)
	if err != nil {
		return "", err
	}
	return device.ID, nil
}

// applyDecision 按判定结果执行。冲突在没有 --resolve 时只报告不动手。
func applyDecision(ctx context.Context, client *apiclient.Client, target syncTarget,
	decision syncstate.Decision, local packager.Artifact, localHash string,
	skill apiclient.Skill, found bool, options syncOptions) (syncResult, syncstate.Entry, error) {

	entry := syncstate.Entry{
		Name: target.Name, Agent: target.Agent, BaseHash: localHash, SkillID: target.SkillID,
	}

	switch decision {
	case syncstate.UpToDate:
		if found {
			entry.SkillID = skill.ID
		}
		return syncResult{Name: target.Name, Path: target.Path,
			Outcome: skipped, Detail: localHash[:12]}, entry, nil

	case syncstate.Download:
		if options.Direction == "upload" {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: skipped,
				Detail: "云端有更新，本次只上传；要拉取用 --direction download"}, entry, nil
		}
		if options.DryRun {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: skipped,
				Detail: "将拉取云端版本"}, entry, nil
		}
		version, err := downloadInto(ctx, client, target, skill)
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		return syncResult{Name: target.Name, Path: target.Path, Outcome: fetched,
			Detail: version}, entry, nil

	case syncstate.Conflict:
		return resolveConflict(ctx, client, target, local, localHash, skill, options)

	default: // Upload
		if options.Direction == "download" {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: skipped,
				Detail: "本地有更新，本次只下载"}, entry, nil
		}
		if options.DryRun {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: skipped,
				Detail: "将上传新版本（演练，未真的上传）"}, entry, nil
		}
		result, err := publishOne(ctx, client, publishTarget{Dir: target.Path,
			RelPath: filepath.Base(target.Path)},
			publishOptions{Visibility: syncVisibility})
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		detail := result.Version
		if !found {
			detail = "云端新建 " + result.Version
		}
		entry.SkillID = result.SkillID
		entry.LastVersion = result.Version
		saveBaseline(target, local, &entry)
		return syncResult{Name: target.Name, Path: target.Path,
			Outcome: uploaded, Detail: detail}, entry, nil
	}
}

// resolveConflict 处理两边都改过的情况。
// 没有 --resolve 就只报告——自动覆盖等于替用户做决定（设计文档 §6.3）。
func resolveConflict(ctx context.Context, client *apiclient.Client, target syncTarget,
	local packager.Artifact, localHash string, skill apiclient.Skill,
	options syncOptions) (syncResult, syncstate.Entry, error) {

	entry := syncstate.Entry{Name: target.Name, Agent: target.Agent,
		BaseHash: localHash, SkillID: target.SkillID}
	hint := fmt.Sprintf("本地 %s vs 云端 %s，用 --resolve keep-local/keep-cloud/keep-both 处理",
		localHash[:8], "已变")

	switch options.Resolve {
	case "keep-local":
		result, err := publishOne(ctx, client, publishTarget{Dir: target.Path,
			RelPath: filepath.Base(target.Path)},
			publishOptions{Visibility: syncVisibility})
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		entry.SkillID = result.SkillID
		entry.LastVersion = result.Version
		return syncResult{Name: target.Name, Path: target.Path, Outcome: uploaded,
			Detail: "保留本地，已上传 " + result.Version}, entry, nil

	case "keep-cloud":
		version, err := downloadInto(ctx, client, target, skill)
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		return syncResult{Name: target.Name, Path: target.Path, Outcome: fetched,
			Detail: "保留云端，已拉取 " + version}, entry, nil

	case "keep-both":
		// 备份放在隐藏目录：扫描会跳过隐藏目录，它不会变成下一个「待同步的 Skill」。
		backup := filepath.Join(filepath.Dir(target.Path),
			"."+target.Name+".conflict-"+localHash[:8])
		if err := installer.InstallAtomic(target.Path, backup, true); err != nil {
			return failedResult(target, "备份本地版本失败: "+err.Error()), syncstate.Entry{}, nil
		}
		result, err := publishOne(ctx, client, publishTarget{Dir: target.Path,
			RelPath: filepath.Base(target.Path)},
			publishOptions{Visibility: syncVisibility})
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		entry.SkillID = result.SkillID
		entry.LastVersion = result.Version
		return syncResult{Name: target.Name, Path: target.Path, Outcome: uploaded,
			Detail: fmt.Sprintf("本地版本备份到 %s，云端上传 %s", backup, result.Version)}, entry, nil

	default:
		// 先按文件合一次（设计文档 §6.3）：两边改的不是同一处就合掉，
		// 真的合不了才落到「冲突副本，等人来定」。
		base, _ := syncstate.Load(cfg.ConfigDir)
		prior, _ := base.Find(target.Name, target.Agent)
		if ok, note, mergeErr := mergeConflict(ctx, client, target, prior, local, skill); mergeErr == nil && ok {
			if result, err := publishOne(ctx, client, publishTarget{Dir: target.Path,
				RelPath: filepath.Base(target.Path)},
				publishOptions{Visibility: syncVisibility}); err == nil {
				entry.SkillID = result.SkillID
				entry.LastVersion = result.Version
				entry.BaseHash = localHash
				return syncResult{Name: target.Name, Path: target.Path, Outcome: uploaded,
					Detail: note + "，已上传 " + result.Version}, entry, nil
			}
		} else if mergeErr == nil && note != "" {
			hint = note + "；用 --resolve keep-local/keep-cloud/keep-both 强行决定"
		}
		// 让服务端也知道这次冲突了：另一台设备可能正等着知道这件事。
		if !options.DryRun {
			_ = client.ReportAuditEvent(ctx, "sync.conflict_reported", target.SkillID, "denied",
				map[string]string{"skill": target.Name, "local_hash": localHash[:12]})
		}
		return syncResult{Name: target.Name, Path: target.Path,
			Outcome: conflicted, Detail: hint}, syncstate.Entry{}, nil
	}
}

// downloadInto 把云端最新已发布版本装回本机目录。
func downloadInto(ctx context.Context, client *apiclient.Client, target syncTarget,
	skill apiclient.Skill) (string, error) {

	versions, err := client.Versions(ctx, skill.ID)
	if err != nil {
		return "", err
	}
	selected, err := pickVersion(versions, "", false, lockfileEntry(target), false)
	if err != nil {
		return "", err
	}
	data, err := client.DownloadArtifact(ctx, selected.Artifact.ID)
	if err != nil {
		return "", err
	}
	work, err := os.MkdirTemp("", "skillmesh-sync-*")
	if err != nil {
		return "", err
	}
	defer os.RemoveAll(work)
	skillDir, err := installer.ExtractZip(data, work)
	if err != nil {
		return "", err
	}
	if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
		return "", err
	}
	if err := installer.InstallAtomic(skillDir, target.Path, true); err != nil {
		return "", err
	}
	if err := installer.MarkInstalled(target.Path, "skillmesh:"+skill.ID); err != nil {
		return "", err
	}
	return selected.Version, nil
}

func lockfileEntry(target syncTarget) lockfile.LockedSkill {
	return lockfile.LockedSkill{Name: target.Name, SourceID: target.SkillID}
}

// saveBaseline 记下这次同步出去的逐文件摘要和内容副本。
// 下次两边都改时，三方合并要靠它当基准（设计文档 §6.3）。
func saveBaseline(target syncTarget, local packager.Artifact, entry *syncstate.Entry) {
	files, err := skillscan.DirHashes(target.Path)
	if err != nil {
		return
	}
	hashes := make(map[string]string, len(files))
	for _, file := range files {
		hashes[file.Path] = file.SHA256
	}
	entry.BaseFiles = hashes
	entry.BaseHash = localHashOf(hashes)

	snapshot := filepath.Join(cfg.ConfigDir, "sync-base",
		filepath.Join(target.Agent, target.Name))
	if err := os.RemoveAll(snapshot); err != nil {
		return
	}
	// 副本只留一份，目录换掉就行；失败就不给基线，下次合并会老实说「合不了」
	_ = copyTree(target.Path, snapshot)
	if _, err := os.Stat(snapshot); err == nil {
		entry.BaseSnapshot = snapshot
	}
}

// copyTree 复制目录内容（跳过隐藏文件，与打包口径一致）。
func copyTree(from, to string) error {
	return filepath.Walk(from, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, relErr := filepath.Rel(from, path)
		if relErr != nil {
			return relErr
		}
		if info.IsDir() {
			if strings.HasPrefix(info.Name(), ".") && path != from {
				return filepath.SkipDir
			}
			return os.MkdirAll(filepath.Join(to, rel), 0o755)
		}
		if strings.HasPrefix(info.Name(), ".") {
			return nil
		}
		data, readErr := os.ReadFile(path)
		if readErr != nil {
			return readErr
		}
		return os.WriteFile(filepath.Join(to, rel), data, info.Mode().Perm())
	})
}

func localHashOf(files map[string]string) string {
	list := make([]skillscan.FileDigest, 0, len(files))
	for path, sum := range files {
		list = append(list, skillscan.FileDigest{Path: path, SHA256: sum})
	}
	return skillscan.Digest(list)
}

func failedResult(target syncTarget, why string) syncResult {
	return syncResult{Name: target.Name, Path: target.Path, Outcome: failed, Detail: why}
}

func manifestDigest(entries []packager.Entry) string {
	files := make([]skillscan.FileDigest, 0, len(entries))
	for _, entry := range entries {
		files = append(files, skillscan.FileDigest{Path: entry.Path, SHA256: entry.SHA256})
	}
	return skillscan.Digest(files)
}

// cloudDigest 取云端最新已发布版本的内容摘要。
// 老版本没有 manifest，退化成比 zip 摘要（拿不到就返回空串，判定为「云端没有可比的内容」）。
func cloudDigest(ctx context.Context, client *apiclient.Client, skillID,
	localZipSHA string) (string, error) {

	versions, err := client.Versions(ctx, skillID)
	if err != nil {
		return "", err
	}
	for _, item := range versions.Items {
		if item.Status != "published" {
			continue
		}
		if len(item.Manifest) > 0 {
			return remoteDigest(item), nil
		}
		if item.Artifact != nil && item.Artifact.SHA256 == localZipSHA {
			return "", nil // 内容和本地一样，判定会走 UpToDate
		}
		return localZipSHA + "-zip", nil
	}
	return "", nil
}

func remoteDigest(version apiclient.SkillVersion) string {
	files := make([]skillscan.FileDigest, 0, len(version.Manifest))
	for _, entry := range version.Manifest {
		files = append(files, skillscan.FileDigest{Path: entry.Path, SHA256: entry.SHA256})
	}
	return skillscan.Digest(files)
}

// reportQueue 把待重试和已放弃的任务摊开：这些是需要人看的。
func reportQueue(queue syncqueue.Queue) {
	pending := queue.Pending()
	if len(pending) == 0 {
		return
	}
	for _, entry := range pending {
		if entry.Abandoned() {
			fmt.Printf("已放弃：%s（第 %d 次失败：%s）\n",
				entry.Name, entry.Attempts, entry.LastError)
			continue
		}
		fmt.Printf("待重试：%s（%s，第 %d 次失败：%s）\n", entry.Name, entry.NextAttemptAt,
			entry.Attempts, entry.LastError)
	}
	fmt.Println("skillmesh sync --retry-only 只跑这些到点的任务；skillmesh doctor 看队列概况。")
}

func reportSync(results []syncResult, queue syncqueue.Queue) error {
	counts := map[syncOutcome]int{}
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "SKILL\t结果\t说明")
	for _, result := range results {
		counts[result.Outcome]++
		fmt.Fprintf(w, "%s\t%s\t%s\n", result.Name, result.Outcome, result.Detail)
	}
	if err := w.Flush(); err != nil {
		return err
	}
	fmt.Printf("\n无变化 %d，上传 %d，拉取 %d，冲突 %d，失败 %d\n",
		counts[skipped], counts[uploaded], counts[fetched], counts[conflicted], counts[failed])
	if counts[conflicted] > 0 {
		return fmt.Errorf("%d 个存在冲突，需要 --resolve 处理", counts[conflicted])
	}
	if counts[failed] > 0 {
		return fmt.Errorf("%d 个同步失败", counts[failed])
	}
	return nil
}
