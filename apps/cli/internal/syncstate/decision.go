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
// cloud 为空表示云端没有可比的内容——这个 Skill 云端不存在，或还没有已发布版本。
// 那不是「云端更新了」，云端根本没有东西可下：以本地为准重新传上去。
//
// cloud 为空还堵住了一个坑：云端的库被重建过（服务端的 Skill 全没了），
// 本机基线还留着旧记录。这时三方比较会把「云端没有」当成「云端变了」，
// 于是去下载一个不存在的 Skill——请求里带着空的 skill_id，撞出一句看不懂的 401。
func Decide(base, local, cloud string) Decision {
	if local == cloud {
		return UpToDate
	}
	if base == "" || cloud == "" {
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
