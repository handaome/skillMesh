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
