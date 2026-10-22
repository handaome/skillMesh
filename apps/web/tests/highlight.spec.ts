import { describe, expect, it } from 'vitest'
import { highlight, languageOf } from '../src/utils/highlight'

describe('highlight', () => {
  it('按扩展名认出语言', () => {
    expect(languageOf('SKILL.md')).toBe('md')
    expect(languageOf('scripts/run.sh')).toBe('sh')
    expect(languageOf('a/b/c.ps1')).toBe('ps1')
    expect(languageOf('main.py')).toBe('py')
    expect(languageOf('config.yaml')).toBe('yaml')
    expect(languageOf('data.json')).toBe('json')
    expect(languageOf('README')).toBe('')
    expect(languageOf('')).toBe('')
  })

  it('转义 HTML：正文是不可信输入', () => {
    const html = highlight('<script>alert(1)</script>', 'md')
    expect(html).not.toContain('<script>')
    expect(html).toContain('&lt;script&gt;')
  })

  it('高亮关键字与字符串', () => {
    const html = highlight('if [ -f "$HOME/x" ]; then echo 1; fi', 'sh')
    expect(html).toContain('sm-hl-kw')
    expect(html).toContain('sm-hl-str')
  })

  it('注释不上色成关键字', () => {
    const html = highlight('# if then fi\nls', 'sh')
    expect(html).toContain('sm-hl-com')
  })

  it('JSON 的键与值分开', () => {
    const html = highlight('{"name": "x", "n": 1}', 'json')
    expect(html).toContain('sm-hl-key')
    expect(html).toContain('sm-hl-str')
  })

  it('没认出的语言就原样转义返回', () => {
    expect(highlight('a < b & c', '')).toBe('a &lt; b &amp; c')
  })
})
