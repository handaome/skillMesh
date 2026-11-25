package cmd

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/gitx"
	"github.com/handaome/skillmesh/apps/cli/internal/installer"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/oplog"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/spf13/cobra"
)

func newInstallCmd() *cobra.Command {
	var version, file, path, ref string
	var locked, replace bool

	cmd := &cobra.Command{
		Use:   "install <skill-id>",
		Short: "从 SkillMesh 注册中心安装指定版本的 Skill",
		Long: "来源有三种：注册中心上的 Skill、锁文件里登记过的本地来源、Git 仓库地址。\n" +
			"给 Git 地址时配 --path 指到仓库里的子目录。",
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runInstall(cmd, args, installOptions{
				Version: version, File: file, Path: path, Ref: ref,
				Locked: locked, Replace: replace,
			})
		},
	}
	cmd.Flags().StringVar(&version, "version", "", "指定版本，默认取最新已发布")
	cmd.Flags().StringVar(&file, "file", "",
		"从本地 zip 安装（不进注册中心，来源只写锁文件）")
	cmd.Flags().StringVar(&path, "path", "", "Git 仓库里 Skill 所在的子目录")
	cmd.Flags().StringVar(&ref, "ref", "", "Git 分支、标签或 commit")
	cmd.Flags().BoolVar(&locked, "locked", false, "严格按锁文件校验，不符则失败")
	cmd.Flags().BoolVar(&replace, "replace", false, "覆盖不受 SkillMesh 管理的同名目录")
	return supportsDryRun(cmd)
}

// installOptions 是 install 命令的全部参数。
type installOptions struct {
	Version string
	File    string
	Path    string
	Ref     string
	Locked  bool
	Replace bool
}

// runInstall 按来源分流：本地 zip、Git 仓库、注册中心。
func runInstall(cmd *cobra.Command, args []string, opts installOptions) error {
	// 本地 zip：没签名、没走过服务端扫描，不进注册中心，
	// 只装到本机，来源写进锁文件（详细设计 §7.1 的来源分级）。
	if opts.File != "" {
		if len(args) > 0 {
			return fmt.Errorf("--file 与 skill-id 不能同时用")
		}
		return installFromLocalZip(opts)
	}
	if len(args) == 0 {
		return fmt.Errorf("要给 skill-id、仓库地址，或用 --file 指定本地 zip")
	}
	// Git 地址：直接从仓库装（设计文档 §3.5「支持来源类型」第三项）。
	if repo, ok := gitRepoURL(args[0]); ok {
		return installFromGit(cmd.Context(), gitInstallOptions{
			Repository: repo, Ref: opts.Ref, Path: opts.Path,
			Scope: scopeOr("user"), Agents: agentNamesOr(defaultAgent),
			Replace: opts.Replace, DryRun: globalDryRun,
		})
	}
	return installFromRegistry(cmd, args[0], opts)
}

// installFromLocalZip 装本地 zip：解到临时目录，按本地来源登记进锁文件。
func installFromLocalZip(opts installOptions) error {
	root, cleanup, err := extractToTemp(opts.File)
	if err != nil {
		return err
	}
	defer cleanup()
	return installFromDir(localInstallOptions{
		Root: root, Source: "local:" + opts.File, SourceType: "local",
		Provider: "local", Scope: scopeOr("user"),
		Agents:       agentNamesOr(defaultAgent),
		UpdatePolicy: policyManual, Replace: opts.Replace, DryRun: globalDryRun,
	})
}

// installSource 是这次安装解析出来的来源。
type installSource struct {
	SkillID string
	Agents  []string
	Scope   string
	// Git 非空表示按锁文件里登记的本地来源重装，不走注册中心。
	Git      *gitInstallOptions
	FromLock lockfile.LockedSkill
	HasLock  bool
}

// resolveInstallSource 决定装哪个 Skill、用哪些 Agent 与 scope。
//
// 参数可以是 Skill ID，也可以是锁文件里记的名字：
// 后者支持 `install <名字> --version <旧版本>` 回滚，
// 这样就不用再记一个 rollback 命令。
func resolveInstallSource(cmd *cobra.Command, lock lockfile.Lock, name string) installSource {
	src := installSource{
		SkillID: name, Agents: agentNamesOr(defaultAgent), Scope: scopeOr("user"),
	}
	fromLock, hasLock := lock.Find(name)
	src.FromLock, src.HasLock = fromLock, hasLock
	if !hasLock {
		return src
	}
	// 先认注册中心 id，再认仓库地址：一条记录两个都写了时以注册中心 id 为准。
	if fromLock.SourceID != "" {
		src.SkillID = fromLock.SourceID
		// 没显式给 --agent / --scope 时，沿用上次装的那次。
		if !cmd.Flags().Changed("agent") && len(fromLock.Agents) > 0 {
			src.Agents = fromLock.Agents
		}
		if !cmd.Flags().Changed("scope") && fromLock.Scope != "" {
			src.Scope = fromLock.Scope
		}
		return src
	}
	if fromLock.Repository != "" {
		// 已登记的本地来源：按锁文件里记的仓库、ref 与子目录重装一份。
		// 锁文件记的就是上次解析到的那个版本，重装不会跑到别处去。
		src.Git = &gitInstallOptions{
			Repository: fromLock.Repository, Ref: fromLock.Ref, Path: fromLock.Path,
			Scope: scopeOr(fromLock.Scope), Agents: agentNamesOr(defaultAgent),
		}
	}
	return src
}

