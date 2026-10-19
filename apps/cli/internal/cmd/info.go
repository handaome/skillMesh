package cmd

import (
	"fmt"
	"os"
	"text/tabwriter"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

func newInfoCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "info <skill-id>",
		Short: "查看 Skill 详情、来源、扫描结论与版本列表",
		Long: "装之前先看一眼：来源、许可证、扫描结论和风险都在这儿。\n" +
			"Git 来源的仓库与 commit 来自本项目的锁文件——注册中心那边没存。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient()
			if err != nil {
				return err
			}
			skill, err := client.Skill(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			fmt.Printf("%s (%s)\n%s\n", skill.Name, skill.Slug, skill.Description)

			versions, err := client.Versions(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			if len(versions.Items) == 0 {
				fmt.Println("暂无版本")
				return nil
			}
			printProvenance(skill, versions.Items[0])
			if err := printVersions(versions.Items); err != nil {
				return err
			}
			return printRisks(versions.Items)
		},
	}
}

// printProvenance 打印来源信息。缺的就写「未提供」，不留空让人猜。
func printProvenance(skill apiclient.Skill, latest apiclient.SkillVersion) {
	fmt.Println("\n来源")
	lock, locked := localEntryByID(skill.ID)
	switch {
	case locked && lock.Repository != "":
		fmt.Printf("  仓库      %s（%s）\n", lock.Repository, lock.Path)
		fmt.Printf("  commit    %s\n", orDash(short(lock.ResolvedCommit)))
	case locked:
		fmt.Printf("  来源      SkillMesh 注册中心（本项目锁文件记录的 Skill ID %s）\n", lock.SourceID)
	default:
		fmt.Println("  来源      SkillMesh 注册中心")
	}
	fmt.Printf("  可见性    %s\n", skill.Visibility)
	latestVersion := "-"
	if skill.LatestVersion != nil {
		latestVersion = *skill.LatestVersion
	}
	fmt.Printf("  最新版本  %s（状态 %s）\n", latestVersion, latest.Status)
	fmt.Println("  许可证    未提供（注册中心不存这个字段，识别仅供参考）")
}

func printVersions(versions []apiclient.SkillVersion) error {
	w := tabwriter.NewWriter(os.Stdout, 0, 0, 2, ' ', 0)
	fmt.Fprintln(w, "\n版本\t通道\t状态\t扫描\tcommit\tSHA256")
	for _, version := range versions {
		commit := "-"
		if version.SourceCommit != nil {
			commit = short(*version.SourceCommit)
		}
		digest := "-"
		if version.Artifact != nil && len(version.Artifact.SHA256) >= 12 {
			digest = version.Artifact.SHA256[:12]
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\n",
			version.Version, version.Channel, version.Status, scanLabel(version.Scan),
			commit, digest)
	}
	return w.Flush()
}

func scanLabel(scan *apiclient.ScanSummary) string {
	if scan == nil {
		return "未扫描"
	}
	return scan.Status
}

// printRisks 把没发布成功的版本和扫描命中摊开——这些是需要人看的。
func printRisks(versions []apiclient.SkillVersion) error {
	var findings []apiclient.ScanFinding
	var blocked []string
	for _, version := range versions {
		if version.Status != "published" {
			blocked = append(blocked, fmt.Sprintf("%s（%s）", version.Version, version.Status))
		}
		if version.Scan != nil {
			findings = append(findings, version.Scan.Findings...)
		}
	}
	if len(blocked) == 0 && len(findings) == 0 {
		return nil
	}
	fmt.Println("\n风险")
	for _, version := range blocked {
		fmt.Printf("  %s 不能安装：不是已发布状态\n", version)
	}
	for _, finding := range findings {
		fmt.Printf("  %s:%d %s —— %s\n", finding.Path, finding.Line, finding.Rule, finding.Detail)
	}
	if len(findings) > 0 || len(blocked) > 0 {
		fmt.Println("  静态扫描只能帮你发现风险，不能证明这份 Skill 安全。")
	}
	return nil
}

// localEntryByID 在本项目锁文件里找这个 Skill。
func localEntryByID(skillID string) (lockfile.LockedSkill, bool) {
	lock, err := lockfile.Load(mustGetwd())
	if err != nil {
		return lockfile.LockedSkill{}, false
	}
	for _, entry := range lock.Skills {
		if entry.SourceID == skillID {
			return entry, true
		}
	}
	return lockfile.LockedSkill{}, false
}
