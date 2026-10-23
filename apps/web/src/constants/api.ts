/**
 * 后端 API 的基址。
 *
 * 控制台与 API 同源部署（反代把 /api 转到后端），所以走相对路径。
 * 单独放一个文件：登录前要取的公开配置也在 /api/v1 下，
 * 放在 api.ts 里会被 auth/session 的引用绕成环。
 */
export const API_BASE = '/api/v1'
