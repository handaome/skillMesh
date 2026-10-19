package cmd

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// syncVisibility 是同步用的可见性。
// 个人云端备份默认私有，不等于团队发布（设计文档 §2 的概念区分）。
const syncVisibility = "private"

// syncOutcome 是单个 Skill 的同步结果。
type syncOutcome string

const (
	synced   syncOutcome = "已同步"
	skipped  syncOutcome = "无变化"
	uploaded syncOutcome = "新版本"
	failed   syncOutcome = "失败"
)

func newSyncCmd() *cobra.Command {
	var agentNames []string
	var dryRun bool

	cmd := &cobra.Command{
		Use:   "sync",
		Short: "把本地 Agent 目录里的 Skill 同步到个人私有空间",
		Long: "扫描已授权的 Agent 目录，与云端逐个比对制品摘要：\n" +
			"内容没变就跳过，变了才发一个新版本（版本号自动累加）。\n\n" +
			"这是「本地 → 个人云端」那条链路（设计文档 §3.1），不是团队发布。\n" +
			"只同步、从不删除云端内容——目录扫不到不该等于云端被删。",
		Args: cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			dirs, err := syncDirs(agentNames)
			if err != nil {
				return err
			}
			if len(dirs) == 0 {
				return fmt.Errorf("没有可扫描的 Agent 目录，先跑 skillmesh agents detect 看配置")
			}
			client, err := newClient()
			if err != nil {
				return err
			}
			var results []syncResult
			for _, dir := range dirs {
				for _, candidate := range localSkills(dir) {
					outcome, detail, err := syncOne(cmd.Context(), client, candidate, dryRun)
					if err != nil {
						results = append(results, syncResult{
							Name: skillscan.SkillName(candidate), Path: candidate,
							Outcome: failed, Detail: err.Error(),
						})
						continue
					}
					results = append(results, syncResult{
						Name: skillscan.SkillName(candidate), Path: candidate,
						Outcome: outcome, Detail: detail,
					})
				}
			}
			if len(results) == 0 {
				fmt.Println("授权目录里没有找到含 SKILL.md 的目录。")
				return nil
			}
			return reportSync(results)
		},
	}
	cmd.Flags().StringSliceVar(&agentNames, "agent", nil, "只同步指定 Agent 的目录，不填用全部启用的")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只报出会同步什么，不上传")
	return cmd
}

// syncResult 是一条同步结果。
type syncResult struct {
	Name    string
	Path    string
	Outcome syncOutcome
	Detail  string
}

// syncDirs 列出要扫的目录：指定了 --agent 就用它，否则用所有启用的 Agent 的用户目录。
func syncDirs(agentNames []string) ([]string, error) {
	names := agentNames
	if len(names) == 0 {
		for _, name := range agent.Supported {
			if agentCfg, ok := cfg.Agents[name]; ok && agentCfg.Enabled {
				names = append(names, name)
			}
		}
	}
	seen := map[string]bool{}
	var dirs []string
	for _, name := range names {
		dir, err := agent.Dir(cfg, name, "user", "")
		if err != nil {
			return nil, err
		}
		if !seen[dir] {
			seen[dir] = true
			dirs = append(dirs, dir)
		}
	}
	return dirs, nil
}

// localSkills 列出目录下的 Skill 候选（含目录本身），跳过本机的受管标记。
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

// syncOne 同步一个 Skill：内容寻址比对，相同就不发。
func syncOne(ctx context.Context, client *apiclient.Client, dir string,
	dryRun bool) (syncOutcome, string, error) {

	if err := skillscan.ValidateDir(dir, skillscan.DefaultLimits); err != nil {
		return failed, "", err
	}
	findings, err := skillscan.ScanSecrets(dir)
	if err != nil {
		return failed, "", err
	}
	if len(findings) > 0 {
		return failed, "", errors.New(skillscan.FormatFindings(findings))
	}
	local, err := packager.Pack(dir)
	if err != nil {
		return failed, "", err
	}
	slug, err := skillscan.SlugFromDirName(filepath.Base(dir))
	if err != nil {
		return failed, "", err
	}

	skill, found, err := client.FindSkillBySlug(ctx, slug)
	if err != nil {
		return failed, "", err
	}
	if found {
		digest := localDigest(local)
		same, err := cloudHasContent(ctx, client, skill.ID, local)
		if err != nil {
			return failed, "", err
		}
		if same {
			return skipped, digest[:12], nil
		}
	}

	result, err := publishOne(ctx, client, publishTarget{Dir: dir, RelPath: filepath.Base(dir)},
		publishOptions{Visibility: syncVisibility, DryRun: dryRun})
	if err != nil {
		return failed, "", err
	}
	// 结果看有没有在云端见过，别看 publishOne 的动作——
	// dry-run 时它一律返回「将发布」，会把「已存在」误报成「新建」。
	if !found {
		detail := "云端还没有这个 Skill"
		if dryRun {
			detail += "（演练）"
		}
		return synced, detail, nil
	}
	return uploaded, result.Version, nil
}

// cloudHasContent 判断云端已发布版本里有没有一份和本地内容一样的。
// 内容寻址意味着同一份内容只会存一份，所以命中即可判定无需重传。
func cloudHasContent(ctx context.Context, client *apiclient.Client, skillID string,
	local packager.Artifact) (bool, error) {

	versions, err := client.Versions(ctx, skillID)
	if err != nil {
		return false, err
	}
	digest := localDigest(local)
	for _, item := range versions.Items {
		if item.Status != "published" {
			continue
		}
		if len(item.Manifest) > 0 {
			if remoteDigest(item) == digest {
				return true, nil
			}
			continue
		}
		// 老服务端不返回 manifest，只能退而比 zip 摘要。
		if item.Artifact != nil && item.Artifact.SHA256 == local.SHA256 {
			return true, nil
		}
	}
	return false, nil
}

// fileDigest 是参与规范化摘要的一项。
type fileDigest struct {
	Path   string
	SHA256 string
}

// digestOf 对「路径 + 逐文件 SHA-256」排序后哈希，得到内容的规范化摘要
// （设计文档 §7.1 的 installed_hash、§6.2 的「计算相对路径与 SHA-256」）。
//
// 不用整包 zip 的 sha256：同一份内容重新打包，压缩实现或文件权限位一变
// 摘要就变了，会被误判成内容变更。
func digestOf(files []fileDigest) string {
	sort.Slice(files, func(i, j int) bool { return files[i].Path < files[j].Path })
	hash := sha256.New()
	for _, file := range files {
		fmt.Fprintf(hash, "%s\x00%s\n", file.Path, file.SHA256)
	}
	return hex.EncodeToString(hash.Sum(nil))
}

func localDigest(artifact packager.Artifact) string {
	files := make([]fileDigest, 0, len(artifact.Manifest))
	for _, entry := range artifact.Manifest {
		files = append(files, fileDigest{Path: entry.Path, SHA256: entry.SHA256})
	}
	return digestOf(files)
}

func remoteDigest(version apiclient.SkillVersion) string {
	files := make([]fileDigest, 0, len(version.Manifest))
	for _, entry := range version.Manifest {
		files = append(files, fileDigest{Path: entry.Path, SHA256: entry.SHA256})
	}
	return digestOf(files)
}

func reportSync(results []syncResult) error {
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
	fmt.Printf("\n无变化 %d，已同步/新版本 %d，失败 %d\n",
		counts[skipped], counts[synced]+counts[uploaded], counts[failed])
	if counts[failed] > 0 {
		return fmt.Errorf("%d 个同步失败", counts[failed])
	}
	return nil
}
