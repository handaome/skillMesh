package cmd

import (
	"testing"

	"github.com/handaome/skillmesh/apps/cli/internal/apiclient"
)

func Test不指定slug就是全要(t *testing.T) {
	skills := []apiclient.Skill{{Slug: "a"}, {Slug: "b"}}
	if got := pickBySlug(skills, nil); len(got) != 2 {
		t.Fatalf("应全选，得到 %d 个", len(got))
	}
}

func Test按slug挑选(t *testing.T) {
	skills := []apiclient.Skill{{Slug: "a"}, {Slug: "b"}}
	got := pickBySlug(skills, []string{"b"})
	if len(got) != 1 || got[0].Slug != "b" {
		t.Fatalf("应只选 b，得到 %+v", got)
	}
	if got := pickBySlug(skills, []string{"nope"}); len(got) != 0 {
		t.Fatalf("个人空间没有的 slug 不该被选中，得到 %+v", got)
	}
}
