package cmd

import (
	"fmt"
	"time"

	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/spf13/cobra"
)

// newLockCmd 锁文件维护（详细设计 §3.10）。
func newLockCmd() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "lock",
		Short: "锁文件维护",
	}
	cmd.AddCommand(newLockRefreshCmd())
	return cmd
}

// newLockRefreshCmd 重新解析来源，更新锁文件里记的 commit。
//
// 锁文件记的是上次解析到的 commit。远端前进了而锁文件不动，
// CI 的 --locked 校验就拿旧 commit 当准，说「没漂移」其实已经漂了。
//
// 只重新解析，不安装、不下载——这是「把锁文件对齐远端」，不是「更新 Skill」。
func newLockRefreshCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "refresh",
		Short: "重新解析来源并更新锁文件",
		Args:  cobra.NoArgs,
		RunE: func(cmd *cobra.Command, _ []string) error {
			projectRoot := projectRootDir()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			changed := 0
			for i := range lock.Skills {
				entry := &lock.Skills[i]
				// 本地来源没有远端可解析。
				if entry.Repository == "" {
					continue
				}
				remote, err := LsRemote(cmd.Context(), entry.Repository, entry.Ref)
				if err != nil {
					fmt.Printf("%s 解析失败: %v\n", entry.Name, err)
					continue
				}
				if remote == entry.ResolvedCommit {
					continue
				}
				fmt.Printf("%s %s → %s\n", entry.Name,
					short(entry.ResolvedCommit), short(remote))
				entry.ResolvedCommit = remote
				changed++
			}
			if changed == 0 {
				fmt.Println("锁文件已是最新")
				return nil
			}
			lock.ResolvedAt = time.Now().UTC().Format(time.RFC3339)
			if err := lock.Save(projectRoot); err != nil {
				return err
			}
			fmt.Printf("已更新 %d 条\n", changed)
			return nil
		},
	}
}
