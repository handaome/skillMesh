package syncstate

// Decision 是「本地 vs 云端 vs 基线」三方比较出来的同步动作。
type Decision string

const (
	// UpToDate 两边一样，没什么可做的。
	UpToDate Decision = "无变化"
	// Upload 只有本地改了，正常上传。
	Upload Decision = "上传"
	// Download 只有云端改了，本地落后。
	Download Decision = "下载"
	// Conflict 两边都改了，且改得不一样——不自动覆盖（设计文档 §6.3）。
	Conflict Decision = "冲突"
)

// Decide 比较基线、本地、云端三份摘要，决定这次同步做什么。
//
// base 为空表示这台机器从没同步过（没有基线），此时以本地为准上传。
func Decide(base, local, cloud string) Decision {
	if local == cloud {
		return UpToDate
	}
	if base == "" {
		return Upload
	}
	switch {
	case local == base:
		return Download
	case cloud == base:
		return Upload
	default:
		return Conflict
	}
}
