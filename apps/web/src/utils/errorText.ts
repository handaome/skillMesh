import { ApiError } from '../api'

/**
 * 把异常转成人能读的提示。
 *
 * 服务端错误按 code 分支（ApiError），拼成「CODE: 说明」；别的异常
 * （网络断了、代码抛的错）直接 String() 出来，至少让人知道出了什么事。
 *
 * 全站只此一份：同一个异常在几个页面各翻一次，翻着翻着文案就漂了。
 */
export function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}
