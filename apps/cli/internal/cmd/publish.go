package cmd

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// publishVisibility 是 v1 放开的可见性。
// team / organization 需要组织与成员概念，v2 才有。
var publishVisibility = []string{"private", "public"}

// publishTarget 是待发布的一个 Skill。
type publishTarget struct {
	Dir     string
	RelPath string
}

func newPublishCmd() *cobra.Command {
	var (
		version, visibility, channel, slug, name, description string
		all, dryRun, skipSecretScan                           bool
	)
	cmd := &cobra.Command{
		Use:   "publish <path>",
		Short: "把本地 Skill 打包发布到 SkillMesh 注册中心",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !contains(publishVisibility, visibility) {
				return fmt.Errorf("v1 只支持可见性 %s，收到 %q",
					strings.Join(publishVisibility, " / "), visibility)
			}
			targets, err := locatePublishTargets(args[0], all)
			if err != nil {
				return err
			}
			client, err := newClient()
			if err != nil {
				return err
			}
			options := publishOptions{
				Version:        version,
				Visibility:     visibility,
				Channel:        channel,
				Slug:           slug,
				Name:           name,
				Description:    description,
				DryRun:         dryRun,
				SkipSecretScan: skipSecretScan,
			}
			published, failed := 0, 0
			for _, target := range targets {
				result, err := publishOne(cmd.Context(), client, target, options)
				if err != nil {
					fmt.Fprintf(os.Stderr, "发布 %s 失败: %v\n", target.RelPath, err)
					failed++
					continue
				}
				fmt.Printf("%s %s -> %s（%s，%d 字节）\n",
					result.Action, result.Name, result.SkillID, result.Version, result.SizeBytes)
				published++
			}
			fmt.Printf("\n%s %d 个，失败 %d 个\n", map[bool]string{true: "演练", false: "已发布"}[dryRun], published, failed)
			if failed > 0 {
				return fmt.Errorf("%d 个发布失败", failed)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&version, "version", "", "版本号，不填则在已有版本上累加 patch 位")
	cmd.Flags().StringVar(&visibility, "visibility", "private", "可见性：private 或 public")
	cmd.Flags().StringVar(&channel, "channel", "stable", "发布通道：stable / beta / dev")
	cmd.Flags().StringVar(&slug, "slug", "", "覆盖 slug，默认取目录名")
	cmd.Flags().StringVar(&name, "name", "", "覆盖展示名，默认取 SKILL.md frontmatter 的 name")
	cmd.Flags().StringVar(&description, "description", "", "覆盖描述，默认取 SKILL.md frontmatter 的 description")
	cmd.Flags().BoolVar(&all, "all", false, "发布目录树里的全部 Skill")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只走完流程不真的发布")
	cmd.Flags().BoolVar(&skipSecretScan, "skip-secret-scan", false,
		"跳过密钥扫描（确认命中是占位符时再用）")
	return cmd
}

// publishOptions 是发布时的可调项。
type publishOptions struct {
	Version        string
	Visibility     string
	Channel        string
	Slug           string
	Name           string
	Description    string
	DryRun         bool
	SkipSecretScan bool
}

// publishResult 是一次发布的结果。
type publishResult struct {
	Action    string
	Name      string
	SkillID   string
	Version   string
	SizeBytes int64
}

// locatePublishTargets 定位要发布的 Skill。
// --all 时扫整棵目录树，否则就 path 本身这一个。
// 路径先转绝对路径：在 Skill 目录里敲 publish . 时，Base(".") 是 "."，
// 拿它当 slug 会被规则挡下来。
func locatePublishTargets(path string, all bool) ([]publishTarget, error) {
	dir, err := filepath.Abs(path)
	if err != nil {
		return nil, err
	}
	info, err := os.Stat(dir)
	if err != nil {
		return nil, fmt.Errorf("路径不存在: %s", path)
	}
	if !info.IsDir() {
		return nil, fmt.Errorf("只能发布目录（Skill 是一个目录）：%s", path)
	}
	if !all {
		return []publishTarget{{Dir: dir, RelPath: filepath.Base(dir)}}, nil
	}
	candidates, err := skillscan.Candidates(dir)
	if err != nil {
		return nil, err
	}
	if len(candidates) == 0 {
		return nil, fmt.Errorf("%s 下没有找到含 SKILL.md 的目录", path)
	}
	targets := make([]publishTarget, 0, len(candidates))
	for _, candidate := range candidates {
		rel, err := filepath.Rel(dir, candidate.Dir)
		if err != nil {
			rel = candidate.Dir
		}
		targets = append(targets, publishTarget{Dir: candidate.Dir, RelPath: filepath.ToSlash(rel)})
	}
	return targets, nil
}

// publishOne 发布一个 Skill：打包 → 建/复用 Skill 元数据 → 建版本 → 直传 → 服务端校验。
func publishOne(ctx context.Context, client *apiclient.Client, target publishTarget,
	options publishOptions) (publishResult, error) {

	if err := skillscan.ValidateDir(target.Dir, skillscan.DefaultLimits); err != nil {
		return publishResult{}, err
	}
	if !options.SkipSecretScan {
		findings, err := skillscan.ScanSecrets(target.Dir)
		if err != nil {
			return publishResult{}, err
		}
		if len(findings) > 0 {
			// 设计文档 §15.1：含已识别密钥的 Skill 上传必须被阻断并说明原因。
			return publishResult{}, errors.New(skillscan.FormatFindings(findings))
		}
	}
	metadata := skillscan.ReadMetadata(target.Dir)

	slug := options.Slug
	if slug == "" {
		var err error
		slug, err = skillscan.SlugFromDirName(filepath.Base(target.Dir))
		if err != nil {
			return publishResult{}, err
		}
	}
	displayName := options.Name
	if displayName == "" {
		displayName = metadata.Name
	}
	if displayName == "" {
		displayName = filepath.Base(target.Dir)
	}
	description := options.Description
	if description == "" {
		description = metadata.Description
	}

	artifact, err := packager.Pack(target.Dir)
	if err != nil {
		return publishResult{}, err
	}

	skill, found, err := client.FindSkillBySlug(ctx, slug)
	if err != nil {
		return publishResult{}, err
	}
	if !found {
		if options.DryRun {
			// 云端还没有这个 Skill，没有版本可查——演练到这里就够了。
			// 拿占位 ID 去调 Versions 会得到 500。
			return publishResult{
				Action:    "将发布",
				Name:      displayName,
				SkillID:   "<新建>",
				Version:   orDefaultVersion(options.Version),
				SizeBytes: artifact.SizeBytes,
			}, nil
		}
		skill, err = client.CreateSkill(ctx, apiclient.CreateSkillRequest{
			Slug:        slug,
			Name:        displayName,
			Description: description,
			Visibility:  options.Visibility,
		})
		if err != nil {
			return publishResult{}, err
		}
	}

	version := options.Version
	if version == "" {
		existing, err := client.Versions(ctx, skill.ID)
		if err != nil {
			return publishResult{}, err
		}
		names := make([]string, 0, len(existing.Items))
		for _, item := range existing.Items {
			names = append(names, item.Version)
		}
		version, err = nextVersion(names)
		if err != nil {
			return publishResult{}, err
		}
	}

	if options.DryRun {
		return publishResult{
			Action:    "将发布",
			Name:      displayName,
			SkillID:   skill.ID,
			Version:   version,
			SizeBytes: artifact.SizeBytes,
		}, nil
	}

	created, err := client.CreateVersion(ctx, skill.ID, apiclient.CreateVersionRequest{
		Version:   version,
		Channel:   options.Channel,
		SizeBytes: artifact.SizeBytes,
		FileCount: len(artifact.Manifest),
		SHA256:    artifact.SHA256,
		Manifest:  toManifest(artifact.Manifest),
	})
	if err != nil {
		return publishResult{}, err
	}
	if err := client.PutArtifact(ctx, created.Upload, artifact.Zip); err != nil {
		return publishResult{}, err
	}
	session, err := client.CompleteUpload(ctx, created.Upload.SessionID)
	if err != nil {
		return publishResult{}, err
	}
	if session.Status != "completed" {
		return publishResult{}, fmt.Errorf("服务端校验未通过：%s %s", session.Status, session.ErrorCode)
	}
	return publishResult{
		Action:    "已发布",
		Name:      displayName,
		SkillID:   skill.ID,
		Version:   version,
		SizeBytes: artifact.SizeBytes,
	}, nil
}

func toManifest(entries []packager.Entry) []apiclient.ManifestEntry {
	out := make([]apiclient.ManifestEntry, 0, len(entries))
	for _, entry := range entries {
		out = append(out, apiclient.ManifestEntry{
			Path:       entry.Path,
			SHA256:     entry.SHA256,
			SizeBytes:  entry.SizeBytes,
			Executable: entry.Executable,
		})
	}
	return out
}

func contains(list []string, value string) bool {
	for _, item := range list {
		if item == value {
			return true
		}
	}
	return false
}
