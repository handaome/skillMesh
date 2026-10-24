package cmd

// 巡检本机磁盘：算安装记录的真实状态，回写服务端。
//
// 服务端只存事实，不替客户端判断（详细设计 §11）：客户端不推，
// installations 里的 status 就永远是 active，local_modified 也永远是 false。

import (
	"context"
	"fmt"
	"os"
	"path/filepath"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/lockfile"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
)

// installFacts 判定一条安装记录要的全部事实。
type installFacts struct {
	Exists   bool   // 目标目录还在不在
	HasSkill bool   // 目录里还有没有 SKILL.md
	Digest   string // 本机内容摘要

	RecordedFound   bool   // 服务端记的那一版在云端找得到吗
	RecordedVersion string // 服务端记的版本号
	RecordedStatus  string // 那一版在云端的状态
	Baseline        string // 那一版的内容摘要（"" = 老版本没清单，比不了）
	LatestVersion   string // 云端最新已发布版
	LatestDigest    string // 那一版的内容摘要
	DiskVersion     string // 本机内容正好等于哪个已发布版（"" = 不等于任何一个）
}

// installOutcome 是一条安装记录巡检出来的真实状态。
//
// LocalModified 是指针：比不了的时候是 nil，表示「别动服务端记的」。
type installOutcome struct {
	Status           string
	LocalModified    *bool
	InstalledVersion string // 要回写的版本号（"" = 不改）
	Note             string
}

// inspectInstall 巡检一条安装记录：本机磁盘 + 云端版本列表。
func inspectInstall(ctx context.Context, client *apiclient.Client,
	row apiclient.Installation, cache map[string]apiclient.VersionPage) (installOutcome, error) {

	facts := readInstallFacts(row.TargetPath)
	if facts.Exists && facts.HasSkill {
		versions, err := skillVersions(ctx, client, row.SkillID, cache)
		if err != nil {
			return installOutcome{}, err
		}
		readCloudFacts(&facts, versions, row)
	}
	return judgeInstall(facts), nil
}

// readInstallFacts 读本机目录：还在不在、Skill 内容还在不在、内容摘要多少。
//
// 摘要按 skillscan 的口径算（跳过隐藏文件与凭据文件），跟制品清单一个算法，
// 两边才可比。
func readInstallFacts(path string) installFacts {
	facts := installFacts{}
	info, err := os.Stat(path)
	if err != nil || !info.IsDir() {
		return facts
	}
	facts.Exists = true
	if _, err := os.Stat(filepath.Join(path, "SKILL.md")); err != nil {
		return facts
	}
	facts.HasSkill = true
	facts.Digest, _ = skillscan.DirDigest(path)
	return facts
}

// readCloudFacts 从云端版本列表里取出判定要的事实。
//
// 基线取「服务端记的那一版」：本机内容跟它对不上，就是本地被改过。
// 另外记下最新已发布版：它跟基线不一样，说明云端也动了。
func readCloudFacts(facts *installFacts, versions apiclient.VersionPage,
	row apiclient.Installation) {

	if recorded := recordedVersion(versions, row); recorded != nil {
		facts.RecordedFound = true
		facts.RecordedVersion = recorded.Version
		facts.RecordedStatus = recorded.Status
		facts.Baseline = contentDigest(*recorded)
	}
	// 最新已发布版按 install 选版本的口径取（版本号最大的已发布版）。
	if latest, err := pickVersion(versions, "", false, lockfile.LockedSkill{}, false); err == nil {
		facts.LatestVersion = latest.Version
		facts.LatestDigest = contentDigest(latest)
	}
	facts.DiskVersion = diskVersionOf(versions, facts.Digest)
}

// contentDigest 一个版本的内容摘要。老版本没有清单时给不出摘要（空串）。
func contentDigest(version apiclient.SkillVersion) string {
	if len(version.Manifest) == 0 {
		return ""
	}
	return remoteDigest(version)
}

// diskVersionOf 本机内容正好等于哪个已发布版本（版本号最大的那个）。
//
// 空串表示本机内容不等于任何一个已发布版本——多半是用户手改过。
func diskVersionOf(versions apiclient.VersionPage, diskDigest string) string {
	if diskDigest == "" {
		return ""
	}
	match := ""
	for _, item := range versions.Items {
		if item.Status != "published" || contentDigest(item) != diskDigest {
			continue
		}
		if match == "" || versionGreater(item.Version, match) {
			match = item.Version
		}
	}
	return match
}

// recordedVersion 找出服务端记的那一版：先按版本号，版本号对不上再按制品摘要。
func recordedVersion(versions apiclient.VersionPage,
	row apiclient.Installation) *apiclient.SkillVersion {

	for i := range versions.Items {
		if row.InstalledVersion != "" && versions.Items[i].Version == row.InstalledVersion {
			return &versions.Items[i]
		}
	}
	for i := range versions.Items {
		item := versions.Items[i]
		if row.InstalledSHA256 != "" && item.Artifact != nil &&
			item.Artifact.SHA256 == row.InstalledSHA256 {
			return &item
		}
	}
	return nil
}

