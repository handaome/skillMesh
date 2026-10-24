package apiclient

import (
	"context"
	"net/http"
)

// Installation 是注册中心记录的安装实例：某台机器上的某个目录装着什么版本。
type Installation struct {
	ID               string `json:"id"`
	SkillID          string `json:"skill_id"`
	DeviceID         string `json:"device_id"`
	Agent            string `json:"agent"`
	InstallScope     string `json:"install_scope"`
	TargetPath       string `json:"target_path"`
	InstalledVersion string `json:"installed_version"`
	InstalledSHA256  string `json:"installed_sha256"`
	UpdatePolicy     string `json:"update_policy"`
	LocalModified    bool   `json:"local_modified"`
	Status           string `json:"status"`
	InstalledAt      string `json:"installed_at"`
	LastUpdatedAt    string `json:"last_updated_at"`
	// LastCheckedAt 是最近什么时候查过这台机器上的磁盘（巡检时间）。
	LastCheckedAt string `json:"last_checked_at"`
}

// RegisterInstallationInput 是登记一条安装所需的字段。
type RegisterInstallationInput struct {
	SkillID          string `json:"skill_id"`
	DeviceID         string `json:"device_id"`
	Agent            string `json:"agent"`
	InstallScope     string `json:"install_scope"`
	TargetPath       string `json:"target_path"`
	InstalledVersion string `json:"installed_version,omitempty"`
	InstalledSHA256  string `json:"installed_sha256,omitempty"`
	// LocalModified 如实报告本机内容有没有被改过。刚装完的内容就是服务端那一份，
	// 所以这里通常是 false；装完之后用户改了文件，由巡检（installations --check）改写。
	LocalModified bool `json:"local_modified"`
}

func (c *Client) RegisterInstallation(ctx context.Context,
	in RegisterInstallationInput) (Installation, error) {

	var out Installation
	if err := c.doWith(ctx, http.MethodPost, "/installations", nil, in, &out); err != nil {
		return out, err
	}
	return out, nil
}

func (c *Client) Installations(ctx context.Context, status string) ([]Installation, error) {
	query := map[string][]string{}
	if status != "" {
		query["status"] = []string{status}
	}
	var out []Installation
	if err := c.doWith(ctx, http.MethodGet, "/installations", query, nil, &out); err != nil {
		return nil, err
	}
	return out, nil
}

func (c *Client) UnregisterInstallation(ctx context.Context, id string) error {
	return c.doWith(ctx, http.MethodDelete, "/installations/"+id, nil, nil, nil)
}

// UpdateInstallationInput 是巡检后回写安装记录要带的字段。
//
// LocalModified 用指针：要能显式写回 false（把「本地改过」抹掉），
// 就不能省掉这个字段——省掉服务端就当没改。
type UpdateInstallationInput struct {
	Status           string `json:"status,omitempty"`
	LocalModified    *bool  `json:"local_modified,omitempty"`
	InstalledVersion string `json:"installed_version,omitempty"`
}

// UpdateInstallation 回写一条安装记录（巡检算出真实状态后推给服务端）。
func (c *Client) UpdateInstallation(ctx context.Context, id string,
	in UpdateInstallationInput) (Installation, error) {

	var out Installation
	if err := c.doWith(ctx, http.MethodPatch, "/installations/"+id, nil, in, &out); err != nil {
		return out, err
	}
	return out, nil
}
