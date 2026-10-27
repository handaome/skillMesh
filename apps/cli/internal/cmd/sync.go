package cmd

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
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
	Skipped    syncOutcome = "无变化"
	Uploaded   syncOutcome = "新版本"
	fetched    syncOutcome = "已拉取"
	Conflicted syncOutcome = "冲突"
	// PendingDelete 本地删掉了文件，等用户确认再往云端传播。
	PendingDelete syncOutcome = "待确认"
	// Deleted 云端删掉了这个 Skill，本地那份也跟着进了回收站。
	Deleted syncOutcome = "已删除"
	// WouldPull 演练时才会出现：本机没有、正式跑会从云端装上。
	WouldPull syncOutcome = "将拉取"
	Failed    syncOutcome = "失败"
)

// SyncOptions 是一次同步的可调项。
type SyncOptions struct {
	Agents    []string
	Only      string // --skill，只同步这一个
	Direction string // upload / download / both
	// OnlyExisting 只处理已经收编过的那批：本地新发现的不上传，
	// 云端有而本机没有的也不装。原来拆成 --skip-new 与 --no-pull-new 两个开关，
	// 语义都是「别自作主张」，合成一个。
	OnlyExisting bool
	Resolve      string // keep-local / keep-cloud / keep-both，空表示不处理冲突
	DryRun       bool
	// AllowDelete 确认把「本地删掉的文件」也传到云端。
	// 不确认就不传：目录扫不到不该等于云端被删（设计文档 §6.3）。
	AllowDelete bool
	// CloudDeletionsKnown 是本次同步探测到的服务端状态，不是命令行参数：
	// 成功拿到了云端的已删除清单没有。拿不到就不能上传——不知道云端删了什么，
	// 上传会把删掉的 Skill 复活（云端查不到 → 判定为「该上传」）。
	CloudDeletionsKnown bool
}

