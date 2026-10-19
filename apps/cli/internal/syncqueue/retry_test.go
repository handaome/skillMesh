package syncqueue

import (
	"testing"
	"time"
)

func Test失败进队列退避重试到上限(t *testing.T) {
	var q Queue
	q.Record("demo", "claude-code", "/tmp/demo", "连接被拒绝")
	if len(q.Pending()) != 1 || q.Pending()[0].Attempts != 1 {
		t.Fatalf("第一次失败应进队列: %+v", q.Pending())
	}
	// 刚失败就重试：不到退避时间，不该被挑出来
	if due := q.Due(time.Now()); len(due) != 0 {
		t.Fatalf("退避期内不该重试: %+v", due)
	}
	for i := 0; i < MaxAttempts-1; i++ {
		q.Record("demo", "claude-code", "/tmp/demo", "连接被拒绝")
	}
	if !q.Pending()[0].Abandoned() {
		t.Fatalf("重试到上限应标记放弃: %+v", q.Pending()[0])
	}
	if due := q.Due(time.Now().Add(24 * time.Hour)); len(due) != 0 {
		t.Fatalf("放弃的不该再自动重试: %+v", due)
	}
	// 但诊断要看得见
	if len(q.Pending()) != 1 {
		t.Fatal("放弃的任务仍应留在队列里可查")
	}
}

func Test成功后清出队列(t *testing.T) {
	var q Queue
	q.Record("demo", "claude-code", "/tmp/demo", "超时")
	q.Record("other", "claude-code", "/tmp/other", "超时")
	q.Clear("demo", "claude-code")
	pending := q.Pending()
	if len(pending) != 1 || pending[0].Name != "other" {
		t.Fatalf("只该清掉成功的那条: %+v", pending)
	}
}

func Test退避时间翻倍并封顶(t *testing.T) {
	if got := Backoff(1); got != time.Minute {
		t.Fatalf("第一次退避 1 分钟，得到 %v", got)
	}
	if got := Backoff(3); got != 4*time.Minute {
		t.Fatalf("第三次退避 4 分钟，得到 %v", got)
	}
	if got := Backoff(20); got != 30*time.Minute {
		t.Fatalf("退避封顶 30 分钟，得到 %v", got)
	}
}
