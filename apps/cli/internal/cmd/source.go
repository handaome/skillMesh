package cmd

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

func newSourceCmd() *cobra.Command {
	var ref, path string
	source := &cobra.Command{
		Use:   "source",
		Short: "来源解析",
	}
	resolve := &cobra.Command{
		Use:   "resolve <repo-url>",
		Short: "解析仓库并列出候选 Skill 目录",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			repo, err := gitx.NormalizeURL(args[0])
			if err != nil {
				return err
			}
			work, err := os.MkdirTemp("", "skillmesh-resolve-*")
			if err != nil {
				return err
			}
			defer os.RemoveAll(work)

			root := filepath.Join(work, "repo")
			commit, err := gitx.Clone(cmd.Context(), repo, ref, root)
			if err != nil {
				return err
			}
			// 指定了子目录就只看那一个：整个仓库扫一遍再筛，
			// 会把别的目录也报出来——用户要的就是这一个。
			candidates, err := resolveCandidates(root, path)
			if err != nil {
				return err
			}
			fmt.Printf("仓库 %s\ncommit %s\n\n", repo, commit)
			if len(candidates) == 0 {
				fmt.Println("仓库中没有含 SKILL.md 的目录")
				return nil
			}
			w := newTable(os.Stdout)
			fmt.Fprintln(w, "子目录\tSKILL.md")
			for _, candidate := range candidates {
				rel, relErr := filepath.Rel(root, candidate.Dir)
				if relErr != nil {
					rel = candidate.Dir
				}
				fmt.Fprintf(w, "%s\t有\n", filepath.ToSlash(rel))
			}
			return w.Flush()
		},
	}
	resolve.Flags().StringVar(&ref, "ref", "", "分支、标签或 commit")
	resolve.Flags().StringVar(&path, "path", "", "只看仓库里的这个子目录")
	source.AddCommand(resolve)
	return source
}

// resolveCandidates 找出候选 Skill 目录。
//
// 指定子目录时只认那一个：它得存在、还得含 SKILL.md——
// 「这个路径下没有 Skill」要说清，不能悄悄给一份全仓库的清单。
func resolveCandidates(root, path string) ([]skillscan.Candidate, error) {
	if path == "" {
		return skillscan.Candidates(root, true)
	}
	dir := filepath.Join(root, filepath.FromSlash(path))
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return nil, fmt.Errorf("子目录不存在: %s", path)
	}
	if _, err := os.Stat(filepath.Join(dir, "SKILL.md")); err != nil {
		return nil, fmt.Errorf("子目录 %s 下没有 SKILL.md，不是一个 Skill 目录", path)
	}
	return []skillscan.Candidate{{Dir: dir, Name: filepath.Base(dir)}}, nil
}
