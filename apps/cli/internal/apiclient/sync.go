package apiclient

import (
	"context"
	"net/http"
	"os"
	"runtime"

	"github.com/handaome/skillmesh/apps/cli/internal/verify"
)

// Device 是注册中心认得的一台机器。同步变更集要挂在设备上。
type Device struct {
	ID          string `json:"id"`
	DeviceKey   string `json:"device_key"`
	DisplayName string `json:"display_name"`
	Platform    string `json:"platform"`
	CLIVersion  string `json:"cli_version"`
}

// SyncChangesetRequest 是提交一次同步的内容声明。
type SyncChangesetRequest struct {
	SkillID   string          `json:"skill_id"`
	Direction string          `json:"direction"`
	BaseHash  string          `json:"base_hash"`
	LocalHash string          `json:"local_hash"`
	LocalPath string          `json:"local_path,omitempty"`
	Manifest  []ManifestEntry `json:"manifest,omitempty"`
}

// SyncChangesetResponse 是服务端对「这次提交还成不成立」的判断。
type SyncChangesetResponse struct {
	ChangesetID string `json:"changeset_id"`
	SkillID     string `json:"skill_id"`
	Status      string `json:"status"`
	CloudHash   string `json:"cloud_hash"`
	Message     string `json:"message"`
	Conflicts   []struct {
		Path  string `json:"path"`
		Base  string `json:"base"`
		Local string `json:"local"`
		Cloud string `json:"cloud"`
		Type  string `json:"type"`
	} `json:"conflicts"`
}

func (c *Client) RegisterDevice(ctx context.Context, key, platform, version string) (Device, error) {
	var out Device
	body := map[string]string{"device_key": key, "platform": platform, "cli_version": version}
	if err := c.doWith(ctx, http.MethodPost, "/devices", nil, body, &out); err != nil {
		return Device{}, err
	}
	return out, nil
}

// SubmitChangeset 提交一次同步。幂等键由调用方给：断网重试时必须复用同一个。
func (c *Client) SubmitChangeset(ctx context.Context, deviceID, idempotencyKey string,
	req SyncChangesetRequest) (SyncChangesetResponse, error) {

	var out SyncChangesetResponse
	headers := map[string]string{"X-Device-Id": deviceID, "Idempotency-Key": idempotencyKey}
	if err := c.doWith(ctx, http.MethodPost, "/sync/changesets", nil, req, &out, headers); err != nil {
		return SyncChangesetResponse{}, err
	}
	return out, nil
}

// MachineKey 生成本机稳定标识：主机名 + 用户名 + 操作系统。
// 用这三个拼而不是随机 ID——随机 ID 存丢了就得重新注册，旧的同步基线会全断。
func MachineKey() string {
	host, err := os.Hostname()
	if err != nil || host == "" {
		host = "unknown-host"
	}
	user := os.Getenv("USERNAME")
	if user == "" {
		user = os.Getenv("USER")
	}
	if user == "" {
		user = "unknown-user"
	}
	return host + "|" + user + "|" + runtime.GOOS
}

// ReportAuditEvent 上报客户端自己知道的审计事件。
// 服务端会拒绝特权动作——发布、撤回这类是服务端自己的事。
func (c *Client) ReportAuditEvent(ctx context.Context, action, targetID, outcome string,
	details map[string]string) error {

	body := map[string]any{"action": action, "target_id": targetID, "outcome": outcome}
	if len(details) > 0 {
		body["details"] = details
	}
	return c.doWith(ctx, http.MethodPost, "/audit/events", nil, body, nil)
}

// VerifyKey 取注册中心的验签公钥。
func (c *Client) VerifyKey(ctx context.Context) (verify.PublicKey, error) {
	var out verify.PublicKey
	if err := c.doWith(ctx, http.MethodGet, "/keys/verify", nil, nil, &out); err != nil {
		return out, err
	}
	return out, nil
}
