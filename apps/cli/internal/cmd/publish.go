package cmd

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/packager"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

// publishVisibility 是发布时允许的可见性。
// team / organization 需要组织与成员概念，v2 起可用。
var publishVisibility = []string{"private", "public", "team", "organization"}

// PublishTarget 是待发布的一个 Skill。
type PublishTarget struct {
	Dir     string
	RelPath string
}

func newPublishCmd() *cobra.Command {
	var (
		version, visibility, channel, slug, name, description string
		orgSlug, teamSlug                                     string
		all, dryRun, skipSecretScan                           bool
	)
	cmd := &cobra.Command{
		Use:   "publish <path>",
		Short: "把本地 Skill 打包发布到 SkillMesh 注册中心",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !contains(publishVisibility, visibility) {
				return fmt.Errorf("只支持可见性 %s，收到 %q",
					strings.Join(publishVisibility, " / "), visibility)
			}
			// 可见性只认用户显式给的：默认值不该被当成「用户要求改成 private」。
			requested := ""
			if cmd.Flags().Changed("visibility") {
				requested = visibility
			}
			if err := CheckScopeFlags(orgSlug, teamSlug); err != nil {
				return err
			}
			if err := CheckVisibilityFlag(orgSlug, teamSlug, requested); err != nil {
				return err
			}
			targets, err := locatePublishTargets(args[0], all)
			if err != nil {
				return err
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			scope, err := LoadPublishScope(cmd.Context(), client, orgSlug, teamSlug, visibility)
			if err != nil {
				return err
			}
			options := PublishOptions{
				Version:             version,
				Visibility:          visibility,
				Channel:             channel,
				Slug:                slug,
				Name:                name,
				Description:         description,
				DryRun:              dryRun,
				SkipSecretScan:      skipSecretScan,
				Scope:               scope,
				RequestedVisibility: requested,
			}
			published, Failed := 0, 0
			for _, target := range targets {
				result, err := PublishOne(cmd.Context(), client, target, options)
				if err != nil {
					fmt.Fprintf(os.Stderr, "发布 %s 失败: %v\n", target.RelPath, err)
					Failed++
					continue
				}
				fmt.Printf("%s %s -> %s（%s，%d 字节）\n",
					result.Action, result.Name, result.SkillID, result.Version, result.SizeBytes)
				published++
			}
			fmt.Printf("\n%s %d 个，失败 %d 个\n", map[bool]string{true: "演练", false: "已发布"}[dryRun], published, Failed)
			if Failed > 0 {
				return fmt.Errorf("%d 个发布失败", Failed)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&version, "version", "", "版本号，不填则在已有版本上累加 patch 位")
	cmd.Flags().StringVar(&visibility, "visibility", "private", "可见性：private / public / team / organization")
	cmd.Flags().StringVar(&channel, "channel", "stable", "发布通道：stable / beta / dev")
	cmd.Flags().StringVar(&slug, "slug", "", "覆盖 slug，默认取目录名")
	cmd.Flags().StringVar(&name, "name", "", "覆盖展示名，默认取 SKILL.md frontmatter 的 name")
	cmd.Flags().StringVar(&description, "description", "", "覆盖描述，默认取 SKILL.md frontmatter 的 description")
	cmd.Flags().StringVar(&orgSlug, "org", "", "发布到的组织（slug）")
	cmd.Flags().StringVar(&teamSlug, "team", "", "发布到的团队（slug），需同时指定 --org")
	cmd.Flags().BoolVar(&all, "all", false, "发布目录树里的全部 Skill")
	cmd.Flags().BoolVar(&dryRun, "dry-run", false, "只走完流程不真的发布")
	cmd.Flags().BoolVar(&skipSecretScan, "skip-secret-scan", false,
		"跳过密钥扫描（确认命中是占位符时再用）")
	return cmd
}

// PublishOptions 是发布时的可调项。
type PublishOptions struct {
	Version        string
	Visibility     string
	Channel        string
	Slug           string
	Name           string
	Description    string
	DryRun         bool
	SkipSecretScan bool
	Scope          PublishScope
	// RequestedVisibility 是用户显式给的 --visibility，没给就是空串。
	// 已有 Skill 的可见性改不动，带着这个去发布要报错，而不是假装生效。
	RequestedVisibility string
	// ExpectedBaseRevision 是「本次决策依据的云端已发布版本内容摘要」。
	// 只有同步上传才带：普通 publish 不带（服务端也就不校验基线）。
	ExpectedBaseRevision string
}

// VisibilityFor 定出这次发布的可见性。
// 指定了 --org/--team 时 Scope 里已经定了（可能是 team / organization）；
// sync 这类内部调用不给 Scope，就用原来的 Visibility。
func (o PublishOptions) VisibilityFor() string {
	if o.Scope.Visibility != "" {
		return o.Scope.Visibility
	}
	return o.Visibility
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
func locatePublishTargets(path string, all bool) ([]PublishTarget, error) {
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
		return []PublishTarget{{Dir: dir, RelPath: filepath.Base(dir)}}, nil
	}
	candidates, err := skillscan.Candidates(dir)
	if err != nil {
		return nil, err
	}
	if len(candidates) == 0 {
		return nil, fmt.Errorf("%s 下没有找到含 SKILL.md 的目录", path)
	}
	targets := make([]PublishTarget, 0, len(candidates))
	for _, candidate := range candidates {
		rel, err := filepath.Rel(dir, candidate.Dir)
		if err != nil {
			rel = candidate.Dir
		}
		targets = append(targets, PublishTarget{Dir: candidate.Dir, RelPath: filepath.ToSlash(rel)})
	}
	return targets, nil
}

// PublishOne 发布一个 Skill：打包 → 建/复用 Skill 元数据 → 建版本 → 直传 → 服务端校验。
func PublishOne(ctx context.Context, client *apiclient.Client, target PublishTarget,
	options PublishOptions) (publishResult, error) {

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
		skill, err = createSkill(ctx, client, slug, displayName, description, options,
			sourceOf(target.Dir, skillscan.DetectLicense(target.Dir)))
		if err != nil {
			return publishResult{}, err
		}
	} else if err := CheckExistingSkill(skill, options.Scope, options.RequestedVisibility); err != nil {
		// 已有 Skill 的归属与可见性都改不动：带着新要求发布只会让人以为生效了。
		return publishResult{}, err
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
		version, err = NextVersion(names)
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
		Channel:   orDefaultChannel(options.Channel),
		SizeBytes: artifact.SizeBytes,
		FileCount: len(artifact.Manifest),
		SHA256:    artifact.SHA256,
		Manifest:  toManifest(artifact.Manifest),
		// 基线带上，服务端才知道这次上传是基于哪个云端版本做的。
		ExpectedBaseRevision: options.ExpectedBaseRevision,
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
	// 结果看版本自己的状态，不从 Skill 的可见性去猜：服务端扫描拦下的版本
	// 可见性还是 team，照可见性猜就会说「已提交审核」，用户等一场不会来的审核。
	switch session.VersionStatus {
	case "rejected", "quarantined":
		return publishResult{}, fmt.Errorf(
			"版本 %s 被服务端的制品扫描拦下了（%s），没有进审核队列。"+
				"服务端扫的是上传的真包，本地扫描没看到的文件也会被算进去——清掉包里的密钥再重发",
			version, session.VersionStatus)
	case "pending_review":
		return publishResult{
			Action:    "已提交审核",
			Name:      displayName,
			SkillID:   skill.ID,
			Version:   version,
			SizeBytes: artifact.SizeBytes,
		}, nil
	case "published":
		return publishResult{
			Action:    "已发布",
			Name:      displayName,
			SkillID:   skill.ID,
			Version:   version,
			SizeBytes: artifact.SizeBytes,
		}, nil
	default:
		return publishResult{}, fmt.Errorf(
			"服务端没有返回版本状态（拿到 %q），无法确认这次发布的结果", session.VersionStatus)
	}
}

// createSkill 新建 Skill 元数据：可见性与归属按解析出来的范围填。
// source 是来源元数据（设计文档 §7.1）：从 Git 装的 Skill 要记上仓库与 commit，
// 服务端只拿得到这些——本机才知道自己从哪儿装的。
func createSkill(ctx context.Context, client *apiclient.Client, slug, displayName, description string,
	options PublishOptions, source *apiclient.SkillSource) (apiclient.Skill, error) {

	skill, err := client.CreateSkill(ctx, apiclient.CreateSkillRequest{
		Slug:           slug,
		Name:           displayName,
		Description:    description,
		Visibility:     options.VisibilityFor(),
		OrganizationID: optionalString(options.Scope.OrganizationID),
		OwnerTeamID:    optionalString(options.Scope.TeamID),
		Source:         source,
	})
	if err != nil {
		return apiclient.Skill{}, explainSlugTaken(err, slug, options.Scope)
	}
	return skill, nil
}

// explainSlugTaken 把服务端的 SLUG_TAKEN 翻译成人能看懂的话：
// 团队可见的 Skill 你可能根本看不见（不是团队成员），撞名只能由服务端说。
func explainSlugTaken(err error, slug string, scope PublishScope) error {
	if apiclient.CodeOf(err) != "SLUG_TAKEN" {
		return err
	}
	where := "你名下"
	hint := "换个 slug 重试"
	if scope.OrganizationID != "" {
		org := scope.OrganizationSlug
		if org == "" {
			org = scope.OrganizationID
		}
		where = "组织 " + org + " 下"
		hint = "换个 slug 重试，或去掉 --org/--team 给已有的 Skill 发新版本"
	}
	return fmt.Errorf("slug %q 已被占用：%s已经有同名的 Skill（团队可见的 Skill 你可能看不见它），%s",
		slug, where, hint)
}

// sourceOf 从项目锁文件里找这个目录的来源，整理成服务端要的形状（设计文档 §7.1）。
//
// 服务端拿不到 git 仓库和 commit——只有当初装它的那台机器知道。
// 找不到就记 local_import，不假装有来源。
func sourceOf(dir, license string) *apiclient.SkillSource {
	source := &apiclient.SkillSource{
		SourceType:    "local_import",
		Provider:      "local",
		LicenseStatus: "unknown",
	}
	if license != "" {
		source.LicenseExpression = license
		source.LicenseStatus = "detected"
	}
	entry, found := localEntryByName(skillscan.SkillName(dir))
	if !found || entry.Repository == "" {
		return source
	}
	source.SourceType = "git"
	source.Provider = orDash(entry.Provider)
	if source.Provider == "-" {
		source.Provider = "generic_git"
	}
	source.RepositoryURL = entry.Repository
	source.Subpath = entry.Path
	source.RefType = orDash(entry.RefType)
	source.RefValue = entry.Ref
	source.ResolvedCommit = entry.ResolvedCommit
	return source
}

// localEntryByName 在当前项目的锁文件里找一个 Skill。
func localEntryByName(name string) (lockfile.LockedSkill, bool) {
	lock, err := lockfile.Load(mustGetwd())
	if err != nil {
		return lockfile.LockedSkill{}, false
	}
	return lock.Find(name)
}

// orDefaultChannel 补上默认通道：sync 这类内部调用不一定显式指定。
func orDefaultChannel(channel string) string {
	if channel == "" {
		return "stable"
	}
	return channel
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
