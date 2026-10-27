package gitx

import "testing"

// 「只接受远程地址」这个承诺要真的成立：放行本地路径的话，
// git 会拿着它去 clone，报出来的是一句看不懂的 repository not found。
func TestNormalizeURLAcceptsRemote(t *testing.T) {
	remote := []string{
		"https://github.com/owner/repo.git",
		"https://github.com/owner/repo",
		"ssh://git@github.com/owner/repo.git",
		"git@github.com:owner/repo.git",
		"https://token@git.example.com/owner/repo.git",
	}
	for _, raw := range remote {
		if _, err := NormalizeURL(raw); err != nil {
			t.Fatalf("NormalizeURL(%q) 不该报错: %v", raw, err)
		}
	}
}

func TestNormalizeURLRejectsLocalAndShorthand(t *testing.T) {
	rejected := []string{
		"",
		"file:///tmp/repo",
		"./local",
		"../local",
		".",
		"..",
		"~/skills",
		"/abs/path",
		`C:\repo`,
		"C:/repo",
		"owner/repo",
	}
	for _, raw := range rejected {
		if _, err := NormalizeURL(raw); err == nil {
			t.Fatalf("NormalizeURL(%q) 应该报错", raw)
		}
	}
}

// 内嵌凭据要拒：凭据不得写进 URL（详细设计 §7.3）。
func TestNormalizeURLRejectsEmbeddedCredential(t *testing.T) {
	if _, err := NormalizeURL("https://user:secret@github.com/owner/repo.git"); err == nil {
		t.Fatal("带密码的地址应该报错")
	}
}
