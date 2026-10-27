package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/agent"
	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

func newInstallCmd() *cobra.Command {
	var agentNames []string
	var scope, version, file string
	var locked, dryRun, replace bool

	cmd := &cobra.Command{
		Use:   "install <skill-id>",
		Short: "从 SkillMesh 注册中心安装指定版本的 Skill",
		Args:  cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			// 本地 zip：没签名、没走过服务端扫描，不进注册中心，
			// 只装到本机，来源写进锁文件（详细设计 §7.1 的来源分级）。
			if file != "" {
				if len(args) > 0 {
					return fmt.Errorf("--file 与 skill-id 不能同时用")
				}
				root, cleanup, err := extractToTemp(file)
				if err != nil {
					return err
				}
				defer cleanup()
				return installFromDir(localInstallOptions{
					Root: root, Source: "local:" + file, SourceType: "local",
					Provider: "local", Scope: scope, Agents: agentNames,
					UpdatePolicy: "manual", Replace: replace, DryRun: dryRun,
				})
			}
			if len(args) == 0 {
				return fmt.Errorf("要给 skill-id，或用 --file 指定本地 zip")
			}
			client, err := newClient(cmd.Context())
			if err != nil {
				return err
			}
			projectRoot := mustGetwd()
			lock, err := lockfile.Load(projectRoot)
			if err != nil {
				return err
			}
			fromLock, hasLock := lock.Find(args[0])
			// 参数可以是 Skill ID，也可以是锁文件里记的名字：
			// 后者支持 `install <名字> --version <旧版本>` 回滚，
			// 这样就不用再记一个 rollback 命令。
			skillID, agents, targetScope := args[0], agentNames, scope
			if hasLock && fromLock.SourceID != "" {
				skillID = fromLock.SourceID
				if !cmd.Flags().Changed("agent") && len(fromLock.Agents) > 0 {
					agents = fromLock.Agents
				}
				if !cmd.Flags().Changed("scope") && fromLock.Scope != "" {
					targetScope = fromLock.Scope
				}
			}
			if targetScope == "" {
				targetScope = "user"
			}

			versions, err := client.Versions(cmd.Context(), skillID)
			if err != nil {
				return err
			}
			// 不指定版本时装「注册中心口径的最新」：优先用注册中心给的
			// latest_version，拿不到就按版本号取最大的。
			latest := ""
			if version == "" && !(locked && hasLock) {
				latest = registryLatestVersion(cmd.Context(), client, skillID)
			}
			target, err := SelectVersion(versions, version, locked, fromLock, hasLock, latest)
			if err != nil {
				return err
			}
			// 装之前先摊开服务端扫描结论：内容没问题也该让人看见风险。
			printVersionRisks(target)

			data, err := client.DownloadArtifact(cmd.Context(), target.Artifact.ID)
			if err != nil {
				return err
			}
			if dryRun {
				fmt.Printf("演练：将从 %s@%s 安装 %d 字节到 %v\n",
					skillID, target.Version, len(data), agents)
				return nil
			}
			return installArtifact(cmd.Context(), client, projectRoot, installRequest{
				SkillID: skillID,
				Version: target,
				Agents:  agents,
				Scope:   targetScope,
				Replace: replace,
			})
		},
	}
	cmd.Flags().StringSliceVar(&agentNames, "agent", []string{"claude-code"}, "目标 Agent，可重复指定")
	cmd.Flags().StringVar(&scope, "scope", "user", "user 或 project")
	cmd.Flags().StringVar(&version, "version", "", "指定版本，默认取最新已发布")
	cmd.Flags().StringVar(&file, "file", "",
		"从本地 zip 安装（不进注册中心，来源只写锁文件）")
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

// artifactReport 是装之前该让人看见的结论：验签、许可证、可疑内容。
type artifactReport struct {
	Verdict string
	License string
	Risks   string
}

