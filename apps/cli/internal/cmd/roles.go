package cmd

// roleRank 是角色等级，索引越小权限越高。
// 顺序与服务端 OrgService.roleAtLeast 一致，别单独改一边。
var roleRank = map[string]int{
	"owner":     0,
	"admin":     1,
	"publisher": 2,
	"reviewer":  3,
	"member":    4,
	"viewer":    5,
}

// RoleAtLeast 判断实际角色是否达到要求。
func RoleAtLeast(actual, required string) bool {
	have, ok := roleRank[actual]
	if !ok {
		return false
	}
	need, ok := roleRank[required]
	return ok && have <= need
}
