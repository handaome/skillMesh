package cmd

import "testing"

func Test首个版本是1_0_0(t *testing.T) {
	got, err := nextVersion(nil)
	if err != nil {
		t.Fatal(err)
	}
	if got != "1.0.0" {
		t.Fatalf("期望 1.0.0，得到 %s", got)
	}
}

func Test版本号累加patch位(t *testing.T) {
	cases := []struct {
		existing []string
		want     string
	}{
		{[]string{"1.0.0"}, "1.0.1"},
		{[]string{"1.0.0", "1.0.1"}, "1.0.2"},
		{[]string{"1.2.3", "1.10.0", "1.9.9"}, "1.10.1"},
		{[]string{"2.0.0"}, "2.0.1"},
		{[]string{"0.0.9"}, "0.0.10"},
	}
	for _, c := range cases {
		got, err := nextVersion(c.existing)
		if err != nil {
			t.Fatalf("%v: %v", c.existing, err)
		}
		if got != c.want {
			t.Fatalf("%v 应得 %s，实际 %s", c.existing, c.want, got)
		}
	}
}

func Test保留v前缀风格(t *testing.T) {
	got, err := nextVersion([]string{"v1.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	if got != "v1.0.1" {
		t.Fatalf("期望 v1.0.1，得到 %s", got)
	}
}

func Test非语义化版本要求显式指定(t *testing.T) {
	_, err := nextVersion([]string{"2024-01-01", "latest"})
	if err == nil {
		t.Fatal("期望报错")
	}
}

func Test版本号比较按数值而非字典序(t *testing.T) {
	// 字典序下 "1.10.0" < "1.9.0"，数值才是对的
	got, err := nextVersion([]string{"1.9.0", "1.10.0"})
	if err != nil {
		t.Fatal(err)
	}
	if got != "1.10.1" {
		t.Fatalf("期望 1.10.1，得到 %s", got)
	}
}