// fetchArtifact 下载并解出可安装的目录：结构校验与验签都在这里做完。
//
// 返回的目录在 cleanup 调用前有效。install 与 restore 走同一条路——
// 装进去的东西必须是服务端那份，验不过就不能进 Agent 目录。
func fetchArtifact(ctx context.Context, client *apiclient.Client,
	version apiclient.SkillVersion) (string, func(), artifactReport, error) {

	noop := func() {}
	data, err := client.DownloadArtifact(ctx, version.Artifact.ID)
	if err != nil {
		return "", noop, artifactReport{}, err
	}
	work, err := os.MkdirTemp("", "skillmesh-install-*")
	if err != nil {
		return "", noop, artifactReport{}, err
	}
	cleanup := func() { os.RemoveAll(work) }

	skillDir, err := installer.ExtractZip(data, work)
	if err != nil {
		cleanup()
		return "", noop, artifactReport{}, err
	}
	if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
		cleanup()
		return "", noop, artifactReport{}, err
	}
	verdict, err := VerifyArtifact(ctx, client, version, skillDir)
	if err != nil {
		cleanup()
		return "", noop, artifactReport{}, err
	}
	report := artifactReport{
		Verdict: verdict,
		License: skillscan.LicenseLine(skillDir),
		Risks:   riskText(skillDir),
	}
	return skillDir, cleanup, report, nil
}

// installIntoAgent 把已校验的目录装进一个 Agent 目录：落盘、打标记、登记安装。
// 返回装好的路径。
func installIntoAgent(ctx context.Context, client *apiclient.Client, skillDir string,
	req installRequest, agentName, projectRoot string) (string, error) {

	base, err := agent.Dir(Cfg, agentName, req.Scope, projectRoot)
	if err != nil {
		return "", err
	}
	name, err := skillDirName(skillDir)
	if err != nil {
		return "", err
	}
	installPath := filepath.Join(base, name)
	// 目标是链接时写它指向的真实目录（skills 管理器把库里的目录挂进 Agent 目录），
	// 链接本身保留：替换掉等于把用户的目录布局拆了。
	writePath := skillscan.WriteTarget(installPath)
	if err := CheckTarget(writePath, "skillmesh:"+req.SkillID, req.Replace); err != nil {
		return "", err
	}
	if err := installer.InstallAtomic(skillDir, writePath, true); err != nil {
		return "", err
	}
	if err := installer.MarkInstalled(writePath, "skillmesh:"+req.SkillID); err != nil {
		return "", err
	}
	// 登记到注册中心：回答「我在哪些机器上装了什么」。
	// 不登记的话 installations 永远是空的，这个功能等于没有。
	// 登记失败不影响安装结果——东西已经装好了——但要让人知道：
	// 装完了却没进注册中心，跨设备就看不到这条记录。
	if err := reportInstallation(ctx, client, req, agentName, installPath); err != nil {
		fmt.Fprintf(os.Stderr, "提示：安装登记没成功（%v），跨设备看不到这条记录\n", err)
	}
	fmt.Printf("已安装 %s@%s 到 %s\n", name, req.Version.Version, installPath)
	opLog().Record(oplog.ActionInstall, name, req.Version.Version,
		map[string]string{"agent": agentName, "path": installPath})
	return installPath, nil
}

// installArtifact 下载指定版本、校验、原子装进各 Agent 目录并写锁文件。
// install 和 rollback 走同一条路——回滚就是装一个更早的不可变版本。
func installArtifact(ctx context.Context, client *apiclient.Client, projectRoot string,
	req installRequest) error {

	skillDir, cleanup, report, err := fetchArtifact(ctx, client, req.Version)
	if err != nil {
		return err
	}
	defer cleanup()
	fmt.Printf("%s 验签：%s\n", req.Version.Version, report.Verdict)
	fmt.Println(report.License)
	if report.Risks != "" {
		fmt.Println(report.Risks)
	}

	name, err := skillDirName(skillDir)
	if err != nil {
		return err
	}

	// 装到多个 Agent 时记第一个的路径：锁文件一条对应一个安装，
	// 目标路径记主要那个（设计文档 §9.1）。
	installedPath := ""
	for _, agentName := range req.Agents {
		path, err := installIntoAgent(ctx, client, skillDir, req, agentName, projectRoot)
		if err != nil {
			return err
		}
		if installedPath == "" {
			installedPath = path
		}
	}
	if err := RecordInstall(InstallRecord{
		ProjectRoot:   projectRoot,
		Name:          name,
		SkillID:       req.SkillID,
		Target:        req.Version,
		Scope:         req.Scope,
		Agents:        req.Agents,
		InstalledHash: InstalledDigest(skillDir),
		TargetPath:    installedPath,
	}); err != nil {
		return err
	}
	// 上报安装完成：服务端只记自己知道的特权动作，本地发生了什么它看不见。
	// 上报失败不该影响安装结果——东西已经装好了。
	_ = client.ReportAuditEvent(ctx, "install.completed", req.SkillID, "success",
		map[string]string{"name": name, "version": req.Version.Version})
	fmt.Printf("锁文件 %s\n", lockfile.FileName)
	return nil
}

