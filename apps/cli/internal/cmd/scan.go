package cmd

import (
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/syncstate"
	"github.com/spf13/cobra"
)

// scanCategory 是扫描结果的分类（详细设计 §3.6）。
type scanCategory string

const (
	catManaged   scanCategory = "已管理"
	catNew       scanCategory = "新发现"
	catChanged   scanCategory = "内容变化"
	catConflict  scanCategory = "路径冲突"
	catSensitive scanCategory = "疑似敏感"
	catRisky     scanCategory = "可疑内容"
	catUnmanaged scanCategory = "未受管"
)

// scanEntry 是扫描出来的一条结果。
type scanEntry struct {
	Name     string       `json:"name"`
	Agent    string       `json:"agent"`
	Path     string       `json:"path"`
	Category scanCategory `json:"category"`
	Detail   string       `json:"detail,omitempty"`
}

func newScanCmd() *cobra.Command {
	var (
		agentNames       []string
		path             string
		allAgents        bool
		asJSON           bool
		includeUnmanaged bool
	)

	cmd := &cobra.Command{
		Use:   "scan [目录]",
		Short: "扫描已授权目录里的 Skill，分类列出发现的问题",
		Long: "不碰网络，只看本机：哪些是受管的、哪些是新的、哪些内容变了、\n" +
			"哪些名字撞车、哪些文件疑似带凭据。\n\n" +
			"扫不到的目录会报出来，不会当成空目录——一次权限异常不该被解读成「这些 Skill 都没了」。",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if allAgents && len(agentNames) > 0 {
				return fmt.Errorf("--all-agents 与 --agent 不能同时用")
			}
			dirs, err := scanDirs(args, path, agentNames, allAgents)
			if err != nil {
				return err
			}
			state, err := syncstate.Load(cfg.ConfigDir)
			if err != nil {
				return err
			}
			entries := scanAll(dirs, state, includeUnmanaged)
			if asJSON {
				encoder := json.NewEncoder(os.Stdout)
				encoder.SetIndent("", "  ")
				return encoder.Encode(entries)
			}
			return reportScan(entries)
		},
	}
	cmd.Flags().StringSliceVar(&agentNames, "agent", nil, "只扫指定 Agent 的目录")
	cmd.Flags().StringVar(&path, "path", "", "直接指定要扫的目录")
	cmd.Flags().BoolVar(&allAgents, "all-agents", false, "扫所有已启用的 Agent")
	cmd.Flags().BoolVar(&asJSON, "json", false, "输出 JSON")
	cmd.Flags().BoolVar(&includeUnmanaged, "include-unmanaged", false,
		"连不受 SkillMesh 管理的目录一起列出")
	return cmd
}

// scanTarget 是一个待扫的目录。
type scanTarget struct {
	Agent string
	Dir   string
}

// scanDirs 决定扫哪些目录：显式路径优先，其次 --agent / --all-agents，最后用配置里启用的 Agent。
func scanDirs(args []string, path string, agentNames []string, allAgents bool) ([]scanTarget, error) {
	if len(args) == 1 {
		return []scanTarget{{Dir: args[0]}}, nil
	}
	if path != "" {
		return []scanTarget{{Dir: path}}, nil
	}
	names := agentNames
	if allAgents || len(names) == 0 {
		names = nil
		for _, name := range agent.Supported {
			if agentCfg, ok := cfg.Agents[name]; ok && agentCfg.Enabled {
				names = append(names, name)
			}
		}
	}
	var targets []scanTarget
	for _, name := range names {
		dir, err := agent.Dir(cfg, name, "user", "")
		if err != nil {
			return nil, err
		}
		targets = append(targets, scanTarget{Agent: name, Dir: dir})
	}
	return targets, nil
}