// installFromRegistry 从注册中心安装：选版本、下载制品、装到各 Agent 目录。
func installFromRegistry(cmd *cobra.Command, name string, opts installOptions) error {
	ctx := cmd.Context()
	client, err := newClient(ctx)
	if err != nil {
		return err
	}
	projectRoot := projectRootDir()
	lock, err := lockfile.Load(projectRoot)
	if err != nil {
		return err
	}
	src := resolveInstallSource(cmd, lock, name)
	if src.Git != nil {
		src.Git.Replace = opts.Replace
		src.Git.DryRun = globalDryRun
		return installFromGit(ctx, *src.Git)
	}
	versions, err := client.Versions(ctx, src.SkillID)
	if err != nil {
		return err
	}
	// 不指定版本时装「注册中心口径的最新」：优先用注册中心给的
	// latest_version，拿不到就按版本号取最大的。
	latest := ""
	if opts.Version == "" && !(opts.Locked && src.HasLock) {
		latest = registryLatestVersion(ctx, client, src.SkillID)
	}
	target, err := SelectVersion(versions, opts.Version, opts.Locked,
		src.FromLock, src.HasLock, latest)
	if err != nil {
		return err
	}
	// 装之前先摊开服务端扫描结论：内容没问题也该让人看见风险。
	printVersionRisks(target)
	// 演练不下载制品：字节数用服务端记的大小，别为看一眼大小把整包拉下来。
	if globalDryRun {
		fmt.Printf("演练：将从 %s@%s 安装 %d 字节到 %v\n",
			src.SkillID, target.Version, target.Artifact.SizeBytes, src.Agents)
		return nil
	}
	return installArtifact(ctx, client, projectRoot, lock, installRequest{
		SkillID: src.SkillID,
		Version: target,
		Agents:  src.Agents,
		Scope:   src.Scope,
		Replace: opts.Replace,
	})
}

// gitInstallOptions 是「从 Git 仓库装」这一路的参数。
type gitInstallOptions struct {
	Repository string
	Ref        string
	Path       string
	Scope      string
	Agents     []string
	Replace    bool
	DryRun     bool
}

// installFromGit 克隆仓库并把里面的 Skill 装到目标 Agent 目录。
//
// 来源记进锁文件（仓库、ref、子目录、commit），下次 update 能照着它查更新。
func installFromGit(ctx context.Context, opts gitInstallOptions) error {
	work, err := os.MkdirTemp("", "skillmesh-install-git-*")
	if err != nil {
		return err
	}
	defer os.RemoveAll(work)

	root := filepath.Join(work, "repo")
	commit, err := gitx.Clone(ctx, opts.Repository, opts.Ref, root)
	if err != nil {
		return err
	}
	return installFromDir(localInstallOptions{
		Root: root, Source: opts.Repository, SourceType: "git",
		Repository: opts.Repository, Provider: providerOf(opts.Repository),
		// RefType 不在这里给：clone 之后才知道取到的是分支还是标签，
		// 交给 installFromDir 按仓库实际状态判定（设计文档 §7.1）。
		Ref: opts.Ref, Commit: commit,
		Subpath: opts.Path, Scope: opts.Scope, Agents: opts.Agents,
		UpdatePolicy: policyManual, Replace: opts.Replace, DryRun: opts.DryRun,
	})
}

