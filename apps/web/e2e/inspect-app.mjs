// 用 design-taste-frontend 的官方检查器量自己的页面。
//
// 为什么要自己写这个驱动：官方的 inspect.mjs 只接受 URL，
// 而控制台要 Keycloak 登录才看得到内容——它打开就是登录页。
// 这里借它的探针（probe/frame/layout/app）与判定（judge/judgeApp），
// 用本仓库自己的 CDP 客户端登录后逐页量。
//
// 用法：node e2e/inspect-app.mjs [输出目录]
import { mkdirSync, readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { launchBrowser, newPage, sleep } from './lib/cdp.mjs'

const SKILL = process.env.TASTE_SKILL_DIR
  ?? 'C:/Users/23929/.claude/skills/design-taste-frontend'

if (!existsSync(SKILL)) {
  console.error(`找不到 taste skill 目录：${SKILL}。用 TASTE_SKILL_DIR 指定。`)
  process.exit(2)
}

const { probe, frame, layout, app, colourShare } = await import(
  `file:///${join(SKILL, 'scripts/lib/probe.mjs').replace(/\\/g, '/')}`
)
const { judge, judgeLayout, judgeApp, APP_SKIP } = await import(
  `file:///${join(SKILL, 'scripts/lib/rules.mjs').replace(/\\/g, '/')}`
)

const CFG = {
  web: process.env.SKILLMESH_E2E_WEB ?? 'http://127.0.0.1:5174',
  issuer: (process.env.SKILLMESH_E2E_ISSUER ?? 'http://127.0.0.1:8180/kc/realms/skillmesh').replace(/\/$/, ''),
  user: process.env.SKILLMESH_E2E_USER_A ?? 'dev-user',
  pass: process.env.SKILLMESH_E2E_PASS_A ?? 'dev',
}

const outDir = process.argv[2] ?? 'd:/tmp/skillmesh-inspect'
mkdirSync(outDir, { recursive: true })

const PAGES = [
  ['/', 'market'],
  ['/mine', 'mine'],
  ['/team', 'team'],
  ['/reviews', 'reviews'],
]
const WIDTHS = [1440, 768, 390]

async function setInput(page, selector, value) {
  await page.eval(`(() => {
    const el = document.querySelector(${JSON.stringify(selector)});
    el.value = ${JSON.stringify(value)};
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
    return true;
  })()`)
}

async function login(page) {
  await page.goto(`${CFG.web}/login`)
  await page.waitFor(`!!document.querySelector('[data-test="login"]')`, { label: '登录按钮' })
  await page.waitApp()
  await page.realClick('[data-test="login"]')
  await page.waitFor(`location.href.startsWith('${CFG.issuer.split('/realms')[0]}')`, { label: '跳到 Keycloak' })
  await page.waitFor(`!!document.querySelector('#username')`, { label: '用户名框' })
  await setInput(page, '#username', CFG.user)
  await setInput(page, '#password', CFG.pass)
  await page.realClick('#kc-login')
  await page.waitFor(`location.href.startsWith('${CFG.web}')`, { label: '回到前端' })
  await page.waitFor(`!!document.querySelector('[data-test="who"]')`, { label: '顶栏用户' })
}

/** 在页面里跑探针：探针是自包含的函数体，直接序列化注入。 */
async function runProbe(page, fn, arg) {
  const expression = typeof fn === 'function'
    ? `(${fn.toString()})(${arg === undefined ? '' : JSON.stringify(arg)})`
    : fn
  const r = await page.send('Runtime.evaluate', {
    expression, awaitPromise: true, returnByValue: true,
  })
  if (r.exceptionDetails) {
    throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text)
  }
  return r.result?.value
}

async function shot(page, name, width, height) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: width < 600,
  })
  await sleep(900)
  const { data } = await page.send('Page.captureScreenshot', { format: 'jpeg', quality: 84 })
  const { writeFileSync } = await import('node:fs')
  writeFileSync(`${outDir}/${name}.jpg`, Buffer.from(data, 'base64'))
}

const findings = []
const { proc, port } = await launchBrowser()
try {
  const page = await newPage(port)
  await login(page)

  for (const [path, name] of PAGES) {
    for (const width of WIDTHS) {
      await page.send('Emulation.setDeviceMetricsOverride', {
        width, height: 900, deviceScaleFactor: 1, mobile: width < 600,
      })
      await page.goto(`${CFG.web}${path}`)
      await sleep(1600)
      // 等字体加载完再量：font-display: swap 下字体是异步到的，
      // 早一步测就会把「没加载」误报成字体回退。
      await page.eval('document.fonts.ready.then(() => true)').catch(() => {})
      await sleep(300)

      const m = await runProbe(page, probe)
      const lay = await runProbe(page, layout)
      const appM = await runProbe(page, app)
      const frameCheck = await runProbe(page, frame)

      await shot(page, `${name}-${width}`, width, 900)

      const allow = []
      // app 模式跳过页面级的规则（细线、图片数量、正文尺寸那些）：
      // 官方 inspect.mjs 就是这么分的——工作台不按落地页的标准判。
      const all = [
        ...judge(m, { width, allow }),
        ...(lay ? judgeLayout(lay, { width, allow }) : []),
      ]
      const list = all.filter((f) => !APP_SKIP.has(f.id) && f.id !== 'no-title' && f.id !== 'small-title')
      if (appM) list.push(...judgeApp(appM, m, { width, allow }))
      if (frameCheck && frameCheck.filled < 0.2) {
        list.push({
          level: 'WARN', id: 'empty-screen', width,
          message: `这一屏只有 ${Math.round(frameCheck.filled * 100)}% 的面积有内容。`,
          fix: '首屏要回答这个屏幕的问题：补上真实内容，或放一个刻意留白的版面。',
        })
      }

      for (const f of list) {
        findings.push({ page: name, ...f })
      }
      const bad = list.filter((f) => f.level === 'FAIL')
      console.log(`${name} @${width}: ${list.length} 条（FAIL ${bad.length}）`)
      for (const f of list) {
        console.log(`  [${f.level}] ${f.id}: ${f.message}`)
      }
    }
  }
} finally {
  proc.kill()
}

const fails = findings.filter((f) => f.level === 'FAIL')
const warns = findings.filter((f) => f.level === 'WARN')
console.log(`\n合计：FAIL ${fails.length} · WARN ${warns.length}`)
if (fails.length) {
  console.log('\nFAIL 明细：')
  for (const f of fails) console.log(`  ${f.page} @${f.width} ${f.id}: ${f.message}`)
}
process.exit(fails.length ? 1 : 0)
