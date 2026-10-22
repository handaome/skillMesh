/**
 * 轻量语法高亮。
 *
 * 不引 highlight.js 之类的库：Skill 里的文件就那么几种（md / sh / ps1 / py /
 * js / ts / json / yaml），一个几十行的正则高亮够用，而且省下一个几百 KB 的
 * 依赖——控制台是内网自部署的，依赖越少越好。
 *
 * 高亮结果是一段 HTML 字符串，**内容先转义再上色**：文件正文是不可信输入，
 * 直接拼进 innerHTML 就是 XSS。
 */

/** 转义 HTML 特殊字符。 */
function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
}

/** 语言的关键字与内建名。 */
const KEYWORDS: Record<string, string[]> = {
  sh: [
    'if', 'then', 'else', 'elif', 'fi', 'for', 'while', 'do', 'done', 'case',
    'esac', 'function', 'return', 'in', 'export', 'local', 'readonly', 'set',
    'unset', 'source', 'exit', 'shift', 'trap', 'echo', 'printf', 'test',
  ],
  ps1: [
    'if', 'else', 'elseif', 'foreach', 'for', 'while', 'do', 'until', 'switch',
    'break', 'continue', 'return', 'function', 'filter', 'param', 'begin',
    'process', 'end', 'try', 'catch', 'finally', 'throw', 'class', 'in',
  ],
  py: [
    'def', 'class', 'return', 'if', 'elif', 'else', 'for', 'while', 'break',
    'continue', 'pass', 'import', 'from', 'as', 'with', 'try', 'except',
    'finally', 'raise', 'lambda', 'yield', 'global', 'nonlocal', 'assert',
    'del', 'in', 'is', 'not', 'and', 'or', 'None', 'True', 'False',
  ],
  js: [
    'const', 'let', 'var', 'function', 'return', 'if', 'else', 'for', 'while',
    'do', 'switch', 'case', 'break', 'continue', 'new', 'delete', 'typeof',
    'instanceof', 'in', 'of', 'class', 'extends', 'super', 'this', 'import',
    'export', 'from', 'as', 'async', 'await', 'try', 'catch', 'finally',
    'throw', 'null', 'undefined', 'true', 'false',
  ],
  ts: [
    'const', 'let', 'var', 'function', 'return', 'if', 'else', 'for', 'while',
    'switch', 'case', 'break', 'continue', 'new', 'typeof', 'class', 'extends',
    'implements', 'interface', 'type', 'enum', 'public', 'private', 'protected',
    'readonly', 'static', 'async', 'await', 'try', 'catch', 'finally', 'throw',
    'import', 'export', 'from', 'as', 'null', 'undefined', 'true', 'false',
    'string', 'number', 'boolean', 'any', 'unknown', 'never', 'void',
  ],
}

/** 由扩展名推出语言。 */
export function languageOf(path: string): string {
  if (!path) return ''
  const name = path.split('/').pop() ?? path
  const ext = name.includes('.') ? name.slice(name.lastIndexOf('.') + 1).toLowerCase() : ''
  switch (ext) {
    case 'md':
    case 'markdown':
      return 'md'
    case 'sh':
    case 'bash':
    case 'zsh':
      return 'sh'
    case 'ps1':
      return 'ps1'
    case 'py':
      return 'py'
    case 'js':
    case 'mjs':
    case 'cjs':
      return 'js'
    case 'ts':
    case 'tsx':
      return 'ts'
    case 'json':
      return 'json'
    case 'yml':
    case 'yaml':
      return 'yaml'
    default:
      return ''
  }
}

/**
 * 高亮一段代码，返回 HTML 字符串。
 *
 * 顺序要紧：先抽字符串与注释（它们内部不该再上别的颜色），
 * 再抽数字，最后剩下的当普通文本。
 */
export function highlight(code: string, language: string): string {
  const escaped = escapeHtml(code)
  if (!language) return escaped

  if (language === 'md') return highlightMarkdown(escaped)
  if (language === 'json' || language === 'yaml') {
    return highlightKeyValue(escaped, language === 'json')
  }
  return highlightCode(escaped, language)
}

/**
 * 代码高亮：一次扫描出所有片段。
 *
 * 不用「先替换成占位符再逐类上色」——占位符里的索引会被后面的规则当成
 * 数字或关键字再吃一遍（`0` 里的 0 会被当数字高亮）。
 * 一次扫描就没有这个问题。
 */
