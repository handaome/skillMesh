package cmd

import (
	"fmt"
	"os"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

// infoPayload 是 info 的机器可读结果。
type infoPayload struct {
	Skill    apiclient.Skill          `json:"skill"`
	Versions []apiclient.SkillVersion `json:"versions"`
	Blocked  []string                 `json:"blocked_versions"`
	Findings []apiclient.ScanFinding  `json:"scan_findings"`
}

func newInfoCmd() *cobra.Command {
	return supportsJSON(&cobra.Command{
		Use:   "info <skill-id>",
		Short: "查看 Skill 详情、来源、扫描结论与版本列表",
		Long: "装之前先看一眼：来源、许可证、扫描结论和风险都在这儿。\n" +
			"Git 来源的仓库与 commit 来自本项目的锁文件——注册中心那边没存。",
		Args: cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			skill, err := client.Skill(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			versions, err := client.Versions(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			blocked, findings := collectRisks(versions.Items)
			if jsonEnabled() {
				return writeJSON(infoPayload{
					Skill:    skill,
					Versions: jsonItems(versions.Items),
					Blocked:  jsonItems(blocked),
					Findings: jsonItems(findings),
				})
			}
			fmt.Printf("%s (%s)\n%s\n", skill.Name, skill.Slug, skill.Description)
			if len(versions.Items) == 0 {
				fmt.Println("暂无版本")
				return nil
			}
			printProvenance(skill, versions.Items[0])
			if err := printVersions(versions.Items); err != nil {
				return err
			}
			return printRisks(blocked, findings)
		},
	})
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
	w := newTable(os.Stdout)
	fmt.Fprintln(w, "\n版本\t通道\t状态\t扫描\t签名\tcommit\tSHA256")
	for _, version := range versions {
		commit := "-"
		if version.SourceCommit != nil {
			commit = short(*version.SourceCommit)
		}
		digest := "-"
		if version.Artifact != nil && len(version.Artifact.SHA256) >= 12 {
			digest = version.Artifact.SHA256[:12]
		}
		fmt.Fprintf(w, "%s\t%s\t%s\t%s\t%s\t%s\t%s\n",
			version.Version, version.Channel, version.Status, scanLabel(version.Scan),
			signatureLabel(version.Artifact),
			commit, digest)
	}
	return w.Flush()
}

// signatureLabel 只显示有没有签，不在这里做验签——info 不下载内容。
func signatureLabel(artifact *apiclient.ArtifactSummary) string {
	if artifact == nil || artifact.Signature == "" {
		return "未签名"
	}
	return artifact.SignatureKeyID
}

func scanLabel(scan *apiclient.ScanSummary) string {
	if scan == nil {
		return "未扫描"
	}
	return scan.Status
}

// collectRisks 挑出装不了的版本和扫描命中。
//
// 人读输出和 JSON 输出都要这两样，分开算就会出现两边对不上的情况。
func collectRisks(versions []apiclient.SkillVersion) ([]string, []apiclient.ScanFinding) {
	var blocked []string
	var findings []apiclient.ScanFinding
	for _, version := range versions {
		if version.Status != "published" {
			blocked = append(blocked, fmt.Sprintf("%s（%s）", version.Version, version.Status))
		}
		if version.Scan != nil {
			findings = append(findings, version.Scan.Findings...)
		}
	}
	return blocked, findings
}

// printRisks 把没发布成功的版本和扫描命中摊开——这些是需要人看的。
func printRisks(blocked []string, findings []apiclient.ScanFinding) error {
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
	lock, err := lockfile.Load(projectRootDir())
	if err != nil {
		return lockfile.LockedSkill{}, false
	}
	return lock.FindBySourceID(skillID)
}
