// v2 团队控制台的浏览器端到端验收。
//
// 为什么要有这个：v2 的浏览器路径曾长期「未实测」——而真跑起来才发现
// realm 里的 webOrigins 配了 Keycloak 不认的「带端口通配」，
// 导致浏览器登录从一开始就不通。没有真实浏览器的回归，这种问题会一直藏着。
//
// 跑法（需要 API、前端 dev server、Keycloak 都在本机跑着）：
//   cd apps/web && pnpm e2e
//
// 环境变量（都有默认值）：
//   SKILLMESH_E2E_WEB / _API / _ISSUER / _CLIENT / _USER_A / _PASS_A / _USER_B / _PASS_B
//   SKILLMESH_E2E_ORG / _TEAM / _SLUG
import { createHash } from 'node:crypto'
import { launchBrowser, newContext, newPage, sleep } from './lib/cdp.mjs'
import { zipFiles } from './lib/zip.mjs'

const CFG = {
  web: process.env.SKILLMESH_E2E_WEB ?? 'http://127.0.0.1:5174',
  api: process.env.SKILLMESH_E2E_API ?? 'http://127.0.0.1:8080/api/v1',
  issuer: (process.env.SKILLMESH_E2E_ISSUER ?? 'http://127.0.0.1:8180/kc/realms/skillmesh').replace(/\/$/, ''),
  client: process.env.SKILLMESH_E2E_CLIENT ?? 'skillmesh-test',
  userA: process.env.SKILLMESH_E2E_USER_A ?? 'dev-user',
  passA: process.env.SKILLMESH_E2E_PASS_A ?? 'dev',
  userB: process.env.SKILLMESH_E2E_USER_B ?? 'dev-user2',
  passB: process.env.SKILLMESH_E2E_PASS_B ?? 'dev2',
  org: process.env.SKILLMESH_E2E_ORG ?? 'acme',
  team: process.env.SKILLMESH_E2E_TEAM ?? 'platform',
  slug: process.env.SKILLMESH_E2E_SLUG ?? 'e2e-v2-console',
}

const results = []
function step(name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  —  ' + detail : ''}`)
}
function mark(msg) {
  console.log(`  · ${msg}`)
}

// ---------- 服务端与 Keycloak ----------

async function passwordToken(username, password) {
  const res = await fetch(`${CFG.issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      client_id: CFG.client, username, password, grant_type: 'password',
    }),
  })
  if (!res.ok) throw new Error(`取 ${username} 的令牌失败：${res.status} ${await res.text()}`)
  const body = await res.json()
  return body.access_token
}

async function api(path, { method = 'GET', token, body } = {}) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(CFG.api + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const payload = await res.json().catch(() => ({}))
  if (!res.ok) {
    const err = new Error(`${method} ${path} → ${res.status} ${JSON.stringify(payload).slice(0, 200)}`)
    err.status = res.status
    err.payload = payload
    throw err
  }
  return payload
}

/** 探端口：依赖不在就直接说清楚缺哪个。 */
async function requireService(name, url) {
  try {
    const res = await fetch(url)
    if (!res.ok && res.status !== 401) throw new Error(String(res.status))
  } catch (e) {
    throw new Error(`${name} 没起来（${url}）：${e.message}。先把它跑起来再跑 E2E。`)
  }
}

/** 造一个待审版本：建（或复用）Skill，再上传一个包。 */
async function submitPendingVersion(token, orgId, teamId) {
  let skillId = null
  const found = await api(`/skills?q=${encodeURIComponent(CFG.slug)}&limit=20`, { token })
  const hit = (found.items ?? []).find((s) => s.slug === CFG.slug)
  if (hit) {
    skillId = hit.id
  } else {
    const created = await api('/skills', {
      method: 'POST', token,
      body: {
        slug: CFG.slug, name: CFG.slug, description: 'E2E 验收用的示例技能',
        visibility: 'team', organization_id: orgId, owner_team_id: teamId,
      },
    })
    skillId = created.id
  }

  // 版本号往后排，别覆盖已有版本。列表顺序不保证，所以从 1.0.0 起找第一个空位
  const versions = await api(`/skills/${skillId}/versions`, { token })
  const used = new Set((versions.items ?? []).map((v) => v.version))
  let next = '1.0.0'
  while (used.has(next)) next = bumpVersion(next)

  const skillMd = Buffer.from(
    `---\nname: ${CFG.slug}\ndescription: E2E 验收用的示例技能\n---\n\n# ${CFG.slug}\n\n版本 ${next} 的正文。\n`,
    'utf8',
  )
  const manifest = [{ path: 'SKILL.md', sha256: createHash('sha256').update(skillMd).digest('hex'), size_bytes: skillMd.length, executable: false }]
  const zip = zipFiles([{ path: 'SKILL.md', content: skillMd }])

  const created = await api(`/skills/${skillId}/versions`, {
    method: 'POST', token,
    body: {
      version: next, channel: 'stable', size_bytes: zip.length, file_count: manifest.length,
      sha256: createHash('sha256').update(zip).digest('hex'), manifest,
    },
  })
  const upload = created.upload
  const put = await fetch(upload.url, { method: upload.method, headers: upload.headers ?? {}, body: zip })
  if (!put.ok) throw new Error(`上传制品失败：${put.status}`)
  await api(`/uploads/${upload.session_id}/complete`, { method: 'POST', token, body: {} })
  return { skillId, version: next }
}

