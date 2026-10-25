package cmd

import (
	"context"
	"fmt"
	"os"
	"strings"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// newRecycleCmd 云端回收站：被删掉的文件，回收期内能找回来。
//
// 本地的回收站是 skillmesh trash（卸载掉的 Skill）；
// 这里管的是云端某个 Skill 里被删掉的文件。
func newRecycleCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "recycle",
		Short: "云端回收站：被删掉的文件，回收期内能找回来",
		Long: "删掉一个文件不是抹掉它：它进了云端回收站，回收期内能找回来。\n" +
			"恢复会产出一个新版本，跟别的发版一样要过审核。\n\n" +
			"本机的回收站是 skillmesh trash（卸载掉的 Skill），两边不是一个东西。",
		Args: cobra.NoArgs,
	}
	cmd.AddCommand(newRecycleListCmd(), newRecycleRestoreCmd())
	return cmd
}

func newRecycleListCmd() *cobra.Command {
	var skill string
	cmd := &cobra.Command{
		Use:   "list",
		Short: "看回收站里有什么",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			id, err := ResolveSkillID(cmd.Context(), client, skill)
			if err != nil {
				return err
			}
			rows, err := client.RecycleFiles(cmd.Context(), id)
			if err != nil {
				return err
			}
			if len(rows) == 0 {
				fmt.Println("回收站是空的。")
				return nil
			}
			w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
			fmt.Fprintln(w, "文件\t来源版本\t删除时间\t回收期到")
			for _, row := range rows {
				fmt.Fprintf(w, "%s\t%s\t%s\t%s\n", row.Path, row.SourceVersion,
					row.DeletedAt, row.ExpiresAt)
			}
			return w.Flush()
		},
	}
	cmd.Flags().StringVar(&skill, "skill", "", "哪个 Skill（slug 或 id），不填用当前目录的")
	return cmd
}

func newRecycleRestoreCmd() *cobra.Command {
	var skill string
	cmd := &cobra.Command{
		Use:   "restore <path>",
		Short: "把回收站里的文件放回去",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			id, err := ResolveSkillID(cmd.Context(), client, skill)
			if err != nil {
				return err
			}
			result, err := client.RestoreFile(cmd.Context(), id, args[0])
			if err != nil {
				return err
			}
			fmt.Printf("已恢复 %s，产出版本 %s（%s）\n",
				args[0], result.Version, result.Status)
			return nil
		},
	}
	cmd.Flags().StringVar(&skill, "skill", "", "哪个 Skill（slug 或 id），不填用当前目录的")
	return cmd
}

// ResolveSkillID 把 slug 或 id 变成 Skill id。
//
// 不给 --skill 时用当前目录的 Skill——人在哪个 Skill 的目录里，多半就是
// 想处理哪个。
func ResolveSkillID(ctx context.Context, client *apiclient.Client, given string) (string, error) {
	if given != "" {
		// id 是 UUID，slug 规则不接受：先按 id 认，认不出再当 slug 查。
		if looksLikeUUID(given) {
			return given, nil
		}
		skill, found, err := client.FindSkillBySlug(ctx, given)
		if err != nil {
			return "", err
		}
		if !found {
			return "", fmt.Errorf("云端没有叫 %s 的 Skill", given)
		}
		return skill.ID, nil
	}
	dir := mustGetwd()
	name := skillscan.SkillName(dir)
	if name == "" {
		return "", fmt.Errorf("看不出当前目录属于哪个 Skill，用 --skill 指定")
	}
	skill, found, err := client.FindSkillBySlug(ctx, name)
	if err != nil {
		return "", err
	}
	if !found {
		return "", fmt.Errorf("云端没有叫 %s 的 Skill，用 --skill 指定", name)
	}
	return skill.ID, nil
}

// looksLikeUUID 粗判一下像不像 UUID：36 个字符、含短横线。
//
// 不引 uuid 库——只为了「像不像」不值得加个依赖。
func looksLikeUUID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for i, c := range value {
		if i == 8 || i == 13 || i == 18 || i == 23 {
			if c != '-' {
				return false
			}
			continue
		}
		if !strings.ContainsRune("0123456789abcdefABCDEF", c) {
			return false
		}
	}
	return true
}
