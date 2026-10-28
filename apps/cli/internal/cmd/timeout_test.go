package cmd

import (
	"context"
	"testing"
	"time"

	"github.com/spf13/cobra"
)

func TestApplyTimeout(t *testing.T) {
	cmd := &cobra.Command{}
	cmd.SetContext(context.Background())
	if err := applyTimeout(cmd, ""); err != nil {
		t.Fatalf("不传 --timeout 时不该报错: %v", err)
	}
	if _, ok := cmd.Context().Deadline(); ok {
		t.Fatal("不传 --timeout 时不该有截止时间")
	}

	cmd2 := &cobra.Command{}
	cmd2.SetContext(context.Background())
	if err := applyTimeout(cmd2, "30s"); err != nil {
		t.Fatalf("applyTimeout 失败: %v", err)
	}
	deadline, ok := cmd2.Context().Deadline()
	if !ok {
		t.Fatal("设了 --timeout 之后上下文该有截止时间")
	}
	if remaining := time.Until(deadline); remaining <= 0 || remaining > 30*time.Second {
		t.Fatalf("剩余时间 = %v, want 30 秒以内", remaining)
	}
}

func TestApplyTimeoutInvalid(t *testing.T) {
	for _, raw := range []string{"abc", "0s", "-5s"} {
		cmd := &cobra.Command{}
		cmd.SetContext(context.Background())
		if err := applyTimeout(cmd, raw); err == nil {
			t.Fatalf("--timeout %q 应该报错", raw)
		}
	}
}
