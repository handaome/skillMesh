package cmd

import (
	"context"
	"fmt"
	"sort"
	"sync"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

// 云端制品体检。
//
// 服务端的「只收 zip」闸门上线之前，发布出去的制品不一定是 zip：
// 有人把 tar.gz 当成 zip 传上去，发布成功、扫描也过，
// 等别人真去装的时候才炸——那时候错的是装的人。
// 客户端主动查一遍，发现坏的就说清楚，并提示撤回。

// zipMagic 是 zip 文件的开头四个字节。
const zipMagic = "PK\x03\x04"

// brokenArtifact 是装不了的云端制品。
type brokenArtifact struct {
	Skill   apiclient.Skill
	Version apiclient.SkillVersion
	Reason  string
}

// checkCloudArtifacts 查我名下已发布版本的制品。
//
// 只看已发布（published）的版本：草稿还没对外，坏了改了就行。
// 拿不到某个版本的信息就跳过，不当成坏——体检不能凭猜报错。
//
// 各个 Skill 并着查：几十个 Skill 串行查要二十多秒，
// 同步是日常命令，不能为了体检慢一半。
func checkCloudArtifacts(ctx context.Context, client *apiclient.Client) ([]brokenArtifact, error) {
	skills, err := mySkills(ctx, client)
	if err != nil {
		return nil, err
	}
	type outcome struct {
		item brokenArtifact
		ok   bool
	}
	results := make(chan outcome, len(skills))
	sem := make(chan struct{}, artifactCheckConcurrency)
	var wg sync.WaitGroup
	for _, skill := range skills {
		wg.Add(1)
		go func(skill apiclient.Skill) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()
			item, ok := checkOneArtifact(ctx, client, skill)
			results <- outcome{item: item, ok: ok}
		}(skill)
	}
	wg.Wait()
	close(results)
	var broken []brokenArtifact
	for result := range results {
		if result.ok {
			broken = append(broken, result.item)
		}
	}
	// 并发查完顺序是乱的：按名字排一遍，报错才 stably。
	sort.Slice(broken, func(i, j int) bool {
		if broken[i].Skill.Slug != broken[j].Skill.Slug {
			return broken[i].Skill.Slug < broken[j].Skill.Slug
		}
		return broken[i].Version.Version < broken[j].Version.Version
	})
	return broken, nil
}

// artifactCheckConcurrency 是体检时的并发数。
//
// 服务端有写接口限流，读接口也有；八路并发是这个量级下的稳妥值。
const artifactCheckConcurrency = 8

// checkOneArtifact 查一个 Skill 的已发布版本。第二个返回值说「确实坏了」。
func checkOneArtifact(ctx context.Context, client *apiclient.Client,
	skill apiclient.Skill) (brokenArtifact, bool) {

	versions, err := client.Versions(ctx, skill.ID)
	if err != nil {
		return brokenArtifact{}, false
	}
	for _, version := range versions.Items {
		if version.Status != "published" || version.Artifact == nil {
			continue
		}
		head, err := client.ArtifactHead(ctx, version.Artifact.ID, len(zipMagic))
		if err != nil {
			continue
		}
		if isZipArtifact(head) {
			continue
		}
		return brokenArtifact{
			Skill: skill, Version: version,
			Reason: "制品不是合法的 zip（开头是 " + describeHead(head) + "）",
		}, true
	}
	return brokenArtifact{}, false
}

// isZipArtifact 这几个字节是不是 zip 的开头。
func isZipArtifact(head []byte) bool {
	return len(head) >= len(zipMagic) && string(head[:len(zipMagic)]) == zipMagic
}

// describeHead 把开头几个字节说成人话。
func describeHead(head []byte) string {
	if len(head) == 0 {
		return "空"
	}
	return fmt.Sprintf("% X", head)
}

// reportArtifactHealth 体检云端制品，发现坏的就提示撤回。
//
// 撤回不可逆（要恢复只能发新版本），所以先问一句。
// 读不到输入（CI、后台任务）就只报告不动手——不能卡住别人的流水线。
func reportArtifactHealth(ctx context.Context, client *apiclient.Client) {
	broken, err := checkCloudArtifacts(ctx, client)
	if err != nil || len(broken) == 0 {
		return
	}
	fmt.Printf("云端制品体检：%d 个已发布版本装不了\n", len(broken))
	for _, item := range broken {
		fmt.Printf("  %s@%s：%s\n", item.Skill.Slug, item.Version.Version, item.Reason)
	}
	if !confirmPrompt("撤回这些版本，让它们不再对外可用？") {
		fmt.Println("（没撤回。要手动撤回：skillmesh cloud revoke <slug> <version>）")
		return
	}
	for _, item := range broken {
		_, err := client.RevokeVersion(ctx, item.Skill.ID, item.Version.Version, "客户端体检发现制品损坏")
		if err != nil {
			fmt.Printf("  撤回 %s@%s 失败：%s\n", item.Skill.Slug, item.Version.Version, err)
			continue
		}
		fmt.Printf("  已撤回 %s@%s\n", item.Skill.Slug, item.Version.Version)
	}
}
