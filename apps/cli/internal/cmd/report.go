package cmd

import (
	"context"
	"fmt"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/verify"
)

// warnRisks 把安装内容里的风险摊开给用户看（设计文档 §3.2 第 6 步）。
//
// 只提示不阻断：装到本地是用户自己的机器，密钥进了别人的机器才有理由硬拦，
// 那是 publish 的事。
func warnRisks(skillDir string) {
	findings, err := skillscan.ScanSecrets(skillDir)
	if err != nil || len(findings) == 0 {
		return
	}
	fmt.Printf("风险提示：\n%s\n", skillscan.FormatFindings(findings))
}

// reportQuiet 上报一条审计事件，失败也不声张——主流程更重要。
func reportQuiet(ctx context.Context, client *apiclient.Client, action, targetID string,
	details map[string]string) {
	if client == nil || targetID == "" {
		return
	}
	_ = client.ReportAuditEvent(ctx, action, targetID, "success", details)
}

// warnRisksOnly 只报可疑内容：脚本、可疑命令、外部下载、依赖（设计文档 §10）。
// 这类不阻断——脚本里有 curl 不等于它有害，但装之前应该让人看见。
func warnRisksOnly(skillDir string) {
	risks, err := skillscan.ScanRisks(skillDir)
	if err != nil || len(risks) == 0 {
		return
	}
	fmt.Println(skillscan.FormatRisks(risks))
}

// verifyArtifact 验签这一份制品，并对一遍本地算出的摘要。
//
// 两件事都要做：签名验的是「服务端说的摘要没被换」，
// 本地对摘要验的是「装下来的东西就是那个摘要」。
func verifyArtifact(ctx context.Context, client *apiclient.Client, target apiclient.SkillVersion,
	skillDir string) string {

	if target.Artifact == nil || target.Artifact.ContentDigest == "" {
		return verify.NoSignature.String()
	}
	key, err := client.VerifyKey(ctx)
	if err != nil {
		return "验签跳过（取不到公钥）"
	}
	// 钉了公钥就以本地这份为准：注册中心换了密钥而你没更新配置，
	// 这里必须硬失败，不能只看「签名能不能验过」（设计文档 §10 信任根）。
	if cfg.Verify.Pinned() {
		if key.KeyID != cfg.Verify.PinnedKeyID || key.PublicKey != cfg.Verify.PinnedPublicKey {
			return string(verify.Invalid) + "：签名密钥与本地钉住的不一致（服务端 " +
				key.KeyID + "，本地 " + cfg.Verify.PinnedKeyID + "）"
		}
	} else if !key.Enabled || key.PublicKey == "" {
		// 没钉也没配服务端密钥：这次只能确认内容摘要对得上，不能确认是谁签的
		return "未验证来源（本地没钉公钥，服务端也没开签名）"
	}
	outcome, err := verify.Check(key, target.Artifact.Signature, target.Artifact.ContentDigest)
	if err != nil {
		return string(verify.Invalid) + "：" + err.Error()
	}
	if outcome != verify.Verified {
		return outcome.String()
	}
	local, err := skillscan.DirDigest(skillDir)
	if err != nil {
		return string(verify.Verified) + "（本地摘要算不出来）"
	}
	if local != target.Artifact.ContentDigest {
		return string(verify.Invalid) + "：装下来的内容和清单摘要对不上"
	}
	return string(verify.Verified) + "（内容与签名一致）"
}

// printVersionRisks 打印服务端对这一份制品的扫描结论。
// 没扫过就说没扫过——空着会让人误以为是「安全」。
func printVersionRisks(version apiclient.SkillVersion) {
	if version.Scan == nil {
		fmt.Printf("%s 未经服务端扫描\n", version.Version)
		return
	}
	if version.Scan.Status == "passed" {
		fmt.Printf("%s 服务端扫描通过（%s %s）\n",
			version.Version, version.Scan.ScannerName, version.Scan.ScannerVersion)
		return
	}
	fmt.Printf("%s 服务端扫描结论：%s\n", version.Version, version.Scan.Status)
	for _, finding := range version.Scan.Findings {
		fmt.Printf("  %s:%d %s —— %s\n", finding.Path, finding.Line, finding.Rule, finding.Detail)
	}
}
