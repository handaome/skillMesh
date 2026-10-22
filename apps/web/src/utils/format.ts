/**
 * 时间戳 → 人读的日期时间。
 *
 * 服务端给的是带时区的 ISO 串，这里转成本地时区显示。
 * 自己拼而不用 toLocaleString：各机器的 locale/ICU 不一样，
 * 同一份数据在开发机与服务器上会长得不一样。
 */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return '—'
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return '—'
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ` +
    `${pad(date.getHours())}:${pad(date.getMinutes())}`
  )
}