// gitRepoURL 判断这个参数是不是仓库地址，顺手把地址规范化好。
//
// 靠 NormalizeURL 判：简写（owner/repo）、本地路径、非法协议它都拒绝，
// 能过的就是远程仓库地址。规范化只做一次，调用方拿到的就是能直接 clone 的地址。
func gitRepoURL(raw string) (string, bool) {
	repo, err := gitx.NormalizeURL(raw)
	if err != nil {
		return "", false
	}
	return repo, true
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
// 目录解在 work 指向的临时目录里，调用方负责清掉它（清空路径是空操作）。
// install 与 restore 走同一条路——装进去的东西必须是服务端那份，
// 验不过就不能进 Agent 目录。
func fetchArtifact(ctx context.Context, client *apiclient.Client,
	version apiclient.SkillVersion) (skillDir string, work string, report artifactReport, err error) {

	data, err := client.DownloadArtifact(ctx, version.Artifact.ID)
	if err != nil {
		return "", "", artifactReport{}, err
	}
	work, err = os.MkdirTemp("", "skillmesh-install-*")
	if err != nil {
		return "", "", artifactReport{}, err
	}
	skillDir, err = installer.ExtractZip(data, work)
	if err != nil {
		return "", work, artifactReport{}, err
	}
	if err := skillscan.ValidateDir(skillDir, skillscan.DefaultLimits); err != nil {
		return "", work, artifactReport{}, err
	}
	verdict, err := VerifyArtifact(ctx, client, version, skillDir)
	if err != nil {
		return "", work, artifactReport{}, err
	}
	report = artifactReport{
		Verdict: verdict,
		License: skillscan.LicenseLine(skillDir),
		Risks:   riskText(skillDir),
	}
	return skillDir, work, report, nil
}

// installIntoAgent 把已校验的目录装进一个 Agent 目录：落盘、打标记、登记安装。
// 返回装好的路径。
func installIntoAgent(ctx context.Context, client *apiclient.Client, skillDir string,
	req installRequest, agentName, projectRoot string) (string, error) {

	name, err := skillDirName(skillDir)
	if err != nil {
		return "", err
	}
	installPath, err := installTargetOf(agentName, req.Scope, projectRoot, skillDir, name)
	if err != nil {
		return "", err
	}
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
//
// lock 由调用方读好传进来（lockfile.Load）：判这次安装算升级还是回滚、
// 装完把它写回去，用的是同一份锁状态，不用读第二遍。
func installArtifact(ctx context.Context, client *apiclient.Client, projectRoot string,
	lock lockfile.Lock, req installRequest) error {

	// 审计动作要在动锁文件之前定：升级、回滚还是普通安装，
	// 看的是「装之前锁文件里记的是哪一版」（设计文档 §9.1、§10）。
	action := installAuditAction(lock, req.SkillID, req.Version.Version)

	skillDir, work, report, err := fetchArtifact(ctx, client, req.Version)
	defer os.RemoveAll(work)
	if err != nil {
		return err
	}
	fmt.Printf("%s 验签：%s\n", req.Version.Version, report.Verdict)
	fmt.Println(report.License)
	if report.Risks != "" {
		fmt.Println(report.Risks)
	}

	name, err := skillDirName(skillDir)
	if err != nil {
		return err
	}

	// 装到多个 Agent 时每个都记一条路径：锁文件一条对应一个安装，
	// 目标路径按 Agent 分开记（设计文档 §9.1）。
	installedPaths := map[string]string{}
	for _, agentName := range req.Agents {
		path, err := installIntoAgent(ctx, client, skillDir, req, agentName, projectRoot)
		if err != nil {
			return err
		}
		installedPaths[agentName] = path
	}
	if err := writeInstall(lock, InstallRecord{
		ProjectRoot:   projectRoot,
		Name:          name,
		SkillID:       req.SkillID,
		Target:        req.Version,
		Scope:         req.Scope,
		Agents:        req.Agents,
		InstalledHash: InstalledDigest(skillDir),
		TargetPaths:   installedPaths,
	}); err != nil {
		return err
	}
	// 上报安装结果：服务端只记自己知道的特权动作，本地发生了什么它看不见。
	// 上报失败不该影响安装结果——东西已经装好了。
	_ = client.ReportAuditEvent(ctx, action, req.SkillID, "success",
		map[string]string{"name": name, "version": req.Version.Version})
	fmt.Printf("锁文件 %s\n", lockfile.FileName)
	return nil
}

// installAuditAction 判这次安装该报哪条审计动作。
//
// 锁文件里记着上一版：版本号更大是升级，更小是回滚（设计文档 §9.1「回滚也必须写
// 审计日志」、§10 审计要记「升级」）。一律报 install.completed，升级与回滚就都
// 混成一条流水，事后查不出来。
func installAuditAction(lock lockfile.Lock, skillID, version string) string {
	if skillID == "" {
		return "install.completed"
	}
	previous, ok := lock.FindBySourceID(skillID)
	if !ok || previous.Version == "" || previous.Version == version {
		return "install.completed"
	}
	if VersionGreater(version, previous.Version) {
		return "install.upgraded"
	}
	return "install.rolled_back"
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
	// TargetPaths 是装到哪：Agent 目录改了以后，按配置重算的位置
	// 与当初装的对不上（设计文档 §9.1 要求记「目标路径」）。
	// 一个 Agent 一条：装到几个 Agent 就有几个路径。
	TargetPaths map[string]string
}

// RecordInstall 把这次安装写进锁文件。
func RecordInstall(record InstallRecord) error {
	lock, err := lockfile.Load(record.ProjectRoot)
	if err != nil {
		return err
	}
	return writeInstall(lock, record)
}

// writeInstall 把一次安装写进已经读好的锁文件。
//
// 读锁文件只做一次：装之前要拿它判升级还是回滚，装完又要写它。
// 分成两个函数，读的那次和写的那次就能共用同一份内存里的锁。
func writeInstall(lock lockfile.Lock, record InstallRecord) error {
	entry := lockfile.LockedSkill{
		Name:          record.Name,
		SourceID:      record.SkillID,
		RefType:       "version",
		Ref:           record.Target.Version,
		Version:       record.Target.Version,
		Scope:         record.Scope,
		InstalledHash: record.InstalledHash,
		Agents:        record.Agents,
		TargetPaths:   record.TargetPaths,
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
