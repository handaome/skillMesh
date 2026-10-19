package cmd

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

func newAddCmd() *cobra.Command {
	var (
		ref, subpath, agentName, scope, credentialRef string
		doInstall, replace                            bool
	)
	cmd := &cobra.Command{
		Use:   "add <repo-url>",
		Short: "登记一个 Git 来源（加 --install 才安装到 Agent 目录）",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			repo, err := gitx.NormalizeURL(args[0])
			if err != nil {
				return err
			}
			if credentialRef != "" {
				fmt.Printf("使用凭据引用 %s（不接受明文令牌）\n", credentialRef)
			}

			work, err := os.MkdirTemp("", "skillmesh-add-*")
			if err != nil {
				return err
			}
			defer os.RemoveAll(work)

			root := filepath.Join(work, "repo")
			commit, err := gitx.Clone(cmd.Context(), repo, ref, root)
			if err != nil {
				return err
			}

			skillDir, err := locateSkillDir(root, subpath)
			if err != nil {
				return err
			}
			if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
				return err
			}

			name := skillscan.SkillName(skillDir)
			relPath, _ := filepath.Rel(root, skillDir)
			relPath = filepath.ToSlash(relPath)
			fmt.Printf("来源 %s\n子目录 %s\ncommit %s\nSkill %s\n", repo, relPath, commit, name)

			if !doInstall {
				fmt.Println("已登记。加 --install 安装到 Agent 目录。")
				return nil
			}

			projectRoot := mustGetwd()
			targetScope := scope
			if targetScope == "" {
				targetScope = "project"
				if projectRoot == "" {
					targetScope = "user"
				}
			}
			base, err := agent.Dir(cfg, agentName, targetScope, projectRoot)
			if err != nil {
				return err
			}
			target := filepath.Join(base, name)
			if err := checkTarget(target, replace); err != nil {
				return err
			}
			if err := installer.InstallAtomic(skillDir, target, true); err != nil {
				return err
			}
			if err := installer.MarkInstalled(target, repo); err != nil {
				return err
			}

			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			lock.Upsert(lockfile.LockedSkill{
				Name:           name,
				Repository:     repo,
				Path:           relPath,
				RefType:        refTypeOf(ref),
				Ref:            ref,
				ResolvedCommit: commit,
				Agents:         []string{agentName},
			})
			if err := lock.Save(projectRoot); err != nil {
				return err
			}
			fmt.Printf("已安装到 %s\n锁文件 %s\n", target, lockfile.FileName)
			return nil
		},
	}
	cmd.Flags().StringVar(&ref, "ref", "", "分支、标签或 commit")
	cmd.Flags().StringVar(&subpath, "path", "", "仓库内 Skill 子目录")
	cmd.Flags().StringVar(&agentName, "agent", "claude-code", "目标 Agent")
	cmd.Flags().StringVar(&scope, "scope", "", "user 或 project（默认有项目根时为 project）")
	cmd.Flags().StringVar(&credentialRef, "credential-ref", "", "本机凭据管理器中的凭据引用")
	cmd.Flags().BoolVar(&doInstall, "install", false, "解析和扫描通过后立即安装")
	cmd.Flags().BoolVar(&replace, "replace", false, "覆盖不受 SkillMesh 管理的同名目录")
	return cmd
}

// locateSkillDir 定位要安装的 Skill 目录：给了 --path 就用它，
// 否则按候选数量决定——唯一候选直接用，多个必须让用户明确指定。
func locateSkillDir(root, subpath string) (string, error) {
	if subpath != "" {
		dir := filepath.Join(root, filepath.FromSlash(subpath))
		if _, err := os.Stat(dir); err != nil {
			return "", fmt.Errorf("子目录不存在: %s", subpath)
		}
		return dir, nil
	}
	candidates, err := skillscan.Candidates(root)
	if err != nil {
		return "", err
	}
	switch len(candidates) {
	case 0:
		return "", fmt.Errorf("仓库中未找到含 SKILL.md 的目录")
	case 1:
		return candidates[0].Dir, nil
	default:
		names := make([]string, 0, len(candidates))
		for _, candidate := range candidates {
			rel, relErr := filepath.Rel(root, candidate.Dir)
			if relErr != nil {
				rel = candidate.Name
			}
			names = append(names, filepath.ToSlash(rel))
		}
		return "", fmt.Errorf("仓库含多个候选 Skill，请用 --path 指定其中一个：%s",
			strings.Join(names, ", "))
	}
}

// checkTarget 同名且不受管理时默认不覆盖，避免毁掉用户手写的 Skill。
func checkTarget(target string, replace bool) error {
	if _, err := os.Stat(target); err != nil {
		return nil
	}
	if replace || installer.IsManaged(target) {
		return nil
	}
	return fmt.Errorf("目标已存在且不受 SkillMesh 管理: %s（用 --replace 覆盖）", target)
}

func refTypeOf(ref string) string {
	switch {
	case ref == "":
		return "default"
	case len(ref) == 40:
		return "commit"
	default:
		return "branch"
	}
}
