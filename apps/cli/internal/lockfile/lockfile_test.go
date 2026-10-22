package lockfile

import "testing"

// 锁文件按名字存，但 `install <skill-id> --locked` 手里只有注册中心 id。
// 只按名字查，--locked 就成了一空开关。
func Test两种键都能查到(t *testing.T) {
	lock := Lock{Skills: []LockedSkill{
		{Name: "my-skill", SourceID: "9c1f0000-0000-0000-0000-000000000001"},
	}}

	if _, ok := lock.Find("my-skill"); !ok {
		t.Fatal("按名字查不到")
	}
	if _, ok := lock.Find("9c1f0000-0000-0000-0000-000000000001"); !ok {
		t.Fatal("按注册中心 id 查不到")
	}
	if _, ok := lock.Find("别的-skill"); ok {
		t.Fatal("不存在的名字不该查到")
	}
}
