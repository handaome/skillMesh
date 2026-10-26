package skillscan

import "bytes"

// IsBinaryContent 判断一段内容是不是二进制：只看前 8KB 里有没有 NUL 字节，
// 跟 git 判二进制一个路子。
//
// 打包、算摘要、扫风险、扫密钥、算 diff 必须用同一份判定：
// 同一批文件在几处得出不同结论时，「本地看着是文本、云端说是二进制」这类对不上的情况很难查。
func IsBinaryContent(data []byte) bool {
	head := data
	if len(head) > 8192 {
		head = head[:8192]
	}
	return bytes.IndexByte(head, 0) >= 0
}
