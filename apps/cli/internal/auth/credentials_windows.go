//go:build windows

package auth

import "github.com/danieljoos/wincred"

// 凭据只进系统凭据管理器，不写进 config.yaml 或 Skill 文件
// （详细设计 §10 密钥防护、§3.1.1 凭据存放）。
const credentialTarget = "skillmesh:api"

func putCredential(secret string) error {
	cred := wincred.NewGenericCredential(credentialTarget)
	cred.UserName = "skillmesh-cli"
	cred.CredentialBlob = []byte(secret)
	cred.Persist = wincred.PersistLocalMachine
	return cred.Write()
}

func getCredential() (string, error) {
	cred, err := wincred.GetGenericCredential(credentialTarget)
	if err != nil {
		return "", err
	}
	return string(cred.CredentialBlob), nil
}

func deleteCredential() {
	if cred, err := wincred.GetGenericCredential(credentialTarget); err == nil {
		_ = cred.Delete()
	}
}
