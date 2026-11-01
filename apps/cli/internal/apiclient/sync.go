package apiclient

import (
	"context"
	"net/http"
	"net/url"
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
// DeviceID 返回本机在注册中心的设备 ID，没有就注册一个。
// 注册成功后缓存：同步一批 Skill 会反复问，每次都发写请求会撞限流。
// 失败不缓存，下一次调用还会重试。
func (c *Client) DeviceID(ctx context.Context) (string, error) {
	c.deviceMu.Lock()
	defer c.deviceMu.Unlock()
	if c.deviceID != "" {
		return c.deviceID, nil
	}
	device, err := c.RegisterDevice(ctx, MachineKey(), runtime.GOOS, "0.1.0")
	if err != nil {
		return "", err
	}
	c.deviceID = device.ID
	return c.deviceID, nil
}

func (c *Client) SubmitChangeset(ctx context.Context, deviceID, IdempotencyKey string,
	req SyncChangesetRequest) (SyncChangesetResponse, error) {

	var out SyncChangesetResponse
	headers := map[string]string{"X-Device-Id": deviceID, "Idempotency-Key": IdempotencyKey}
	if err := c.doWith(ctx, http.MethodPost, "/sync/changesets", nil, req, &out, headers); err != nil {
		return SyncChangesetResponse{}, err
	}
	return out, nil
}

// SyncFileSpec 是要上传的一个文件：变更集里 upsert 的那一项。
type SyncFileSpec struct {
	Path        string `json:"path"`
	SHA256      string `json:"sha256"`
	SizeBytes   int64  `json:"size_bytes"`
	ContentType string `json:"content_type,omitempty"`
}

// SyncUploadSlot 是服务端开好的上传会话。
type SyncUploadSlot struct {
	Path string `json:"path"`
	// UploadID 是上传会话的 ID：传完用它调 complete 让服务端校验。
	UploadID string `json:"upload_id"`
	// UploadURL 是把文件内容 PUT 过去的位置（相对 API base 的地址）。
	UploadURL string            `json:"upload_url"`
	Headers   map[string]string `json:"headers"`
}

// OpenChangesetUploads 为变更集里变了的文件开上传会话。
//
// 只开变了的那些：没变的文件服务端从基线里取，不用重传
// （设计文档 §6.2「未变化文件不重复传输」）。
func (c *Client) OpenChangesetUploads(ctx context.Context, changesetID string,
	files []SyncFileSpec) ([]SyncUploadSlot, error) {

	var out []SyncUploadSlot
	path := "/sync/changesets/" + url.PathEscape(changesetID) + "/uploads"
	if err := c.doWith(ctx, http.MethodPost, path, nil, files, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// CommitChangeset 提交变更集：基线对得上就生成新版本，对不上服务端回 409。
func (c *Client) CommitChangeset(ctx context.Context, changesetID,
	expectedRevision string) (map[string]any, error) {

	body := map[string]any{}
	if expectedRevision != "" {
		body["expected_revision"] = expectedRevision
	}
	var out map[string]any
	path := "/sync/changesets/" + url.PathEscape(changesetID) + "/commit"
	if err := c.doWith(ctx, http.MethodPost, path, nil, body, &out); err != nil {
		return nil, err
	}
	return out, nil
}

// GetChangeset 取一个变更集。--conflict <id> 要靠它知道这是哪个 Skill 的冲突。
func (c *Client) GetChangeset(ctx context.Context, changesetID string) (SyncChangesetResponse, error) {
	var out SyncChangesetResponse
	path := "/sync/changesets/" + url.PathEscape(changesetID)
	if err := c.do(ctx, http.MethodGet, path, nil, &out); err != nil {
		return SyncChangesetResponse{}, err
	}
	return out, nil
}

// ResolveChangeset 告诉服务端这个变更集的冲突怎么处理了。
//
// resolution 取 keep-local / keep-cloud / keep-both；服务端据此把冲突标记成
// 已解决（只改记录状态，真正覆盖的是客户端自己的文件）。
func (c *Client) ResolveChangeset(ctx context.Context, changesetID, resolution string) (SyncChangesetResponse, error) {
	var out SyncChangesetResponse
	query := url.Values{}
	query.Set("resolution", resolution)
	path := "/sync/changesets/" + url.PathEscape(changesetID) + "/resolve"
	if err := c.doWith(ctx, http.MethodPost, path, query, nil, &out); err != nil {
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

// PublicConfig 是服务端公开的登录配置（GET /api/v1/config/public）。
//
// issuer 与 client_id 由服务端给：换 IdP 不用改客户端代码。
type PublicConfig struct {
	Issuer   string `json:"issuer"`
	ClientID string `json:"client_id"`
	// LocalAuth：服务端支持内置账号（用户名 + 密码）。
	LocalAuth bool `json:"local_auth"`
	// RegisterOpen：能不能自助注册。
	RegisterOpen bool `json:"register_open"`
}

// FetchPublicConfig 取服务端公开配置。不用令牌——登录前就要拿到它。
func FetchPublicConfig(ctx context.Context, server string) (PublicConfig, error) {
	var out PublicConfig
	client := New(server, "", 0)
	if err := client.doWith(ctx, http.MethodGet, "/config/public", nil, nil, &out); err != nil {
		return out, err
	}
	return out, nil
}
