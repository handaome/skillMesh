package cmd

import (
	"context"
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
			targetScope := scope
			if targetScope == "" {
				targetScope = "user"
			}
			return installArtifact(cmd.Context(), client, projectRoot, installRequest{
				SkillID: args[0],
				Version: target,
				Agents:  agentNames,
				Scope:   targetScope,
				Replace: replace,
			})
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

// installRequest 是一次注册中心安装的全部参数。
type installRequest struct {
	SkillID string
	Version apiclient.SkillVersion
	Agents  []string
	Scope   string
	Replace bool
}

// installArtifact 下载指定版本、校验、原子装进各 Agent 目录并写锁文件。
// install 和 rollback 走同一条路——回滚就是装一个更早的不可变版本。
func installArtifact(ctx context.Context, client *apiclient.Client, projectRoot string,
	req installRequest) error {

	data, err := client.DownloadArtifact(ctx, req.Version.Artifact.ID)
	if err != nil {
		return err
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
	warnRisks(skillDir)
	fmt.Println(skillscan.LicenseLine(skillDir))
	name := skillscan.SkillName(skillDir)

	for _, agentName := range req.Agents {
		base, err := agent.Dir(cfg, agentName, req.Scope, projectRoot)
		if err != nil {
			return err
		}
		installPath := filepath.Join(base, name)
		if err := checkTarget(installPath, req.Replace); err != nil {
			return err
		}
		if err := installer.InstallAtomic(skillDir, installPath, true); err != nil {
			return err
		}
		if err := installer.MarkInstalled(installPath, "skillmesh:"+req.SkillID); err != nil {
			return err
		}
		fmt.Printf("已安装 %s@%s 到 %s\n", name, req.Version.Version, installPath)
	}
	if err := recordInstall(projectRoot, name, req.SkillID, req.Version, req.Scope, req.Agents); err != nil {
		return err
	}
	fmt.Printf("锁文件 %s\n", lockfile.FileName)
	return nil
}

// recordInstall 把注册中心安装写进锁文件。
// 不写的话 list 看不到它，CI 的 --locked 也无从校验（设计文档 §9.2）。
func recordInstall(projectRoot, name, skillID string, target apiclient.SkillVersion,
	scope string, agents []string) error {

	lock, err := lockfile.Load(projectRoot)
	if err != nil {
		return err
	}
	entry := lockfile.LockedSkill{
		Name:     name,
		SourceID: skillID,
		RefType:  "version",
		Ref:      target.Version,
		Version:  target.Version,
		Scope:    scope,
		Agents:   agents,
	}
	if target.Artifact != nil {
		entry.SHA256 = target.Artifact.SHA256
	}
	lock.Upsert(entry)
	return lock.Save(projectRoot)
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