// judgeInstall 按事实判定一条安装记录的真实状态。
//
// 本机磁盘是硬事实：目录没了就是 uninstalled，SKILL.md 没了就是 missing。
// 本机内容跟服务端记的那一版对不上，就是本地改过（local_modified）；
// 云端同时还有内容不同的已发布版本，就是两边都动了（conflict）。
// 服务端记的那一版在云端已经不能用了，就是 blocked。
func judgeInstall(facts installFacts) installOutcome {
	out := installOutcome{Status: "active"}
	switch {
	case !facts.Exists:
		out.Status = "uninstalled"
		out.Note = "目标目录不在了"
		out.LocalModified = boolPointer(false)
		return out
	case !facts.HasSkill:
		out.Status = "missing"
		out.Note = "目录还在，SKILL.md 不见了"
		out.LocalModified = boolPointer(false)
		return out
	}
	// 本机内容正好是某个已发布版本：盘上装的就是那一版，
	// 服务端记的版本落后了 —— 版本号报准，不算本地改过。
	if facts.DiskVersion != "" && facts.DiskVersion != facts.RecordedVersion {
		out.Status = "active"
		out.InstalledVersion = facts.DiskVersion
		out.LocalModified = boolPointer(false)
		out.Note = "本机内容就是已发布的 " + facts.DiskVersion
		return out
	}
	modified := false
	comparable := facts.Baseline != "" && facts.Digest != ""
	if comparable {
		modified = facts.Digest != facts.Baseline
		out.LocalModified = boolPointer(modified)
	}
	switch {
	case !facts.RecordedFound:
		out.Status = "blocked"
		out.Note = "服务端记的那一版在云端已经找不到了"
	case comparable && modified && facts.LatestDigest != "" && facts.LatestDigest != facts.Baseline:
		out.Status = "conflict"
		out.Note = "本机内容改过，云端也有内容不同的已发布版本"
	case facts.RecordedStatus != "published":
		out.Status = "blocked"
		out.Note = "服务端记的那一版已被 " + facts.RecordedStatus
	case modified:
		out.Note = "本机内容与服务端记的那一版不一致"
	}
	return out
}

// boolPointer 取一个 bool 的指针：false 也要能发出去（把「本地改过」抹掉）。
func boolPointer(value bool) *bool {
	return &value
}

// checkInstallations 巡检本机的安装记录，把真实状态写回服务端。
//
// 只动本机的记录：别的机器上的目录在这台机器上根本不存在，
// 按「目录不在」去判会把别人的记录写成已卸载。
func checkInstallations(ctx context.Context, client *apiclient.Client,
	rows []apiclient.Installation) ([]apiclient.Installation, []string) {

	notes := []string{}
	if len(rows) == 0 {
		return rows, notes
	}
	deviceID, err := client.DeviceID(ctx)
	if err != nil {
		return rows, append(notes, "取本机设备 ID 失败，巡检没做："+err.Error())
	}
	cache := map[string]apiclient.VersionPage{}
	for i := range rows {
		row := rows[i]
		if !belongsToThisMachine(row, deviceID) {
			continue
		}
		outcome, err := inspectInstall(ctx, client, row, cache)
		if err != nil {
			notes = append(notes, fmt.Sprintf("%s：%v", short(row.SkillID), err))
			continue
		}
		if err := pushInstallOutcome(ctx, client, row, outcome); err != nil {
			notes = append(notes, fmt.Sprintf("%s：写回失败（%v）", short(row.SkillID), err))
			continue
		}
		if outcome.Status != row.Status || outcome.InstalledVersion != "" {
			notes = append(notes, fmt.Sprintf("%s：%s → %s（%s）",
				short(row.SkillID), row.Status, outcome.Status, outcome.Note))
		}
		row.Status = outcome.Status
		if outcome.LocalModified != nil {
			row.LocalModified = *outcome.LocalModified
		}
		if outcome.InstalledVersion != "" {
			row.InstalledVersion = outcome.InstalledVersion
		}
		rows[i] = row
	}
	return rows, notes
}

// belongsToThisMachine 这条记录是不是这台机器的。
//
// 别的机器上的目录在这台机器上根本不存在，按「目录不在」去判会把别人的记录
// 写成已卸载。设备 ID 没记的老记录没法分清，只在目录就在本机时才算。
func belongsToThisMachine(row apiclient.Installation, deviceID string) bool {
	if row.DeviceID != "" {
		return row.DeviceID == deviceID
	}
	_, err := os.Stat(row.TargetPath)
	return err == nil
}

// pushInstallOutcome 把巡检结果写回服务端。只写变了的字段。
func pushInstallOutcome(ctx context.Context, client *apiclient.Client,
	row apiclient.Installation, outcome installOutcome) error {

	in := apiclient.UpdateInstallationInput{}
	if outcome.Status != "" && outcome.Status != row.Status {
		in.Status = outcome.Status
	}
	if outcome.LocalModified != nil && *outcome.LocalModified != row.LocalModified {
		in.LocalModified = outcome.LocalModified
	}
	if outcome.InstalledVersion != "" && outcome.InstalledVersion != row.InstalledVersion {
		in.InstalledVersion = outcome.InstalledVersion
	}
	if in.Status == "" && in.LocalModified == nil && in.InstalledVersion == "" {
		return nil
	}
	_, err := client.UpdateInstallation(ctx, row.ID, in)
	return err
}

// skillVersions 取某个 Skill 的版本列表；同一个 Skill 只取一次。
func skillVersions(ctx context.Context, client *apiclient.Client, skillID string,
	cache map[string]apiclient.VersionPage) (apiclient.VersionPage, error) {

	if page, ok := cache[skillID]; ok {
		return page, nil
	}
	page, err := client.Versions(ctx, skillID)
	if err != nil {
		return page, fmt.Errorf("查不到云端版本，这条没判：%w", err)
	}
	cache[skillID] = page
	return page, nil
}

// filterByStatus 按状态过滤（巡检后按算出来的状态筛，不是服务端记的）。
func filterByStatus(rows []apiclient.Installation, status string) []apiclient.Installation {
	if status == "" {
		return rows
	}
	kept := make([]apiclient.Installation, 0, len(rows))
	for _, row := range rows {
		if row.Status == status {
			kept = append(kept, row)
		}
	}
	return kept
}