func newSyncCmd() *cobra.Command {
	options := SyncOptions{}
	cmd := &cobra.Command{
		Use:   "sync",
		Short: "与云端双向同步：本机改的传上去，云端有而本机没有的装下来",
		Long: "扫描已授权的 Agent 目录，与个人私有空间双向对齐：\n" +
			"本机有而云端没有 → 传上去；云端有而本机没有 → 装下来；\n" +
			"两边都有就比内容：只有一边变了顺着变，两边都变了按冲突处理，绝不自动覆盖。\n\n" +
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
				if options.OnlyExisting {
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
	cmd.Flags().BoolVar(&options.OnlyExisting, "only-existing", false,
		"只处理已收编的 Skill：本地新发现的不上传，云端新增的也不装")
	cmd.Flags().StringVar(&options.Resolve, "resolve", "",
		"冲突解决方式：keep-local / keep-cloud / keep-both")
	cmd.Flags().BoolVar(&options.DryRun, "dry-run", false, "只报出会同步什么，不落盘")
	cmd.Flags().BoolVar(&options.AllowDelete, "allow-delete", false,
		"确认把本地删掉的文件也同步到云端（删除传播需要显式确认）")
	return cmd
}

// syncResult 是一条同步结果。
type syncResult struct {
	Name    string
	Path    string
	Outcome syncOutcome
	Detail  string
	// SkillID 是这次涉及的云端 Skill（没有就是纯本地的事）。
	// 上报审计时用它当目标：本地日志自己知道目标是谁，
	// 服务端那边得靠它把这条流水挂到 Skill 上。
	SkillID string
}

func runSync(ctx context.Context, options SyncOptions) ([]syncResult, syncqueue.Queue, error) {
	client, err := newClient(ctx)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	// 云端删掉的先处理：本地那份要先移走，
	// 否则它会被当成「云端没有、本地有」又传回云端。
	state, deleteResults, deletionsKnown, err := loadStateForSync(ctx, client, options)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	// 记下这次有没有拿到删除清单：拿不到的话下面一律不上传。
	options.CloudDeletionsKnown = deletionsKnown
	targets, err := SyncTargets(options)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	queue, err := syncqueue.Load(Cfg.ConfigDir)
	if err != nil {
		return nil, syncqueue.Queue{}, err
	}
	targets = filterByBackoff(targets, queue)

	var results []syncResult
	results = append(results, deleteResults...)
	// 云端删了、本地跟着删的这次也要留痕：这是最需要事后能查清的一类动作。
	for _, result := range deleteResults {
		recordSyncResult(ctx, client, SyncTarget{Name: result.Name, Path: result.Path}, result)
	}
	// 同一份内容可能同时出现在多个位置：多个 Agent 目录、符号链接目录、
	// 甚至是某个 Skill 内部的嵌套子 Skill。按内容摘要去重，只同步一次。
	Uploaded := map[string]string{}
	for i, target := range targets {
		progressStart(i+1, len(targets), target.Name)
		started := time.Now()
		digest, digestErr := skillscan.DirDigest(target.Path)
		if digestErr == nil {
			if first, seen := Uploaded[digest]; seen {
				detail := "与 " + first + " 内容一致，已同步过，跳过"
				results = append(results, syncResult{Name: target.Name, Path: target.Path,
					Outcome: Skipped, Detail: detail})
				progressDone(string(Skipped)+"："+detail, time.Since(started))
				continue
			}
			Uploaded[digest] = target.Name
		}
		result, entry, err := syncOne(ctx, client, target, options)
		if err != nil {
			failed := failedResult(target, err.Error())
			recordSyncResult(ctx, client, target, failed)
			results = append(results, failed)
			progressDone(string(Failed)+"："+oneLine(err.Error()), time.Since(started))
			continue
		}
		recordSyncResult(ctx, client, target, result)
		progressDone(progressText(result, options.DryRun), time.Since(started))
		if result.Outcome == Failed || result.Outcome == Conflicted {
			// 没同步成的不算数，别把后面同内容的也一起挡掉
			delete(Uploaded, digest)
		}
		if result.Outcome == Failed && !options.DryRun {
			// 只有网络类失败才值得重试；校验不通过重试一百次也一样。
			if isTransient(result.Detail) {
				queue.Record(target.Name, target.Agent, target.Path, result.Detail)
			}
		}
		if result.Outcome != Failed && !options.DryRun {
			queue.Clear(target.Name, target.Agent)
		}
		// entry 为空表示这次没同步过（冲突没解决），基线不动。
		if entry.Name != "" && !options.DryRun {
			state.Upsert(entry)
		}
		results = append(results, result)
	}
	// 云端有、本机没有的：一并装上。这是「多地同步」的另一半，
	// 不让用户再去记一个 restore 命令。
	if !options.OnlyExisting {
		results = append(results, pullMissing(ctx, client, options, &state)...)
	}
	if !options.DryRun {
		if err := state.Save(Cfg.ConfigDir); err != nil {
			return results, queue, err
		}
		if err := queue.Save(Cfg.ConfigDir); err != nil {
			return results, queue, err
		}
	}
	return results, queue, nil
}

// progressStart 打出「正在处理哪个」。同一行先不换行，处理完再补上结果。
//
// 同步要打包再上传，十几个 Skill 跑下来不是一瞬间的事——不出声就不知道卡在哪。
// 进度走 stderr，结果表格走 stdout：把 stdout 重定向到文件时，进度还在屏幕上。
func progressStart(index, total int, name string) {
	fmt.Fprintf(os.Stderr, "[%d/%d] %s …", index, total, name)
}

// progressDone 补上这一行的结果与耗时。
func progressDone(text string, elapsed time.Duration) {
	fmt.Fprintf(os.Stderr, " %s（%s）\n", text, elapsedText(elapsed))
}

// progressText 拼进度行上的结果说明。
//
// 正常同步只说结果，摘要留给最后的表格——表格里已经有了，重复一遍没意义。
// 出问题或演练时才带详情：出事要当场看见，演练的结果也不能只说「无变化」。
func progressText(result syncResult, dryRun bool) string {
	switch {
	case dryRun:
		return "演练：" + oneLine(result.Detail)
	case result.Outcome == Failed || result.Outcome == Conflicted ||
		result.Outcome == PendingDelete:
		return string(result.Outcome) + "：" + oneLine(result.Detail)
	default:
		return string(result.Outcome)
	}
}

// elapsedText 把耗时写成人看得懂的短串。
func elapsedText(d time.Duration) string {
	if d < time.Second {
		return d.Round(time.Millisecond).String()
	}
	return d.Round(100 * time.Millisecond).String()
}

// queueKey 拼队列/基线的主键。用分隔符避免名字里带分隔符时撞车。
func queueKey(name, agentName string) string {
	return name + "/" + agentName
}

// filterByBackoff 按退避时间安排任务：
// 还在退避期内的先不碰，避免网络不通时每次运行都硬撞一遍。
func filterByBackoff(targets []SyncTarget, queue syncqueue.Queue) []SyncTarget {
	pending := map[string]syncqueue.Entry{}
	for _, entry := range queue.Pending() {
		pending[queueKey(entry.Name, entry.Agent)] = entry
	}
	now := time.Now()
	kept := make([]SyncTarget, 0, len(targets))
	for _, target := range targets {
		entry, queued := pending[queueKey(target.Name, target.Agent)]
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

// SyncTarget 是一个待对账的 Skill。
type SyncTarget struct {
	Name     string
	Agent    string
	Path     string
	BaseHash string
	SkillID  string
	// CloudHash 是本次看到的云端已发布版本内容摘要。
	// 决策用它，上传也用它：两者不是同一个值的话，服务端的基线校验形同虚设。
	CloudHash string
	// BaseFiles / BaseSnapshot 是上次同步时的逐文件摘要与内容副本。
	// 这次同步没达成新共识时要原样带回去：基线只能前进，不能被写成本地当前内容。
	BaseFiles    map[string]string
	BaseSnapshot string
}

// SyncTargets 列出所有要处理的 Skill，并带上各自的基线。
func SyncTargets(options SyncOptions) ([]SyncTarget, error) {
	state, err := syncstate.Load(Cfg.ConfigDir)
	if err != nil {
		return nil, err
	}
	var targets []SyncTarget
	for _, agentName := range syncAgentNames(options.Agents) {
		dir, err := agent.Dir(Cfg, agentName, "user", "")
		if err != nil {
			return nil, err
		}
		agentCfg := Cfg.Agents[agentName]
		recursive := agent.RecursiveScan(agentCfg)
		targets = append(targets, targetsIn(state, dir, agentName, options, recursive)...)
		// 这个 Agent 自己配的额外扫描根（agents.<name>.extra_scan_dirs）。
		// 它们挂在这个 Agent 名下，基线也按这个 Agent 记。
		for _, extra := range agentCfg.ExtraScanDirs {
			if extra != "" {
				targets = append(targets, targetsIn(state, extra, agentName, options, recursive)...)
			}
		}
	}
	// 额外配置的目录：项目级、自定义目录（设计文档 §6.1）。
	// 它们不属于任何 Agent，用 custom 当标识——同一个 Skill 在
	// Agent 目录与自定义目录里各有一份时，两份各自记基线。
	for _, dir := range Cfg.Sync.ExtraDirs {
		targets = append(targets, targetsIn(state, dir, "custom", options, true)...)
	}
	return dedupeTargets(targets), nil
}

// dedupeTargets 按「名字 + Agent」去重，先出现的优先。
//
// 同一个 Skill 可能同时出现在 Agent 目录和它链接指向的库里
// （magpie 这类管理器就是这么摆的）。两处都记一份基线会同名互相覆盖，
// 上传也会做两次；Agent 原生目录排在前面，所以优先留它。
func dedupeTargets(targets []SyncTarget) []SyncTarget {
	seen := make(map[string]bool, len(targets))
	unique := make([]SyncTarget, 0, len(targets))
	for _, target := range targets {
		key := target.Name + "/" + target.Agent
		if seen[key] {
			continue
		}
		seen[key] = true
		unique = append(unique, target)
	}
	return unique
}

// targetsIn 列出一个目录里的 Skill，并带上各自的基线。
func targetsIn(state syncstate.State, dir, agentName string, options SyncOptions,
	recursive bool) []SyncTarget {
	var targets []SyncTarget
	for _, path := range localSkills(dir, recursive) {
		name := skillscan.SkillName(path)
		if options.Only != "" && name != options.Only {
			continue
		}
		// 第一次看到就先记一笔，但不等于收编——收编由同步本身决定
		entry, known := state.Find(name, agentName)
		if !known {
			entry = state.Discover(name, agentName)
		}
		if options.OnlyExisting && state.New(name, agentName) {
			continue
		}
		targets = append(targets, SyncTarget{
			Name: name, Agent: agentName, Path: path,
			BaseHash: entry.BaseHash, SkillID: entry.SkillID,
			BaseFiles: entry.BaseFiles, BaseSnapshot: entry.BaseSnapshot,
		})
	}
	return targets
}

func syncAgentNames(selected []string) []string {
	if len(selected) > 0 {
		return selected
	}
	var names []string
	for _, name := range agent.Names(Cfg) {
		if agentCfg, ok := Cfg.Agents[name]; ok && agentCfg.Enabled {
			names = append(names, name)
		}
	}
	return names
}

// localSkills 列出目录下的 Skill 候选（含目录本身）。
//
// recursive 为 false 时只看直接子目录——见 skillscan.Candidates。
func localSkills(dir string, recursive bool) []string {
	if _, err := os.Stat(filepath.Join(dir, "SKILL.md")); err == nil {
		return []string{dir}
	}
	candidates, err := skillscan.Candidates(dir, recursive)
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
func syncOne(ctx context.Context, client *apiclient.Client, target SyncTarget,
	options SyncOptions) (syncResult, syncstate.Entry, error) {

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
	localHash := ManifestDigest(local.Manifest)
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
		// 云端给的 ID 才是真的：本机记的可能指向旧库（服务端的库重建过，
		// 旧的 skill_id 就是条死链），拿它去报变更集只会换来一句 404。
		target.SkillID = skill.ID
		cloudHash, err = cloudDigest(ctx, client, skill.ID, local.SHA256)
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
	}
	// 记下这次看到的云端摘要：决策用它，上传也用它。
	// 上传带的基线必须是决策时依据的那个值，否则服务端的基线校验形同虚设。
	target.CloudHash = cloudHash

	decision := syncstate.Decide(target.BaseHash, localHash, cloudHash)
	changeset := ""
	if found && decision != syncstate.UpToDate {
		// 云端也判一次：别的设备可能在两次请求之间动过内容（详细设计 §3.6）。
		var err error
		changeset, err = confirmWithCloud(ctx, client, target, local, localHash, decision, options.DryRun)
		if err != nil {
			// 只有服务端明确判了冲突才算冲突。服务端自己出错（500/404/403）
			// 是失败：失败才进重试队列，报成冲突会让用户去 --resolve 白折腾。
			if !errors.Is(err, ErrConflict) {
				return OutcomeForError(target, err), syncstate.Entry{}, nil
			}
			// 服务端说冲突：用户没给处理方式就报冲突；给了就按他的来。
			// 原来这里直接返回冲突，用户就算加了 --resolve 也走不到处理分支——
			// 冲突永远解不开（服务端那一次校验挡住了后面的路）。
			if options.Resolve == "" {
				return OutcomeForError(target, err), syncstate.Entry{}, nil
			}
			decision = syncstate.Conflict
		}
	}
	result, entry, err := ApplyDecision(ctx, client, target, decision, local, localHash,
		skill, found, options)
	if result.SkillID == "" {
		// 云端给的 ID 才是真的：拿它去挂审计流水，别拿本机记的旧值。
		result.SkillID = target.SkillID
	}
	if changeset != "" && result.Outcome == Uploaded {
		result.Detail += "（云端记录 " + short(changeset) + "）"
	}
	return result, entry, err
}

// confirmWithCloud 把这次的基线和本地摘要报给服务端，由它回答还成不成立。
// 服务端说冲突就以它为准——它看得到别的设备，服务端之间才比得准。
func confirmWithCloud(ctx context.Context, client *apiclient.Client, target SyncTarget,
	local packager.Artifact, localHash string, decision syncstate.Decision,
	dryRun bool) (string, error) {

	if dryRun {
		// --dry-run 的承诺是「什么都不改」：不注册设备、不提交变更集。
		return "", nil
	}
	device, err := client.DeviceID(ctx)
	if err != nil {
		return "", err
	}
	direction := "bidirectional"
	if decision == syncstate.Upload {
		direction = "upload"
	} else if decision == syncstate.Download {
		direction = "download"
	}
	// 幂等键用「skill + 内容摘要 + 基线」：同样的内容重复提交只会有一条记录。
	response, err := client.SubmitChangeset(ctx, device,
		IdempotencyKey(target.SkillID, localHash, target.BaseHash),
		apiclient.SyncChangesetRequest{
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
		return "", fmt.Errorf("%w（%s），不自动覆盖", ErrConflict, response.Message)
	}
	return response.ChangesetID, nil
}

// ErrConflict 标记「服务端明确判了冲突」。
// 服务端出错与「服务端说冲突」是两回事：前者要重试，后者要人来定夺。
var ErrConflict = errors.New("云端已判冲突")

// IdempotencyKey 拼这次提交的幂等键。
//
// 键里必须带 skill id：同一台设备上两个 Skill 的内容摘要与基线可能一模一样
// （同一份模板装到两个目录），不带 id 服务端会把第二个当重复提交吞掉，
// 第二个 Skill 的变更集就这么没了。
func IdempotencyKey(skillID, localHash, baseHash string) string {
	return skillID + "-" + localHash[:16] + "-" + short(baseHash)
}

// OutcomeForError 把提交变更集时的错误归成同步结果。
func OutcomeForError(target SyncTarget, err error) syncResult {
	if errors.Is(err, ErrConflict) {
		return syncResult{Name: target.Name, Path: target.Path, Outcome: Conflicted,
			Detail: err.Error()}
	}
	return failedResult(target, err.Error())
}

// ensureDevice 拿本机在注册中心的设备 ID，没有就注册一个。
// ApplyDecision 按判定结果执行。冲突在没有 --resolve 时只报告不动手。
func ApplyDecision(ctx context.Context, client *apiclient.Client, target SyncTarget,
	decision syncstate.Decision, local packager.Artifact, localHash string,
	skill apiclient.Skill, found bool, options SyncOptions) (syncResult, syncstate.Entry, error) {

	// 基线先按上次的状态带过来。这次同步没达成新共识时（方向过滤、冲突挡住），
	// 基线必须原样保留——写成本地当前内容的话，下一轮会把「本地领先」
	// 误判成「本地落后」，把用户本地的改动下载掉。
	entry := syncstate.Entry{
		Name: target.Name, Agent: target.Agent,
		BaseHash: target.BaseHash, SkillID: target.SkillID,
		BaseFiles: target.BaseFiles, BaseSnapshot: target.BaseSnapshot,
		Path: target.Path,
	}

	switch decision {
	case syncstate.UpToDate:
		if found {
			entry.SkillID = skill.ID
		}
		// 两边一致：以当前内容作为新的共同基线。
		// 不重算的话，一次「无变化」的同步就把逐文件摘要和内容副本清空，
		// 下一次真冲突时就合不了了（设计文档 §6.3 的按文件合并）。
		saveBaseline(target, &entry)
		return syncResult{Name: target.Name, Path: target.Path,
			Outcome: Skipped, Detail: localHash[:12]}, entry, nil

	case syncstate.Download:
		if options.Direction == "upload" {
			// 本次不拉取：云端领先这个事实没变，基线也不动。
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Skipped,
				Detail: "云端有更新，本次只上传；要拉取用 --direction download"}, entry, nil
		}
		if options.DryRun {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Skipped,
				Detail: "将拉取云端版本"}, entry, nil
		}
		version, err := DownloadInto(ctx, client, target, skill)
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		// 装完本地就是云端那份了：基线按新内容重算。
		// 不重算的话，基线还是下载前那份摘要，下一轮就误报冲突，
		// 逐文件合并也永远合不了（没有基线可比）。
		saveBaseline(target, &entry)
		return syncResult{Name: target.Name, Path: target.Path, Outcome: fetched,
			Detail: version}, entry, nil

	case syncstate.Conflict:
		return resolveConflict(ctx, client, target, local, localHash, skill, options)

	default: // Upload
		if options.Direction == "download" {
			// 本次不上传：本地领先这个事实没变，基线也不动。
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Skipped,
				Detail: "本地有更新，本次只下载"}, entry, nil
		}
		if !options.CloudDeletionsKnown {
			// 拿不到云端的已删除清单就不能上传：云端查不到的 Skill 会被判成
			// 「该上传」，上传就在云端新建一个同名的——把人家删掉的 Skill 复活了。
			// 宁可这次不传，等清单拿到了再说。
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Failed,
				Detail: "拿不到云端已删除清单，为免把云端删掉的 Skill 复活，本次不上传"}, entry, nil
		}
		if options.DryRun {
			detail := "将上传新版本（演练，未真的上传）"
			if deleted := localDeletes(target.BaseFiles, local); len(deleted) > 0 {
				// 演练也得说真话：不给 --allow-delete 的话这次根本不会上传。
				detail = fmt.Sprintf("本地删掉了 %d 个文件（%s），"+
					"不给 --allow-delete 就不会上传", len(deleted), describeDeletes(deleted))
			}
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Skipped,
				Detail: detail}, entry, nil
		}
		if reason := deleteBlocked(target, local, options); reason != "" {
			return syncResult{Name: target.Name, Path: target.Path, Outcome: PendingDelete,
				Detail: reason}, entry, nil
		}
		result, err := PublishOne(ctx, client, PublishTarget{Dir: target.Path,
			RelPath: filepath.Base(target.Path)},
			PublishOptions{Visibility: syncVisibility,
				ExpectedBaseRevision: baselineOf(target.CloudHash)})
		if err != nil {
			// 服务端说基线对不上（409）：云端在这期间被别人动过。
			// 这不是普通失败，是冲突——要人来重新对账，不能进重试队列。
			if apiclient.CodeOf(err) == "VERSION_CONFLICT" {
				return OutcomeForError(target,
					fmt.Errorf("%w：%s", ErrConflict, err.Error())), syncstate.Entry{}, nil
			}
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		detail := result.Version
		if !found {
			detail = "云端新建 " + result.Version
		}
		entry.SkillID = result.SkillID
		entry.LastVersion = result.Version
		entry.BaseHash = localHash
		saveBaseline(target, &entry)
		return syncResult{Name: target.Name, Path: target.Path,
			Outcome: Uploaded, Detail: detail}, entry, nil
	}
}

// deleteBlocked 检查这次发布要不要先确认本地的删除。
//
// 本机删掉的文件不能静默跟着新版本传到云端：整个目录重新打包上传，
// 云端那份就跟着消失了。目录扫不到不该等于云端被删（设计文档 §6.3）。
// 只在「云端没变」时用：那时基线里的文件云端都还在，本地删了就是真删。
// 返回非空串表示拦下来了，理由就写在这个串里。
func deleteBlocked(target SyncTarget, local packager.Artifact, options SyncOptions) string {
	if options.AllowDelete {
		return ""
	}
	deleted := localDeletes(target.BaseFiles, local)
	if len(deleted) == 0 {
		return ""
	}
	return fmt.Sprintf("本地删掉了 %d 个文件（%s），确认要传播到云端就加 --allow-delete",
		len(deleted), describeDeletes(deleted))
}

// localDeletes 找出「基线里有、本地已经删掉」的文件。
//
// 基线是上次同步时双方都认可的那份文件清单；本地包里没有它，就是本机删掉了。
func localDeletes(base map[string]string, local packager.Artifact) []string {
	present := make(map[string]bool, len(local.Manifest))
	for _, item := range local.Manifest {
		present[item.Path] = true
	}
	var deleted []string
	for path := range base {
		if !present[path] {
			deleted = append(deleted, path)
		}
	}
	sort.Strings(deleted)
	return deleted
}

// describeDeletes 说清哪些文件在本地被删掉了。太多时只列前几个。
func describeDeletes(deleted []string) string {
	shown := deleted
	if len(shown) > 5 {
		shown = shown[:5]
	}
	text := strings.Join(shown, "、")
	if len(deleted) > len(shown) {
		text += fmt.Sprintf(" 等 %d 个", len(deleted))
	}
	return text
}

// resolveConflict 处理两边都改过的情况。
// 没有 --resolve 就只报告——自动覆盖等于替用户做决定（设计文档 §6.3）。
func resolveConflict(ctx context.Context, client *apiclient.Client, target SyncTarget,
	local packager.Artifact, localHash string, skill apiclient.Skill,
	options SyncOptions) (syncResult, syncstate.Entry, error) {

	entry := syncstate.Entry{Name: target.Name, Agent: target.Agent,
		BaseHash: target.BaseHash, SkillID: target.SkillID,
		BaseFiles: target.BaseFiles, BaseSnapshot: target.BaseSnapshot}
	hint := fmt.Sprintf("本地 %s vs 云端 %s，用 --resolve keep-local/keep-cloud/keep-both 处理",
		localHash[:8], "已变")

	switch options.Resolve {
	case "keep-local":
		result, err := PublishOne(ctx, client, PublishTarget{Dir: target.Path,
			RelPath: filepath.Base(target.Path)},
			PublishOptions{Visibility: syncVisibility})
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		entry.SkillID = result.SkillID
		entry.LastVersion = result.Version
		// 本地这份已经上传了：它就是新的共同基线。
		saveBaseline(target, &entry)
		return syncResult{Name: target.Name, Path: target.Path, Outcome: Uploaded,
			Detail: "保留本地，已上传 " + result.Version}, entry, nil

	case "keep-cloud":
		if options.Direction == "upload" {
			// 本次只上传：拉云端跟方向反着来，用户说了要保留云端就别硬传。
			return syncResult{Name: target.Name, Path: target.Path, Outcome: Skipped,
				Detail: "本次只上传，不能保留云端；要按云端来请用 --direction both"}, entry, nil
		}
		version, err := DownloadInto(ctx, client, target, skill)
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		// 装完本地就是云端那份了：基线按新内容重算，
		// 不能留着「被换掉之前」的本地摘要——那会让下一轮误判成云端变了。
		saveBaseline(target, &entry)
		return syncResult{Name: target.Name, Path: target.Path, Outcome: fetched,
			Detail: "保留云端，已拉取 " + version}, entry, nil

	case "keep-both":
		// 备份放在隐藏目录：扫描会跳过隐藏目录，它不会变成下一个「待同步的 Skill」。
		backup := filepath.Join(filepath.Dir(target.Path),
			"."+target.Name+".conflict-"+localHash[:8])
		if err := installer.InstallAtomic(target.Path, backup, true); err != nil {
			return failedResult(target, "备份本地版本失败: "+err.Error()), syncstate.Entry{}, nil
		}
		result, err := PublishOne(ctx, client, PublishTarget{Dir: target.Path,
			RelPath: filepath.Base(target.Path)},
			PublishOptions{Visibility: syncVisibility})
		if err != nil {
			return failedResult(target, err.Error()), syncstate.Entry{}, nil
		}
		entry.SkillID = result.SkillID
		entry.LastVersion = result.Version
		saveBaseline(target, &entry)
		return syncResult{Name: target.Name, Path: target.Path, Outcome: Uploaded,
			Detail: fmt.Sprintf("本地版本备份到 %s，云端上传 %s", backup, result.Version)}, entry, nil

	default:
		// 先按文件合一次（设计文档 §6.3）：两边改的不是同一处就合掉，
		// 真的合不了才落到「冲突副本，等人来定」。
		base, _ := syncstate.Load(Cfg.ConfigDir)
		prior, _ := base.Find(target.Name, target.Agent)
		ok, note, mergeErr := MergeConflict(ctx, client, target, prior, local, skill)
		outcome := Conflicted
		switch {
		case mergeErr != nil:
			// 合并失败得说实话：报成「冲突」的话，用户只会去 --resolve 白折腾。
			hint = "按文件合并失败：" + mergeErr.Error()
			outcome = Failed
		case ok:
			if result, err := PublishOne(ctx, client, PublishTarget{Dir: target.Path,
				RelPath: filepath.Base(target.Path)},
				PublishOptions{Visibility: syncVisibility}); err == nil {
				entry.SkillID = result.SkillID
				entry.LastVersion = result.Version
				// 合并改过本地文件：基线按合并后的内容重算，
				// 不能记合并前那份摘要。
				saveBaseline(target, &entry)
				return syncResult{Name: target.Name, Path: target.Path, Outcome: Uploaded,
					Detail: note + "，已上传 " + result.Version}, entry, nil
			}
		case note != "":
			hint = note + "；用 --resolve keep-local/keep-cloud/keep-both 强行决定"
		}
		// 让服务端也知道这次冲突了：另一台设备可能正等着知道这件事。
		if !options.DryRun {
			_ = client.ReportAuditEvent(ctx, "sync.conflict_reported", target.SkillID, "denied",
				map[string]string{"skill": target.Name, "local_hash": localHash[:12]})
		}
		return syncResult{Name: target.Name, Path: target.Path,
			Outcome: outcome, Detail: hint}, syncstate.Entry{}, nil
	}
}

// DownloadInto 把云端最新已发布版本装回本机目录。
func DownloadInto(ctx context.Context, client *apiclient.Client, target SyncTarget,
	skill apiclient.Skill) (string, error) {

	versions, err := client.Versions(ctx, skill.ID)
	if err != nil {
		return "", err
	}
	selected, err := PickVersion(versions, "", false, lockfileEntry(target), false)
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
	// 装之前先验签并核对摘要：下载回来的字节未必是云端那一份。
	// install 那条路有这道闸，同步这条不能没有——不然换个源就能把别的东西装进 Agent 目录。
	verdict, err := VerifyArtifact(ctx, client, selected, skillDir)
	if err != nil {
		return "", err
	}
	fmt.Printf("%s 验签：%s\n", selected.Version, verdict)
	// 本地是链接时写它指向的真实目录，链接保留（与 install 同一口径）。
	writePath := skillscan.WriteTarget(target.Path)
	if err := installer.InstallAtomic(skillDir, writePath, true); err != nil {
		return "", err
	}
	if err := installer.MarkInstalled(writePath, "skillmesh:"+skill.ID); err != nil {
		return "", err
	}
	// 同步下来的 Skill 也要登记安装实例：不然 installations 里看不到任何同步来的 Skill。
	reportInstallation(ctx, client, installRequest{
		SkillID: skill.ID,
		Version: selected,
		Agents:  []string{target.Agent},
		Scope:   "user",
	}, target.Agent, target.Path)
	return selected.Version, nil
}

func lockfileEntry(target SyncTarget) lockfile.LockedSkill {
	return lockfile.LockedSkill{Name: target.Name, SourceID: target.SkillID}
}

// saveBaseline 记下这次同步出去的逐文件摘要和内容副本。
// 下次两边都改时，三方合并要靠它当基准（设计文档 §6.3）。
//
// 逐文件摘要与副本从磁盘现算——它们描述的是「双方认可的那份内容」，
// 不是某个人以为的内容。读不出清单就什么也不改：基线宁可旧着，
// 下次合并会老实说「合不了」，总比拿一份对不上的基线去合强。
func saveBaseline(target SyncTarget, entry *syncstate.Entry) {
	files, err := skillscan.DirHashes(target.Path)
	if err != nil {
		return
	}
	hashes := ToHashMap(files)
	entry.BaseFiles = hashes
	entry.BaseHash = localHashOf(hashes)

	snapshot := filepath.Join(Cfg.ConfigDir, "sync-base",
		filepath.Join(target.Agent, target.Name))
	if err := os.RemoveAll(snapshot); err != nil {
		return
	}
	// 副本只留一份，目录换掉就行；失败就不给基线，下次合并会老实说「合不了」
	_ = installer.CopyTree(target.Path, snapshot)
	if _, err := os.Stat(snapshot); err == nil {
		entry.BaseSnapshot = snapshot
	}
}

// 基线快照与安装用的是同一套复制规则（installer.CopyTree）：
// 快照里有的文件，才是要往云端对账的文件。

func localHashOf(files map[string]string) string {
	list := make([]skillscan.FileDigest, 0, len(files))
	for path, sum := range files {
		list = append(list, skillscan.FileDigest{Path: path, SHA256: sum})
	}
	return skillscan.Digest(list)
}

func failedResult(target SyncTarget, why string) syncResult {
	return syncResult{Name: target.Name, Path: target.Path, Outcome: Failed,
		Detail: why, SkillID: target.SkillID}
}

func ManifestDigest(entries []packager.Entry) string {
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

// baselineOf 取能当「期望基线」的云端摘要。
//
// 只有内容摘要（64 位十六进制）能当基线：云端没有可比内容时是空串，
// 老版本没有清单时是 "<zip 摘要>-zip" 的占位。那两样都不是
// 「已发布版本的内容摘要」，带上去了服务端永远对不上——那种情况就不带。
func baselineOf(cloudHash string) string {
	if len(cloudHash) != 64 {
		return ""
	}
	for i := 0; i < len(cloudHash); i++ {
		c := cloudHash[i]
		if (c < '0' || c > '9') && (c < 'a' || c > 'f') {
			return ""
		}
	}
	return cloudHash
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
	fmt.Println("下次 sync 会重试这些到点的任务；skillmesh doctor 看队列概况。")
}

func reportSync(results []syncResult, queue syncqueue.Queue) error {
	counts := map[syncOutcome]int{}
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "SKILL\t结果\t说明")
	for _, result := range results {
		counts[result.Outcome]++
		fmt.Fprintf(w, "%s\t%s\t%s\n", result.Name, result.Outcome, result.Detail)
	}
	if err := w.Flush(); err != nil {
		return err
	}
	fmt.Printf("\n无变化 %d，上传 %d，拉取 %d，将拉取 %d，冲突 %d，待确认 %d，已删除 %d，失败 %d\n",
		counts[Skipped], counts[Uploaded], counts[fetched], counts[WouldPull],
		counts[Conflicted], counts[PendingDelete], counts[Deleted], counts[Failed])
	if counts[Conflicted] > 0 {
		return fmt.Errorf("%d 个存在冲突，需要 --resolve 处理", counts[Conflicted])
	}
	if counts[PendingDelete] > 0 {
		return fmt.Errorf("%d 个有本地删除待确认，需要 --allow-delete", counts[PendingDelete])
	}
	if counts[Failed] > 0 {
		return fmt.Errorf("%d 个同步失败", counts[Failed])
	}
	return nil
}
