// 只截图，不改数据：登录后逐页拍照，用于对比改版前后。
// 用法：node e2e/shot-only.mjs [输出目录]
import { mkdirSync, writeFileSync } from 'node:fs'
import { launchBrowser, newPage, sleep } from './lib/cdp.mjs'

const CFG = {
  web: process.env.SKILLMESH_E2E_WEB ?? 'http://127.0.0.1:5174',
  user: process.env.SKILLMESH_E2E_USER_A ?? 'dev-user',
  pass: process.env.SKILLMESH_E2E_PASS_A ?? 'dev',
}

const outDir = process.argv[2] ?? 'd:/tmp/skillmesh-shots'
mkdirSync(outDir, { recursive: true })

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
  await page.waitFor(`!!document.querySelector('[data-test="username"]')`, { label: '用户名框' })
  await page.waitApp()
  await setInput(page, '[data-test="username"]', CFG.user)
  await setInput(page, '[data-test="password"]', CFG.pass)
  await page.realClick('[data-test="submit"]')
  await page.waitFor(
    `location.href.startsWith('${CFG.web}') && !location.href.includes('/login')`,
    { label: '回到前端', timeout: 30000 },
  )
  await page.waitFor(`!!document.querySelector('[data-test="who"]')`, { label: '顶栏用户' })
}

async function shot(page, name, width = 1440, height = 900) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: false,
  })
  await sleep(700)
  const { data } = await page.send('Page.captureScreenshot', { format: 'jpeg', quality: 82 })
  writeFileSync(`${outDir}/${name}.jpg`, Buffer.from(data, 'base64'))
  console.log(`shot: ${outDir}/${name}.jpg`)
}

const { proc, port } = await launchBrowser()
try {
  const page = await newPage(port)
  await login(page)

  for (const [path, name] of [
    ['/', 'market'],
    ['/mine', 'mine'],
    ['/team', 'team'],
    ['/reviews', 'reviews'],
  ]) {
    await page.goto(`${CFG.web}${path}`)
    await sleep(1500)
    await shot(page, name)
    await shot(page, `${name}-390`, 390, 844)
  }

  // 登录页（未登录状态）
  const anon = await newPage(port)
  await anon.goto(`${CFG.web}/login`)
  await sleep(800)
  await shot(anon, 'login')
} finally {
  proc.kill()
}