// reportInstallation 把这次安装登记到注册中心：回答「我在哪些机器上装了什么」。
//
// 登记失败不影响安装结果——东西已经装好了——但要让调用方知道：
// 装完了却没进注册中心，跨设备就看不到这条记录。
func reportInstallation(ctx context.Context, client *apiclient.Client,
	req installRequest, agentName, target string) error {

	deviceID, err := client.DeviceID(ctx)
	if err != nil {
		return err
	}
	_, err = client.RegisterInstallation(ctx, apiclient.RegisterInstallationInput{
		SkillID:          req.SkillID,
		DeviceID:         deviceID,
		Agent:            agentName,
		InstallScope:     req.Scope,
		TargetPath:       target,
		InstalledVersion: req.Version.Version,
		InstalledSHA256:  artifactSHA256(req.Version),
		// 刚装完的内容就是制品里的那份，本机还没被改过。
		LocalModified: false,
	})
	return err
}

func artifactSHA256(version apiclient.SkillVersion) string {
	if version.Artifact == nil {
		return ""
	}
	return version.Artifact.SHA256
}

// RecordInstall 把注册中心安装写进锁文件。
// 不写的话 list 看不到它，CI 的 --locked 也无从校验（设计文档 §9.2）。
// InstallRecord 是一次安装要记进锁文件的内容。
type InstallRecord struct {
	ProjectRoot   string
	Name          string
	SkillID       string
	Target        apiclient.SkillVersion
	Scope         string
	Agents        []string
	InstalledHash string
	// TargetPath 是装到哪：Agent 目录改了以后，按配置重算的位置
	// 与当初装的对不上（设计文档 §9.1 要求记「目标路径」）。
	TargetPath string
}

// RecordInstall 把这次安装写进锁文件。
func RecordInstall(record InstallRecord) error {
	lock, err := lockfile.Load(record.ProjectRoot)
	if err != nil {
		return err
	}
	entry := lockfile.LockedSkill{
		Name:          record.Name,
		SourceID:      record.SkillID,
		RefType:       "version",
		Ref:           record.Target.Version,
		Version:       record.Target.Version,
		Scope:         record.Scope,
		InstalledHash: record.InstalledHash,
		Agents:        record.Agents,
		TargetPath:    record.TargetPath,
	}
	if record.Target.Artifact != nil {
		entry.SHA256 = record.Target.Artifact.SHA256
	}
	lock.Upsert(entry)
	return lock.Save(record.ProjectRoot)
}

// VersionStatus 查一个版本的状态；版本不存在返回空串。
func VersionStatus(versions apiclient.VersionPage, want string) (string, bool) {
	for _, candidate := range versions.Items {
		if candidate.Version == want {
			return candidate.Status, true
		}
	}
	return "", false
}

// PickVersion 选要装的版本。restore/sync/conflict/rollback 还在用它：
// 它们拿不到注册中心的 latest_version，「最新」按版本号算。
func PickVersion(versions apiclient.VersionPage, want string, locked bool,
	fromLock lockfile.LockedSkill, hasLock bool) (apiclient.SkillVersion, error) {
	return SelectVersion(versions, want, locked, fromLock, hasLock, "")
}

