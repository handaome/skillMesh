package cmd

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

func newInstallCmd() *cobra.Command {
	var agentNames []string
	var scope, version string
	var locked, dryRun, replace bool

	cmd := &cobra.Command{
		Use:   "install <skill-id>",
		Short: "从 SkillMesh 注册中心安装指定版本的 Skill",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			client, err := newClient()
			if err != nil {
				return err
			}
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			fromLock, hasLock := lock.Find(args[0])

			versions, err := client.Versions(cmd.Context(), args[0])
			if err != nil {
				return err
			}
			target, err := pickVersion(versions, version, locked, fromLock, hasLock)
			if err != nil {
				return err
			}

			data, err := client.DownloadArtifact(cmd.Context(), target.Artifact.ID)
			if err != nil {
				return err
			}
			if dryRun {
				fmt.Printf("演练：将从 %s@%s 安装 %d 字节到 %v\n",
					args[0], target.Version, len(data), agentNames)
				return nil
			}

			work, err := os.MkdirTemp("", "skillmesh-install-*")
			if err != nil {
				return err
			}
			defer os.RemoveAll(work)

			skillDir, err := installer.ExtractZip(data, work)
			if err != nil {
				return err
			}
			if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
				return err
			}
			name := skillscan.SkillName(skillDir)

			for _, agentName := range agentNames {
				base, err := agent.Dir(cfg, agentName, scope, projectRoot)
				if err != nil {
					return err
				}
				installPath := filepath.Join(base, name)
				if err := checkTarget(installPath, replace); err != nil {
					return err
				}
				if err := installer.InstallAtomic(skillDir, installPath, true); err != nil {
					return err
				}
				if err := installer.MarkInstalled(installPath, "skillmesh:"+args[0]); err != nil {
					return err
				}
				fmt.Printf("已安装 %s@%s 到 %s\n", name, target.Version, installPath)
			}
			return nil
		},
	}
	cmd.Flags().StringSliceVar(&agentNames, "agent", []string{"claude-code"}, "目标 Agent，可重复指定")
	cmd.Flags().StringVar(&scope, "scope", "user", "user 或 project")
	cmd.Flags().StringVar(&version, "version", "", "指定版本，默认取最新已发布")
	cmd.Flags().BoolVar(&locked, "locked", false, "严格按锁文件校验，不符则失败")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只演练不落盘")
	cmd.Flags().BoolVar(&replace, "replace", false, "覆盖不受 SkillMesh 管理的同名目录")
	return cmd
}

// pickVersion 选要装的版本。--locked 时必须与锁文件完全一致，
// 否则 CI 会静默装到漂移的版本（详细设计 §3.10）。
func pickVersion(versions apiclient.VersionPage, want string, locked bool,
	fromLock lockfile.LockedSkill, hasLock bool) (apiclient.SkillVersion, error) {
	if locked && hasLock {
		want = fromLock.Version
	}
	if want == "" {
		for _, version := range versions.Items {
			if version.Status == "published" {
				return version, nil
			}
		}
		return apiclient.SkillVersion{}, fmt.Errorf("该 Skill 没有已发布的版本")
	}
	for _, candidate := range versions.Items {
		if candidate.Version != want || candidate.Status != "published" {
			continue
		}
		if locked && fromLock.SHA256 != "" && candidate.Artifact != nil &&
			candidate.Artifact.SHA256 != fromLock.SHA256 {
			return apiclient.SkillVersion{}, fmt.Errorf(
				"版本 %s 的摘要与锁文件不符：锁文件 %s，实际 %s",
				want, fromLock.SHA256, candidate.Artifact.SHA256)
		}
		if candidate.Artifact == nil {
			return apiclient.SkillVersion{}, fmt.Errorf("版本 %s 没有可安装的制品", want)
		}
		return candidate, nil
	}
	return apiclient.SkillVersion{}, fmt.Errorf("找不到已发布的版本: %s", want)
}
