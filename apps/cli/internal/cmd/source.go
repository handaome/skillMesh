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
	var ref string
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
			candidates, err := skillscan.Candidates(root, true)
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
	source.AddCommand(resolve)
	return source
}