// SelectVersion 选要装的版本。--locked 时必须与锁文件完全一致，
// 否则 CI 会静默装到漂移的版本（详细设计 §3.10）。
//
// latest 是注册中心给的 latest_version（空串表示没拿到）：「最新」优先用它，
// 拿不到就按版本号取最大的——与注册中心同一个「最新」口径。
func SelectVersion(versions apiclient.VersionPage, want string, locked bool,
	fromLock lockfile.LockedSkill, hasLock bool, latest string) (apiclient.SkillVersion, error) {
	if locked && hasLock {
		want = fromLock.Version
	}
	if want == "" {
		return pickLatestPublished(versions, latest)
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
	if status, revoked := VersionStatus(versions, want); revoked {
		return apiclient.SkillVersion{}, fmt.Errorf("版本 %s 已被撤回，不能安装（当前状态 %s）", want, status)
	}
	return apiclient.SkillVersion{}, fmt.Errorf("找不到已发布的版本: %s", want)
}

// pickLatestPublished 挑「最新已发布」的那一版。
//
// latest 是注册中心给的 latest_version，可以是空串：非空就用它；
// 它不在已发布列表里就当没给，退回按版本号取最大的。
//
// 版本列表按创建时间倒序排（服务端 findBySkillIdOrderByCreatedAtDesc），
// 所以「列表里第一个」是刚发出来的那个——可能是旧版本号，不能当「最新」。
func pickLatestPublished(versions apiclient.VersionPage, latest string) (apiclient.SkillVersion, error) {
	if latest != "" {
		for _, candidate := range versions.Items {
			if candidate.Version == latest && candidate.Status == "published" &&
				candidate.Artifact != nil {
				return candidate, nil
			}
		}
	}
	best := apiclient.SkillVersion{}
	found := false
	for _, candidate := range versions.Items {
		// 没有制品的版本装不了（下载会直接失败），跳过。
		if candidate.Status != "published" || candidate.Artifact == nil {
			continue
		}
		if !found || VersionGreater(candidate.Version, best.Version) {
			best, found = candidate, true
		}
	}
	if !found {
		return apiclient.SkillVersion{}, fmt.Errorf("该 Skill 没有可安装的已发布版本")
	}
	return best, nil
}

// registryLatestVersion 取注册中心给的 latest_version。
//
// 注册中心的「最新」是版本号最大的那个（skills.latest_version），
// 跟列表顺序不是一回事。取不到就返回空串，调用方退回按版本号比。
func registryLatestVersion(ctx context.Context, client *apiclient.Client, skillID string) string {
	skill, err := client.Skill(ctx, skillID)
	if err != nil || skill.LatestVersion == nil {
		return ""
	}
	return *skill.LatestVersion
}

// VersionGreater 版本号比大小：对齐服务端 VersionOrder.isGreater——
// 点分字段按数字比（1.10.0 > 1.9.0），缺的字段当 0，字段不是数字就按字典序比。
func VersionGreater(candidate, current string) bool {
	if candidate == "" {
		return false
	}
	if current == "" {
		return true
	}
	left, right := splitVersion(candidate), splitVersion(current)
	for i := 0; i < len(left) || i < len(right); i++ {
		cmp := compareSegment(versionField(left, i), versionField(right, i))
		if cmp != 0 {
			return cmp > 0
		}
	}
	return false
}

// splitVersion 切版本号。末尾的空段不算（对齐 Java split 的语义）。
func splitVersion(version string) []string {
	parts := strings.Split(version, ".")
	for len(parts) > 0 && parts[len(parts)-1] == "" {
		parts = parts[:len(parts)-1]
	}
	return parts
}

// versionField 取第 i 段，缺了当 0（1.0 与 1.0.0 一样大）。
func versionField(parts []string, i int) string {
	if i < len(parts) {
		return parts[i]
	}
	return "0"
}

// compareSegment 数字段按数字比，不是数字就按字典序比——
// 版本号写歪了也别把「最新」弄丢。
func compareSegment(left, right string) int {
	l, lErr := strconv.ParseInt(left, 10, 64)
	r, rErr := strconv.ParseInt(right, 10, 64)
	if lErr == nil && rErr == nil {
		switch {
		case l < r:
			return -1
		case l > r:
			return 1
		default:
			return 0
		}
	}
	return strings.Compare(left, right)
}
