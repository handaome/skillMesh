package auth

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

// Tokens 是登录态。优先存系统凭据管理器；不可用时回退到权限 0600 的文件。
type Tokens struct {
	AccessToken  string    `json:"access_token"`
	RefreshToken string    `json:"refresh_token,omitempty"`
	ExpiresAt    time.Time `json:"expires_at"`
}

// Valid 留一分钟余量，避免请求发出时刚好过期。
func (t Tokens) Valid() bool {
	return t.AccessToken != "" && time.Now().Add(time.Minute).Before(t.ExpiresAt)
}

func (t *Tokens) Save(path string) error {
	data, err := json.Marshal(t)
	if err != nil {
		return err
	}
	if putCredential(string(data)) == nil {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o600)
}

func LoadTokens(path string) (Tokens, error) {
	var t Tokens
	raw, err := getCredential()
	if err == nil && raw != "" {
		if err := json.Unmarshal([]byte(raw), &t); err != nil {
			return t, fmt.Errorf("解析凭据管理器中的令牌: %w", err)
		}
		return t, nil
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return t, err
	}
	if err := json.Unmarshal(data, &t); err != nil {
		return t, fmt.Errorf("解析 %s: %w", path, err)
	}
	return t, nil
}

// Clear 先清凭据管理器再删文件，任一失败不阻断登出。
func Clear(path string) error {
	deleteCredential()
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}