function highlightCode(escaped: string, language: string): string {
  const keywords = KEYWORDS[language] ?? []
  const keywordSet = new Set(keywords)
  const commentStart = language === 'sh' || language === 'py' ? '#' : '//'

  const out: string[] = []
  let plain = ''
  let i = 0

  const flushPlain = () => {
    if (plain) {
      out.push(plain)
      plain = ''
    }
  }

  while (i < escaped.length) {
    const rest = escaped.slice(i)

    // 字符串
    const stringMatch = rest.match(/^("(?:[^"\\]|\\.)*"|'(?:[^'\\]|\\.)*'|`(?:[^`\\]|\\.)*`)/)
    if (stringMatch) {
      flushPlain()
      out.push(`<span class="sm-hl-str">${stringMatch[0]}</span>`)
      i += stringMatch[0].length
      continue
    }

    // 块注释（js/ts）
    if (language === 'js' || language === 'ts') {
      const blockMatch = rest.match(/^\/\*[\s\S]*?\*\//)
      if (blockMatch) {
        flushPlain()
        out.push(`<span class="sm-hl-com">${blockMatch[0]}</span>`)
        i += blockMatch[0].length
        continue
      }
    }

    // 行注释
    if (rest.startsWith(commentStart)) {
      const end = rest.indexOf('\n')
      const text = end === -1 ? rest : rest.slice(0, end)
      flushPlain()
      out.push(`<span class="sm-hl-com">${text}</span>`)
      i += text.length
      continue
    }

    // 标识符：可能是关键字
    const wordMatch = rest.match(/^[A-Za-z_$][A-Za-z0-9_$]*/)
    if (wordMatch) {
      const word = wordMatch[0]
      if (keywordSet.has(word)) {
        flushPlain()
        out.push(`<span class="sm-hl-kw">${word}</span>`)
      } else {
        plain += word
      }
      i += word.length
      continue
    }

    // 数字
    const numMatch = rest.match(/^\d+(\.\d+)?/)
    if (numMatch) {
      flushPlain()
      out.push(`<span class="sm-hl-num">${numMatch[0]}</span>`)
      i += numMatch[0].length
      continue
    }

    plain += escaped[i]
    i += 1
  }
  flushPlain()
  return out.join('')
}

/** Markdown：标题、代码块、行内代码。 */
function highlightMarkdown(escaped: string): string {
  const out: string[] = []
  let plain = ''
  let i = 0

  const flushPlain = () => {
    if (plain) {
      out.push(plain)
      plain = ''
    }
  }

  while (i < escaped.length) {
    const rest = escaped.slice(i)

    // 围栏代码块
    const fenceMatch = rest.match(/^```[\s\S]*?```/)
    if (fenceMatch) {
      flushPlain()
      out.push(`<span class="sm-hl-codeblock">${fenceMatch[0]}</span>`)
      i += fenceMatch[0].length
      continue
    }

    // 行内代码
    const inlineMatch = rest.match(/^`[^`\n]+`/)
    if (inlineMatch) {
      flushPlain()
      out.push(`<span class="sm-hl-str">${inlineMatch[0]}</span>`)
      i += inlineMatch[0].length
      continue
    }

    // 行首的标题或分隔线
    const lineMatch = rest.match(/^(#{1,6}[^\n]*|---)(?=\n|$)/)
    if (lineMatch) {
      flushPlain()
      const cls = lineMatch[0].startsWith('#') ? 'sm-hl-head' : 'sm-hl-kw'
      out.push(`<span class="${cls}">${lineMatch[0]}</span>`)
      i += lineMatch[0].length
      continue
    }

    plain += escaped[i]
    i += 1
  }
  flushPlain()
  return out.join('')
}

/** JSON / YAML：键与字符串。 */
function highlightKeyValue(escaped: string, json: boolean): string {
  const out: string[] = []
  let plain = ''
  let i = 0

  const flushPlain = () => {
    if (plain) {
      out.push(plain)
      plain = ''
    }
  }

  while (i < escaped.length) {
    const rest = escaped.slice(i)

    const strMatch = rest.match(/^"(?:[^"\\]|\\.)*"/)
    if (strMatch) {
      // 后面跟着冒号的是键
      const after = rest.slice(strMatch[0].length)
      const isKey = /^\s*:/.test(after)
      flushPlain()
      out.push(`<span class="${isKey ? 'sm-hl-key' : 'sm-hl-str'}">${strMatch[0]}</span>`)
      i += strMatch[0].length
      continue
    }

    // YAML 的裸键
    if (!json) {
      const keyMatch = rest.match(/^[ \t]*[\w.-]+(?=\s*:)/)
      if (keyMatch) {
        flushPlain()
        out.push(`<span class="sm-hl-key">${keyMatch[0]}</span>`)
        i += keyMatch[0].length
        continue
      }
    }

    const literalMatch = rest.match(/^(true|false|null)\b/)
    if (literalMatch) {
      flushPlain()
      out.push(`<span class="sm-hl-kw">${literalMatch[0]}</span>`)
      i += literalMatch[0].length
      continue
    }

    const numMatch = rest.match(/^\d+(\.\d+)?/)
    if (numMatch) {
      flushPlain()
      out.push(`<span class="sm-hl-num">${numMatch[0]}</span>`)
      i += numMatch[0].length
      continue
    }

    plain += escaped[i]
    i += 1
  }
  flushPlain()
  return out.join('')
}
