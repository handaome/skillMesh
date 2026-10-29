// 临时截图脚本：登录后逐页截图，用来肉眼检查改版效果。
// 用法：node e2e/shot.mjs [输出目录]
import { mkdirSync } from 'node:fs'
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

  // 展开文件浏览，点开一个文件
  await page.realClick('[data-test="browse-files"]')
  await sleep(2000)
  await shot(page, 'mine-files')
  const file = await page.eval(`(() => {
    const el = [...document.querySelectorAll('[data-test="tree-file"]')]
      .find((e) => e.innerText.includes('SKILL.md'));
    if (!el) return null;
    el.click();
    return el.innerText.trim();
  })()`)
  console.log('点了文件:', file)
  await sleep(2000)
  await shot(page, 'mine-file-content')

  // 编辑：点「编辑」→ 改内容 → 保存
  const canEdit = await page.eval(`!!document.querySelector('[data-test="start-edit"]')`)
  console.log('有编辑按钮:', canEdit)
  if (canEdit) {
    await page.realClick('[data-test="start-edit"]')
    await sleep(600)
    await shot(page, 'mine-editing')
    await page.eval(`(() => {
      const el = document.querySelector('[data-test="file-editor"]');
      el.value = '# 在控制台改的内容\\n';
      el.dispatchEvent(new Event('input', { bubbles: true }));
      return true;
    })()`)
    await sleep(300)
    await page.realClick('[data-test="save-file"]')
    await sleep(5000)
    await shot(page, 'mine-saved')
    console.log('保存提示:', await page.text('[data-test="save-notice"]'))
  }

  // 市场页的浏览入口
  await page.goto(`${CFG.web}/`)
  await sleep(1000)
  await page.realClick('[data-test="search"]')
  await sleep(1500)
  const item = await page.eval(`(() => {
    const el = document.querySelector('[data-test="skill-item"]');
    if (!el) return null;
    el.click();
    return el.innerText.slice(0, 40);
  })()`)
  console.log('市场页点了:', item)
  // 版本列表接口在远程 PG 上要 2 秒，等够
  await sleep(5000)
  const browse = await page.eval(`!!document.querySelector('[data-test="browse-files"]')`)
  console.log('市场页有浏览按钮:', browse)
  if (browse) {
    await page.realClick('[data-test="browse-files"]')
    await sleep(2500)
    await shot(page, 'market-files')
  }
} finally {
  proc.kill()
}
