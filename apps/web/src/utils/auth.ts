/**
 * 认证头。
 *
 * Bearer 头在几个地方拼过：拼法一样，散在各处就迟早拼出两种写法。
 * 统一放这里，谁要用都从这里拿。
 */

/** 无条件带 Authorization。 */
export function bearerHeader(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` }
}

/**
 * 没有令牌就不带 Authorization：公开接口（公开邀请、登录前配置）不该
 * 拿一个空令牌去碰壁。
 */
export function authHeaders(token: string): Record<string, string> {
  return token ? bearerHeader(token) : {}
}
