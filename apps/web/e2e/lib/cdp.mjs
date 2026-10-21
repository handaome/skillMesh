// 浏览器驱动的最小 CDP 客户端：只依赖 node 内置的 WebSocket，不装任何第三方包。
//
// 为什么不用 Playwright：它要额外下载浏览器二进制（本机网络受限），
// 而开发机本来就有 Edge/Chrome——CDP 是它们都有的调试协议，
// 用内置 WebSocket 直连就够驱动真实浏览器了。
import { spawn } from 'node:child_process'
import { existsSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'

/** 常见浏览器位置。环境变量 SKILLMESH_E2E_BROWSER 可覆盖。 */
const BROWSER_CANDIDATES = [
  'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe',
  'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe',
  'C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe',
  '/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge',
  '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  '/usr/bin/google-chrome',
  '/usr/bin/chromium',
  '/usr/bin/chromium-browser',
]

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function findBrowser() {
  const override = process.env.SKILLMESH_E2E_BROWSER
  if (override) {
    if (!existsSync(override)) throw new Error(`SKILLMESH_E2E_BROWSER 指的浏览器不存在：${override}`)
    return override
  }
  const hit = BROWSER_CANDIDATES.find((p) => existsSync(p))
  if (!hit) {
    throw new Error(
      '找不到可用的浏览器（Edge 或 Chrome）。用 SKILLMESH_E2E_BROWSER 指向可执行文件。',
    )
  }
  return hit
}

/** 端口被占就换一个——本机可能有别的调试实例在跑。 */
async function pickPort(start = 9333) {
  for (let port = start; port < start + 20; port++) {
    const busy = await fetch(`http://127.0.0.1:${port}/json/version`)
      .then((r) => r.ok)
      .catch(() => false)
    if (!busy) return port
  }
  throw new Error('找不到空闲的调试端口')
}

/** 起一个无头浏览器实例，等它的调试端口就绪。 */
export async function launchBrowser() {
  const exe = findBrowser()
  const port = await pickPort()
  const userDataDir = mkdtempSync(join(tmpdir(), 'skillmesh-e2e-'))
  const proc = spawn(exe, [
    '--headless=new',
    `--remote-debugging-port=${port}`,
    `--user-data-dir=${userDataDir}`,
    '--no-first-run',
    '--no-default-browser-check',
    '--disable-gpu',
    '--window-size=1400,900',
  ], { stdio: 'ignore' })

  for (let i = 0; i < 80; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (res.ok) return { proc, port }
    } catch {}
    await sleep(250)
  }
  proc.kill()
  throw new Error('浏览器没起来')
}

async function openWs(url) {
  const ws = new WebSocket(url)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true })
    ws.addEventListener('error', reject, { once: true })
  })
  return ws
}

// 一个页面 = 一个标签页 + 它自己的 CDP 连接。
export class Page {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.pending = new Map()
    this.console = []
    this.net = []
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.method === 'Runtime.consoleAPICalled') {
        this.console.push(msg.params.type + ': ' +
          msg.params.args.map((a) => a.value ?? a.description ?? '').join(' '))
      } else if (msg.method === 'Runtime.exceptionThrown') {
        this.console.push('EXC: ' +
          (msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text))
      } else if (msg.method === 'Network.responseReceived') {
        this.net.push({ url: msg.params.response.url, status: msg.params.response.status })
      }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error
          ? reject(new Error(`${msg.error.message} ${JSON.stringify(msg.error.data ?? '')}`))
          : resolve(msg.result)
      }
    })
  }

  send(method, params = {}) {
    return new Promise((resolve, reject) => {
      const id = ++this.id
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  async eval(expression) {
    const r = await this.send('Runtime.evaluate', {
      expression, awaitPromise: true, returnByValue: true,
    })
    if (r.exceptionDetails) {
      throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
    }
    return r.result.value
  }

  async waitFor(expr, { timeout = 20000, label = '' } = {}) {
    const started = Date.now()
    let last = ''
    while (Date.now() - started < timeout) {
      try {
        const v = await this.eval(expr)
        if (v) return v
        last = String(v)
      } catch (e) {
        last = e.message
      }
      await sleep(200)
    }
    throw new Error(`等超时（${label || expr}）：${last}`)
  }

  async goto(url) {
    await this.send('Page.navigate', { url })
    await this.waitFor('!!document.body', { label: 'page load', timeout: 30000 })
  }

  async url() {
    return this.eval('location.href')
  }

  async text(selector) {
    return this.eval(
      `(() => { const el = document.querySelector(${JSON.stringify(selector)}); return el ? el.innerText.trim() : null })()`,
    )
  }

  // 真实鼠标点击：Vue 的事件委托与 Element Plus 的下拉都吃真实事件，
  // 脚本合成的 el.click() 未必触发。
  async realClick(selector) {
    const box = await this.eval(`(() => {
      const el = document.querySelector(${JSON.stringify(selector)});
      if (!el) return null;
      el.scrollIntoView({ block: 'center' });
      const r = el.getBoundingClientRect();
      return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
    })()`)
    if (!box) throw new Error('点不到：' + selector)
    for (const type of ['mousePressed', 'mouseReleased']) {
      await this.send('Input.dispatchMouseEvent', {
        type, x: box.x, y: box.y, button: 'left', clickCount: 1, buttons: 1,
      })
    }
    await sleep(150)
  }

  // 等 Vue 挂载完：body 存在不等于事件已经绑好。
  async waitApp() {
    await this.waitFor(
      `!!(document.querySelector('#app') && document.querySelector('#app').__vue_app__)`,
      { label: 'Vue 挂载', timeout: 20000 },
    )
  }

  async close() {
    try { await this.send('Page.close') } catch {}
  }
}

async function browserSession(port) {
  const res = await fetch(`http://127.0.0.1:${port}/json/version`)
  const version = await res.json()
  return new Page(await openWs(version.webSocketDebuggerUrl))
}

// 新建页面。给了 browserContextId 就是独立上下文（第二个账号用，不能共用 cookie）。
export async function newPage(port, browserContextId) {
  const browser = await browserSession(port)
  const { targetId } = await browser.send('Target.createTarget', {
    url: 'about:blank',
    browserContextId,
  })
  let wsUrl = null
  for (let i = 0; i < 40; i++) {
    const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()
    const hit = list.find((t) => t.id === targetId)
    if (hit?.webSocketDebuggerUrl) { wsUrl = hit.webSocketDebuggerUrl; break }
    await sleep(100)
  }
  if (!wsUrl) throw new Error('拿不到页面的 ws 地址')
  const page = new Page(await openWs(wsUrl))
  await page.send('Page.enable')
  await page.send('Runtime.enable')
  await page.send('Network.enable')
  return page
}

// 独立浏览器上下文 —— 第二个账号要用。
export async function newContext(port) {
  const browser = await browserSession(port)
  const { browserContextId } = await browser.send('Target.createBrowserContext', {
    disposeOnDetach: false,
  })
  return browserContextId
}