function bumpVersion(v) {
  const parts = String(v).split('.')
  while (parts.length < 3) parts.push('0')
  parts[parts.length - 1] = String(Number(parts[parts.length - 1]) + 1)
  return parts.join('.')
}

// ---------- 浏览器操作 ----------

async function setInput(page, selector, value) {
  await page.eval(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    el.value = ${JSON.stringify(value)};
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`)
}

/** 登录：点「登录」→ Keycloak → 填账号密码 → 回到前端。 */
async function login(page, user, pass, doneExpr = `!!document.querySelector('[data-test="who"]')`, startUrl = `${CFG.web}/login`) {
  await page.goto(startUrl)
  await page.waitFor(`!!document.querySelector('[data-test="login"]')`, { label: '登录按钮' })
  await page.waitApp()
  await page.realClick('[data-test="login"]')
  await page.waitFor(`location.href.startsWith('${CFG.issuer.split('/realms')[0]}')`, { label: '跳到 Keycloak', timeout: 30000 })
  await page.waitFor(`!!document.querySelector('#username')`, { label: 'Keycloak 用户名框', timeout: 30000 })
  await setInput(page, '#username', user)
  await setInput(page, '#password', pass)
  await page.realClick('#kc-login')
  await page.waitFor(`location.href.startsWith('${CFG.web}')`, { label: '回到前端', timeout: 30000 })
  await page.waitFor(doneExpr, { label: '登录后落地', timeout: 20000 })
  // 等用户名真的出现：元素挂上时 /me 还没回来，里面是空的。
  await page.waitFor(
    `(document.querySelector('[data-test="who"]')||{}).textContent?.trim()?.length > 0`,
    { label: '顶栏用户名', timeout: 20000 },
  )
}

/** 在原生 select 里选一项：设好 value 再派发 change，界面就走同一套更新路径。 */
async function pickOption(page, rowSelector, rowText, optionText) {
  await page.eval(`(() => {
    const row = [...document.querySelectorAll(${JSON.stringify(rowSelector)})]
      .find((r) => r.innerText.includes(${JSON.stringify(rowText)}));
    if (!row) throw new Error('找不到行 ' + ${JSON.stringify(rowText)});
    const select = row.querySelector('[data-test="role-edit"]');
    const option = [...select.options].find((o) => o.textContent.trim() === ${JSON.stringify(optionText)});
    if (!option) throw new Error('找不到选项 ' + ${JSON.stringify(optionText)});
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`)
}

/** 等某人的角色列变成目标值——改完角色，表格要等接口回来才刷新。 */
async function waitRole(page, who, want, timeout = 15000) {
  return page.waitFor(`(() => {
    const row = [...document.querySelectorAll('tr')].find((r) => r.innerText.includes(${JSON.stringify(who)}));
    if (!row) return false;
    const el = row.querySelector('[data-test="role-edit"]');
    return el ? el.value === ${JSON.stringify(want)} : false;
  })()`, { timeout, label: `${who} 的角色变 ${want}` })
}

async function roleOf(page, who) {
  return page.eval(`(() => {
    const row = [...document.querySelectorAll('tr')].find((r) => r.innerText.includes(${JSON.stringify(who)}));
    return row ? row.querySelector('[data-test="role-edit"]').value : null;
  })()`)
}

// ---------- 主流程 ----------

await requireService('注册中心', `${CFG.api.replace(/\/api\/v1$/, '')}/actuator/health`)
await requireService('前端', `${CFG.web}/`)
await requireService('Keycloak', `${CFG.issuer}/.well-known/openid-configuration`)

const tokenA = await passwordToken(CFG.userA, CFG.passA)
const tokenB = await passwordToken(CFG.userB, CFG.passB)

const meA = await api('/me', { token: tokenA })
const org = (meA.organizations ?? []).find((o) => o.slug === CFG.org)
if (!org) throw new Error(`账号 ${CFG.userA} 不是组织 ${CFG.org} 的成员`)
const teams = await api(`/organizations/${org.id}/teams`, { token: tokenA })
const team = (teams ?? []).find((t) => t.slug === CFG.team)
if (!team) throw new Error(`组织 ${CFG.org} 下没有团队 ${CFG.team}`)
mark(`组织 ${org.slug} / 团队 ${team.slug} 就位`)

// B 账号要先有 publisher 才能发版。上一轮跑完可能把它留在别的角色上
//（脚本第 4 步会来回改角色），这里先摆平，脚本才能重复跑。
const meB = await api('/me', { token: tokenB })
const orgB = (meB.organizations ?? []).find((o) => o.slug === CFG.org)
if (!orgB) throw new Error(`账号 ${CFG.userB} 不是组织 ${CFG.org} 的成员`)
if (!['publisher', 'owner', 'admin'].includes(orgB.role)) {
  const members = await api(`/organizations/${org.id}/members`, { token: tokenA })
  const memberB = (members ?? []).find((m) => m.display_name === meB.user?.display_name)
  if (!memberB) throw new Error(`成员表里找不到 ${CFG.userB}`)
  await api(`/organizations/${org.id}/members/${memberB.user_id}`, {
    method: 'PATCH', token: tokenA, body: { role: 'publisher' },
  })
  mark(`把 ${CFG.userB} 的组织角色提回 publisher（原为 ${orgB.role}）`)
}

const { version } = await submitPendingVersion(tokenB, org.id, team.id)
mark(`已提交待审版本 ${CFG.slug} ${version}`)

const { proc, port } = await launchBrowser()
try {
  // ---- A 登录 ----
  const a = await newPage(port)
  await login(a, CFG.userA, CFG.passA)
  const who = await a.text('[data-test="who"]')
  step('1. A 登录 Web，顶栏显示用户', who === 'Dev User', `who=${who}`)

  const hasTokenBox = await a.eval(
    `!!document.querySelector('input[placeholder*="token" i], input[type="password"]')`,
  )
  step('2. 市场页没有手贴 token 的输入框', !hasTokenBox, hasTokenBox ? '还在' : '已删')

  // ---- 团队页 ----
  await a.goto(`${CFG.web}/team`)
  await a.waitFor(`!!document.querySelector('[data-test="member-row"]')`, { label: '成员表', timeout: 30000 })
  const members = await a.eval(
    `[...document.querySelectorAll('[data-test="member-row"]')].map((e) => e.innerText.trim())`,
  )
  step('3. 团队页列出组织成员', members.length >= 2, members.join(' / '))

  // ---- 改组织角色（不选团队时改的是组织角色）----
  // 找另一个成员：自己不能改（服务端会挡），要拿别人的行来试。
  const other = members.find((m) => m.trim() !== who.trim())
  if (!other) throw new Error('只有自己一个成员，改不了别人的角色')
  await pickOption(a, 'tr', other, 'publisher')
  await waitRole(a, other, 'publisher')
  await pickOption(a, 'tr', other, 'member')
  await waitRole(a, other, 'member')
  const after = await roleOf(a, other)
  step('4. 改组织角色（publisher → member）生效', after === 'member', `${other} → ${after}`)
  await pickOption(a, 'tr', other, 'publisher')
  await waitRole(a, other, 'publisher')
  step('5. 改回 publisher 生效', (await roleOf(a, other)) === 'publisher')

  // ---- 选中团队：列标题变成「团队角色」----
  await a.eval(`(() => {
    const select = document.querySelector('[data-test="team-select"]');
    const option = [...select.options].find((o) => o.textContent.trim() === ${JSON.stringify(team.name)});
    if (!option) throw new Error('下拉里没有团队 ' + ${JSON.stringify(team.name)});
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`)
  await sleep(1200)
  const columnTitle = await a.eval(`(() => {
    const th = [...document.querySelectorAll('th')].find((t) => t.innerText.includes('角色'));
    return th ? th.innerText.trim() : null;
  })()`)
  step('6. 选中团队后列标题变成「团队角色」', columnTitle?.startsWith('团队角色') === true, `列标题=${columnTitle}`)

  // ---- 生成邀请 ----
  await a.realClick('[data-test="invite"]')
  await a.waitFor(`!!document.querySelector('[data-test="invite-link"]')`, { label: '邀请链接', timeout: 30000 })
  const inviteLink = await a.text('[data-test="invite-link"]')
  step('7. 生成邀请链接', !!inviteLink && inviteLink.includes('/invite/'), inviteLink)

  // ---- B 打开链接 → 登录 → 接受 ----
  const ctxB = await newContext(port)
  const b = await newPage(port, ctxB)
  await b.goto(inviteLink)
  await b.waitFor(`!!document.querySelector('[data-test="login-first"]')`, { label: '登录后接受', timeout: 30000 })
  step('8. B 打开邀请链接，未登录时提示「登录后接受」', true)

  await b.waitApp()
  await b.realClick('[data-test="login-first"]')
  await b.waitFor(`location.pathname === '/login'`, { label: 'B 落到登录页', timeout: 20000 })
  await login(b, CFG.userB, CFG.passB, `location.pathname.startsWith('/invite/')`, await b.url())
  await b.waitFor(`!!document.querySelector('[data-test="accept"]')`, { label: '接受按钮', timeout: 30000 })
  await b.realClick('[data-test="accept"]')
  await b.waitFor(`!!document.querySelector('[data-test="done"]')`, { label: '已加入', timeout: 30000 })
  step('9. B 登录后接受邀请，页面显示「已加入」', !!(await b.text('[data-test="done"]')))

  // ---- 审核队列 → 详情 → 批准 ----
  await a.goto(`${CFG.web}/reviews`)
  await a.waitFor(`!!document.querySelector('[data-test="review-item"]')`, { label: '待审项', timeout: 30000 })
  const queueText = await a.text('[data-test="review-item"]')
  step('10. A 在审核队列看到待审版本', queueText.includes(CFG.slug), queueText.replace(/\s+/g, ' ').slice(0, 80))

  // 队列里可能还留着上几轮没批准的版本——点「当前这一版」那张，别点错
  await a.eval(`(() => {
    const cards = [...document.querySelectorAll('[data-test="review-item"]')];
    const hit = cards.find((c) => c.innerText.includes(${JSON.stringify(version)}));
    if (!hit) throw new Error('队列里没有版本 ' + ${JSON.stringify(version)});
    hit.setAttribute('data-e2e-target', '1');
    return true;
  })()`)
  await a.realClick('[data-e2e-target]')
  await a.waitFor(`!!document.querySelector('[data-test="skill-md"]')`, { label: 'SKILL.md 正文', timeout: 30000 })
  const md = await a.text('[data-test="skill-md"]')
  const diffRows = await a.eval(`document.querySelectorAll('[data-test="diff-row"]').length`)
  step('11. 详情页有 SKILL.md 正文与文件级差异', md.includes(CFG.slug) && diffRows > 0,
    `正文 ${md.length} 字符，差异行 ${diffRows}`)

  await a.realClick('[data-test="approve"]')
  await a.waitFor(
    `(document.querySelector('[data-test="version-status"]')||{}).innerText?.includes('published')`,
    { label: '版本状态变 published', timeout: 30000 },
  )
  step('12. 点「通过」后版本状态变 published', true)

  // ---- 负例：B 不是 reviewer，看不到队列 ----
  await b.goto(`${CFG.web}/reviews`)
  await b.waitFor(`!!document.body`, { label: 'B 的队列页', timeout: 30000 })
  await sleep(2000)
  const bItems = await b.eval(`document.querySelectorAll('[data-test="review-item"]').length`)
  const bBody = await b.eval('document.body.innerText')
  step('13. 负例：B（非 reviewer）打开审核队列是空的',
    bItems === 0 && bBody.includes('没有待审的版本'), `条目 ${bItems} 个`)

  // ---- 负例：唯一 owner 改自己的角色 ----
  await a.goto(`${CFG.web}/team`)
  await a.waitFor(`!!document.querySelector('[data-test="member-row"]')`, { label: '成员表', timeout: 30000 })
  await a.eval(`(() => {
    // 按名字精确找自己的那一行：靠 'Two' 排除别人，换个名字就失效了。
    const row = [...document.querySelectorAll('tr')].find((r) => {
      const name = r.querySelector('[data-test="member-row"]');
      return name && name.textContent.trim() === ${JSON.stringify(who)};
    });
    if (!row) throw new Error('找不到自己的行');
    const select = row.querySelector('[data-test="role-edit"]');
    if (!select) throw new Error('这一行没有角色下拉');
    const option = [...select.options].find((o) => o.textContent.trim() === 'member');
    if (!option) throw new Error('下拉里没有 member');
    select.value = option.value;
    select.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`)
  await a.waitFor(`!!document.querySelector('[data-test="error"]')`, { label: '页面报错提示', timeout: 20000 })
  const errText = await a.text('[data-test="error"]')
  step('14. 负例：唯一 owner 改自己的角色，页面报错', errText.length > 0, errText)

  // ---- 登出：SSO 会话也要一起结束 ----
  // 只清 localStorage 不算登出：浏览器里 Keycloak 的 SSO cookie 还在，
  // 下一个人点「登录」会被免密放行，权限就落到他头上了。
  await a.goto(`${CFG.web}/`)
  await a.waitFor(`!!document.querySelector('[data-test="logout"]')`, { label: '登出按钮' })
  await a.realClick('[data-test="logout"]')
  try {
    await a.waitFor(`location.pathname === '/login'`, { label: '登出后回到登录页', timeout: 30000 })
  } catch (e) {
    console.log('  诊断 url =', await a.url())
    console.log('  诊断 body =', JSON.stringify((await a.eval('document.body.innerText')).slice(0, 200)))
    throw e
  }
  step('15. 登出后回到登录页（Keycloak 的 SSO 会话一起结束）', true)

  // 判据不是「跳走了」，而是「再登录时 Keycloak 仍要密码」：
  // SSO cookie 还在的话会被直接放行，那等于没登出。
  await a.waitApp()
  await a.realClick('[data-test="login"]')
  await a.waitFor(
    `location.href.startsWith('${CFG.issuer.split('/realms')[0]}') || location.href.includes('code=')`,
    { label: '跳到 Keycloak 或被免密放行', timeout: 30000 },
  )
  await sleep(1500) // Keycloak 渲染登录页要一会儿
  const askedPassword = await a.eval(`!!document.querySelector('#username')`)
  const ssoKept = (await a.url()).includes('code=')
  step('16. 登出后再登录仍要求输入密码（SSO 已结束）', askedPassword && !ssoKept,
    ssoKept ? '被免密放行了——SSO 还在' : '')
} finally {
  proc.kill()
}

const failed = results.filter((r) => !r.ok)
console.log(`\n结果：${results.length - failed.length}/${results.length} 通过`)
if (failed.length) {
  console.log('失败项：')
  failed.forEach((f) => console.log('  - ' + f.name))
}
process.exit(failed.length ? 1 : 0)