func scanAll(targets []scanTarget, state syncstate.State, includeUnmanaged bool) []scanEntry {
	var entries []scanEntry
	seen := map[string]string{} // 安装名 -> 来源路径，撞车就报出来

	for _, target := range targets {
		// 读不到就明说读不到。当成空目录会让 sync 以为东西都没了（详细设计 §3.6）。
		if _, err := os.Stat(target.Dir); err != nil {
			entries = append(entries, scanEntry{
				Name: "-", Agent: target.Agent, Path: target.Dir,
				Category: catNew, Detail: "目录读不到，已跳过：" + err.Error(),
			})
			continue
		}
		candidates, err := skillscan.Candidates(target.Dir)
		if err != nil {
			entries = append(entries, scanEntry{
				Name: "-", Agent: target.Agent, Path: target.Dir,
				Category: catNew, Detail: "扫描失败，已跳过：" + err.Error(),
			})
			continue
		}
		for _, candidate := range candidates {
			name := skillscan.SkillName(candidate.Dir)
			if previous, duplicate := seen[name]; duplicate {
				entries = append(entries, scanEntry{
					Name: name, Agent: target.Agent, Path: candidate.Dir,
					Category: catConflict, Detail: "与 " + previous + " 撞名",
				})
				continue
			}
			seen[name] = candidate.Dir
			entries = append(entries, classifyOne(target, candidate, state, includeUnmanaged))
		}
	}
	return entries
}

// classifyOne 给单个 Skill 归类。最严重的那条优先——敏感 > 变化 > 未受管 > 已管理。
func classifyOne(target scanTarget, candidate skillscan.Candidate, state syncstate.State,
	includeUnmanaged bool) scanEntry {

	entry := scanEntry{Name: candidate.Name, Agent: target.Agent, Path: candidate.Dir,
		Category: catManaged}

	if findings, err := skillscan.ScanSecrets(candidate.Dir); err == nil && len(findings) > 0 {
		entry.Category = catSensitive
		entry.Detail = fmt.Sprintf("%d 处，第一处 %s:%d %s", len(findings),
			findings[0].Path, findings[0].Line, findings[0].Rule)
		return entry
	}
	if risks, err := skillscan.ScanRisks(candidate.Dir); err == nil && len(risks) > 0 {
		entry.Category = catRisky
		entry.Detail = fmt.Sprintf("%d 处，第一处 %s:%d %s", len(risks),
			risks[0].Path, risks[0].Line, risks[0].Rule)
		return entry
	}
	digest, err := skillscan.DirDigest(candidate.Dir)
	if err != nil {
		entry.Category = catNew
		entry.Detail = "算不出内容摘要：" + err.Error()
		return entry
	}
	if base, ok := state.Find(candidate.Name, target.Agent); ok {
		if base.BaseHash != digest {
			entry.Category = catChanged
			entry.Detail = fmt.Sprintf("%s -> %s", short(base.BaseHash), short(digest))
			return entry
		}
		entry.Detail = short(digest)
		return entry
	}
	if installer.IsManaged(candidate.Dir) {
		entry.Detail = short(digest) + "（装过但没有同步基线）"
		return entry
	}
	entry.Category = catUnmanaged
	if !includeUnmanaged {
		entry.Detail = "不受 SkillMesh 管理，加 --include-unmanaged 才列出来"
		return entry
	}
	entry.Detail = short(digest) + "，SkillMesh 不接管它"
	return entry
}

// oneLine 把多行说明压成一行，表格才不会被撑开。
func oneLine(text string) string {
	return strings.ReplaceAll(strings.TrimSpace(text), "\n", " ")
}

func reportScan(entries []scanEntry) error {
	if len(entries) == 0 {
		fmt.Println("没有发现 Skill。")
		return nil
	}
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "SKILL\tAGENT\t分类\t说明")
	for _, entry := range entries {
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", entry.Name, orDash(entry.Agent),
			entry.Category, oneLine(entry.Detail))
	}
	if err := w.Flush(); err != nil {
		return err
	}
	counts := map[scanCategory]int{}
	for _, entry := range entries {
		counts[entry.Category]++
	}
	fmt.Printf("\n已管理 %d，新发现/待处理 %d，内容变化 %d，疑似敏感 %d，路径冲突 %d，未受管 %d\n",
		counts[catManaged], counts[catNew], counts[catChanged], counts[catSensitive],
		counts[catConflict], counts[catUnmanaged])
	if counts[catSensitive] > 0 {
		fmt.Println("疑似敏感的那几条在 skillmesh sync / publish 时会被直接拦下。")
	}
	return nil
}
