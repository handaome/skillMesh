package cmd

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
	"github.com/handaome/skillmesh/apps/cli/internal/skillscan"
	"github.com/handaome/skillmesh/apps/cli/internal/verify"
)

// warnRisks 把安装内容里的风险摊开给用户看（设计文档 §3.2 第 6 步）。
//
// 只提示不阻断：装到本地是用户自己的机器，密钥进了别人的机器才有理由硬拦，
// 那是 publish 的事。
// riskText 把安装内容里的风险摊成一段文字：疑似密钥与可疑内容（设计文档 §10）。
//
// 返回文本而不是直接打印：批量恢复几十个 Skill 时逐条打印会刷屏，
// 什么时候展开由调用方决定。
//
// 可疑内容不阻断——脚本里有 curl 不等于它有害，但装之前应该让人看见。
func riskText(skillDir string) string {
	var parts []string
	if findings, err := skillscan.ScanSecrets(skillDir); err == nil && len(findings) > 0 {
		parts = append(parts, "风险提示：\n"+skillscan.FormatFindings(findings))
	}
	if risks, err := skillscan.ScanRisks(skillDir); err == nil && len(risks) > 0 {
		parts = append(parts, skillscan.FormatRisks(risks))
	}
	return strings.Join(parts, "\n")
}

// VerifyArtifact 验签这一份制品，并对一遍本地算出的摘要。
//
// 两件事都要做：签名验的是「服务端说的摘要没被换」，
// 本地对摘要验的是「装下来的东西就是那个摘要」。
//
// 第二个返回值是 error：**不通过就是 error**，调用方必须中止安装。
// 只打印一句「验签失败」然后照样装，等于没有闸门——
// spec §11 里「不符就拒装」说的就是这里。
func VerifyArtifact(ctx context.Context, client *apiclient.Client, target apiclient.SkillVersion,
	skillDir string) (string, error) {

	if target.Artifact == nil || target.Artifact.ContentDigest == "" {
		return verify.NoSignature.String(), nil
	}
	key, err := client.VerifyKey(ctx)
	if err != nil {
		return "验签跳过（取不到公钥）", nil
	}
	// 钉了公钥就以本地这份为准：注册中心换了密钥而你没更新配置，
	// 这里必须硬失败，不能只看「签名能不能验过」（设计文档 §10 信任根）。
	if Cfg.Verify.Pinned() {
		if key.KeyID != Cfg.Verify.PinnedKeyID || key.PublicKey != Cfg.Verify.PinnedPublicKey {
			msg := string(verify.Invalid) + "：签名密钥与本地钉住的不一致（服务端 " +
				key.KeyID + "，本地 " + Cfg.Verify.PinnedKeyID + "）"
			return msg, errors.New(msg)
		}
	} else if !key.Enabled || key.PublicKey == "" {
		// 没钉也没配服务端密钥：这次只能确认内容摘要对得上，不能确认是谁签的
		return "未验证来源（本地没钉公钥，服务端也没开签名）", nil
	}
	outcome, err := verify.Check(key, target.Artifact.Signature, target.Artifact.ContentDigest)
	if err != nil {
		msg := string(verify.Invalid) + "：" + err.Error()
		return msg, errors.New(msg)
	}
	if outcome != verify.Verified {
		return outcome.String(), fmt.Errorf("验签未通过：%s", outcome.String())
	}
	local, err := skillscan.DirDigest(skillDir)
	if err != nil {
		return string(verify.Verified) + "（本地摘要算不出来）", nil
	}
	if local != target.Artifact.ContentDigest {
		msg := string(verify.Invalid) + "：装下来的内容和清单摘要对不上"
		return msg, errors.New(msg)
	}
	return string(verify.Verified) + "（内容与签名一致）", nil
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
