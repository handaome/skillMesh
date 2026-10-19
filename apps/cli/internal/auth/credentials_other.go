//go:build !windows

package auth

import "os"

// 非 Windows 平台没有绑定的系统凭据管理器，一律回退到权限 0600 的文件。
func putCredential(string) error { return os.ErrNotSupported }

func getCredential() (string, error) { return "", os.ErrNotSupported }

func deleteCredential() {}
