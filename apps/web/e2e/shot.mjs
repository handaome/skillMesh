// 临时截图脚本：登录后逐页截图，用来肉眼检查改版效果。
// 用法：node e2e/shot.mjs [输出目录]
import { mkdirSync } from 'node:fs'
import { launchBrowser, newPage, sleep } from './lib/cdp.mjs'

const CFG = {
  web: process.env.SKILLMESH_E2E_WEB ?? 'http://127.0.0.1:5174',
  issuer: (process.env.SKILLMESH_E2E_ISSUER ?? 'http://127.0.0.1:8180/kc/realms/skillmesh').replace(/\/$/, ''),
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

async function shot(page, name, width = 1440, height = 900) {
  await page.send('Emulation.setDeviceMetricsOverride', {
    width, height, deviceScaleFactor: 1, mobile: false,
  })
  await sleep(700)
  const { data } = await page.send('Page.captureScreenshot', { format: 'jpeg', quality: 82 })
  const { writeFileSync } = await import('node:fs')
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
    await sleep(1200)
    await shot(page, name)
  }

  await page.goto(`${CFG.web}/`)
  await sleep(1000)
  await shot(page, 'market-390', 390, 844)

  // 选中一个 Skill，看详情面板
  await page.goto(`${CFG.web}/mine`)
  await sleep(1500)
  await page.realClick('[data-test="mine-item"]')
  await sleep(1500)
  await shot(page, 'mine-detail')
} finally {
  proc.kill()
}
