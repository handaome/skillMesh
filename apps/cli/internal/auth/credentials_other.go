//go:build !windows

package auth

import "errors"

// errNoCredentialStore 是非 Windows 平台的凭据存储缺失提示。
// 那边的系统凭据管理器没有绑定实现，登录请走 SKILLMESH_TOKEN。
var errNoCredentialStore = errors.New("当前平台没有系统凭据管理器，CI 场景请用 SKILLMESH_TOKEN 环境变量")

func putCredential(string) error { return errNoCredentialStore }

func getCredential() (string, error) { return "", errNoCredentialStore }

func deleteCredential() {}
