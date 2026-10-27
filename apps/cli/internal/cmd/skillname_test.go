package cmd

import "testing"

// 目录名在 Windows 上非法时装不进去，报的还是原始的操作系统错误——
// 用户看不出是名字的问题，所以要在发布这一步就拦住。
func TestCheckDirName(t *testing.T) {
	cases := []struct {
		name    string
		skill   string
		wantErr bool
	}{
		{"正常名字", "my-skill", false},
		{"带点", "my.skill", false},
		{"空名", "", true},
		{"当前目录", ".", true},
		{"上级目录", "..", true},
		{"含斜杠", "a/b", true},
		{"含反斜杠", `a\b`, true},
		{"含冒号", "a:b", true},
		{"Windows 保留字符", "a<b", true},
		{"Windows 保留字符 引号", `a"b`, true},
		{"Windows 保留字符 问号", "a?b", true},
		{"尾随点", "foo.", true},
		{"尾随空格", "foo ", true},
		{"保留设备名", "CON", true},
		{"保留设备名 小写", "nul", true},
		{"保留设备名 带扩展名", "COM1.txt", true},
		{"控制字符", "a\x01b", true},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := CheckDirName(c.skill)
			if c.wantErr && err == nil {
				t.Fatalf("CheckDirName(%q) 应该报错", c.skill)
			}
			if !c.wantErr && err != nil {
				t.Fatalf("CheckDirName(%q) 不该报错: %v", c.skill, err)
			}
		})
	}
}
