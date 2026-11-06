#!/usr/bin/env node
/**
 * Skill 调用统计工具。
 *
 * 子命令：
 *   hook      —— 由 Claude Code 的 PreToolUse hook 调用，静默落盘一条记录
 *   report    —— 回溯扫描全部历史 transcript + hook 日志，输出调用统计
 *   install   —— 把 PreToolUse hook 注册进 ~/.claude/settings.json（可重复执行）
 *   uninstall —— 移除该 hook
 */

const fs = require('fs');
const path = require('path');
const os = require('os');
const { spawnSync } = require('node:child_process');

const CLAUDE_DIR = path.join(os.homedir(), '.claude');
const LOG_DIR = path.join(CLAUDE_DIR, 'skill-usage');
const LOG_FILE = path.join(LOG_DIR, 'usage.jsonl');
const PROJECTS_DIR = path.join(CLAUDE_DIR, 'projects');

/** 把 cwd 压缩成 "skillMesh/apps/web" 这样的项目标签 */
function projectLabel(cwd) {
  if (!cwd) return 'unknown';
  const parts = String(cwd).split(/[\\/]/).filter(Boolean);
  return parts.slice(-2).join('/') || 'unknown';
}

/**
 * 读 hook 传进来的 JSON。
 *
 * 异步读：Windows 上 fs.readFileSync(0) 读管道会直接抛错。
 */
function readStdin() {
  return new Promise((resolve) => {
    let buf = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (d) => (buf += d));
    process.stdin.on('end', () => resolve(buf));
    process.stdin.on('error', () => resolve(''));
  });
}

/* ------------------------------ hook 模式 ------------------------------ */

async function runHook() {
  // 保活定时器：stdin 读完就不再持有事件循环的引用，
  // 没有它，进程会带着还没跑的推送逻辑一起退出。
  const keepAlive = setTimeout(() => {}, 60000);
  try {
    if (debug()) console.error('[skill-usage] 读取 stdin…');
    const raw = await readStdin();
    if (debug()) console.error('[skill-usage] 读到 ' + raw.length + ' 字节');
    if (!raw.trim()) return;
    let input;
    try {
      input = JSON.parse(raw);
    } catch (err) {
      if (debug()) {
        console.error('[skill-usage] stdin 解析失败：', err.message);
        console.error('[skill-usage] 原始内容：', JSON.stringify(raw.slice(0, 300)));
      }
      return; // 输入不是合法 JSON，静默放弃，绝不阻塞工具调用
    }

    const toolInput = input.tool_input || {};
    const skill = toolInput.skill || toolInput.name || '';
    if (!skill) return;

    const record = {
      ts: new Date().toISOString(),
      skill,
      args: toolInput.args || '',
      session_id: input.session_id || '',
      tool_use_id: input.tool_use_id || '',
      cwd: input.cwd || '',
      transcript: input.transcript_path || '',
      source: 'hook',
    };

    try {
      fs.mkdirSync(LOG_DIR, { recursive: true });
      fs.appendFileSync(LOG_FILE, JSON.stringify(record) + '\n', 'utf8');
    } catch {
      // 落盘失败不打扰会话
    }

    if (debug()) console.error('[skill-usage] 开始推送', record.skill);
    pushToCloud(record);
    if (debug()) console.error('[skill-usage] 推送结束');
  } catch (err) {
    // hook 里任何异常都不能影响会话
    if (debug()) console.error('[skill-usage] hook 出错：', err && err.message);
  } finally {
    clearTimeout(keepAlive);
  }
}

/** SKILLMESH_USAGE_DEBUG=1 时往 stderr 打排查信息。 */
function debug() {
  return process.env.SKILLMESH_USAGE_DEBUG === '1';
}

/**
 * 把这次调用实时推给 skillMesh 云端。
 *
 * 交给 CLI 发：登录态、令牌续期、限流退避，CLI 已经有一套，
 * 这里重写一遍只会多一处会漂移的认证逻辑。
 *
 * 上报内容：Skill 名、项目路径、参数摘要、Agent 名。
 * 收件方是 skillMesh 云端（地址取自 CLI 自己的配置），用户已知情并同意。
 * 要完全关掉推送：SKILLMESH_USAGE_PUSH=0。
 *
 * 推不出去也不能打扰会话——CLI 会把这条存进本地重试队列，等下次再发。
 */
function pushToCloud(record) {
  if (process.env.SKILLMESH_USAGE_PUSH === '0') return;
  const args = ['usage', 'push', '--skill', record.skill, '--agent', 'claude-code'];
  // 幂等键必须跟 watch 那边一致：否则 hook 报一次、watch 读到日志再报一次，
  // 同一次调用会被服务端算成两次。
  const eventId = eventIDOf(record);
  if (eventId) args.push('--event-id', eventId);
  // 路径统一用正斜杠：Windows 下反斜杠在传参时容易被吃掉。
  if (record.cwd) args.push('--project', String(record.cwd).replaceAll('\\', '/'));
  if (record.args) args.push('--args', String(record.args).slice(0, 1000));
  for (const bin of cliCandidates()) {
    const result = spawnSync(bin, args, { timeout: 15000, windowsHide: true, encoding: 'utf8' });
    // 有 error 说明这个可执行文件找不到，换下一个；找到了就到此为止。
    if (!result.error) return;
  }
}

/**
 * 这次调用的幂等键。
 *
 * 算法跟 CLI 侧 usage 包里的 eventIDOf 保持一致：优先用工具调用 id，
 * 没有就用「时间戳 + Skill 名」。两边必须一样。
 */
function eventIDOf(record) {
  const raw = record.tool_use_id || `${record.ts}-${record.skill}`;
  return String(raw).slice(0, 64);
}

/**
 * skillmesh CLI 的可执行文件。
 *
 * 安装时会把真实路径填进这个占位符（skillmesh usage hook install 替换），
 * 这样不依赖 PATH——CLI 未必装在 PATH 里。
 */
const CLI_PATH = '__SKILLMESH_CLI_PATH__';

/** skillmesh CLI 的可执行文件候选。SKILLMESH_CLI 可以指定绝对路径。 */
function cliCandidates() {
  const candidates = [];
  if (process.env.SKILLMESH_CLI) candidates.push(process.env.SKILLMESH_CLI);
  if (CLI_PATH && !CLI_PATH.startsWith('__')) candidates.push(CLI_PATH);
  candidates.push('skillmesh', 'skillmesh.exe');
  return candidates;
}

/* ------------------------------ 记录收集 ------------------------------ */

/** 扫描所有历史 transcript，抽出 Skill 工具调用 */
function collectFromTranscripts() {
  const records = [];
  if (!fs.existsSync(PROJECTS_DIR)) return records;

  const stack = [PROJECTS_DIR];
  while (stack.length) {
    const dir = stack.pop();
    let entries;
    try {
      entries = fs.readdirSync(dir, { withFileTypes: true });
    } catch {
      continue;
    }
    for (const entry of entries) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) {
        stack.push(full);
        continue;
      }
      if (!entry.name.endsWith('.jsonl')) continue;

      let content;
      try {
        content = fs.readFileSync(full, 'utf8');
      } catch {
        continue;
      }
      // 快速预筛，绝大多数会话文件根本不含 Skill 调用
      if (!content.includes('"name":"Skill"')) continue;

      for (const line of content.split('\n')) {
        if (!line.includes('"name":"Skill"')) continue;
        let obj;
        try {
          obj = JSON.parse(line);
        } catch {
          continue;
        }
        const blocks = obj.message && obj.message.content;
        if (!Array.isArray(blocks)) continue;
        for (const block of blocks) {
          if (!block || block.type !== 'tool_use' || block.name !== 'Skill') continue;
          const input = block.input || {};
          records.push({
            ts: obj.timestamp || '',
            skill: input.skill || input.name || '',
            args: input.args || '',
            session_id: obj.sessionId || '',
            tool_use_id: block.id || '',
            cwd: obj.cwd || '',
            source: 'transcript',
          });
        }
      }
    }
  }
  return records;
}

/** 读取 hook 实时日志 */
function collectFromHookLog() {
  if (!fs.existsSync(LOG_FILE)) return [];
  return fs
    .readFileSync(LOG_FILE, 'utf8')
    .split('\n')
    .filter((line) => line.trim())
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

/** 同一次调用可能被 transcript 与 hook 同时记录，按 session+tool_use_id 去重 */
function dedupe(records) {
  const seen = new Set();
  const unique = [];
  for (const record of records) {
    const key = `${record.session_id}|${record.tool_use_id}|${record.ts}`;
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push(record);
  }
  return unique;
}

/* ------------------------------ report 模式 ------------------------------ */

function buildReport(records) {
  const agg = new Map();
  for (const record of records) {
    if (!record.skill) continue;
    let item = agg.get(record.skill);
    if (!item) {
      item = { skill: record.skill, count: 0, sessions: new Set(), projects: new Set(), first: '', last: '', samples: [] };
      agg.set(record.skill, item);
    }
    item.count += 1;
    if (record.session_id) item.sessions.add(record.session_id);
    item.projects.add(projectLabel(record.cwd));
    if (record.ts) {
      if (!item.first || record.ts < item.first) item.first = record.ts;
      if (!item.last || record.ts > item.last) item.last = record.ts;
    }
    if (item.samples.length < 3 && record.args) item.samples.push(record.args);
  }
  return [...agg.values()].sort((a, b) => b.count - a.count || a.skill.localeCompare(b.skill));
}

function fmtTime(ts) {
  if (!ts) return '-';
  return ts.replace('T', ' ').slice(0, 16);
}

function runReport() {
  const records = dedupe([...collectFromTranscripts(), ...collectFromHookLog()]);
  const report = buildReport(records);
  const asJson = process.argv.includes('--json');

  if (asJson) {
    console.log(
      JSON.stringify(
        {
          total: records.length,
          skills: report.map((item) => ({
            skill: item.skill,
            count: item.count,
            sessions: item.sessions.size,
            projects: [...item.projects],
            first: item.first,
            last: item.last,
          })),
        },
        null,
        2
      )
    );
    return;
  }

  const sessions = new Set(records.map((r) => r.session_id).filter(Boolean));
  console.log(`\nSkill 调用统计：${report.length} 个 skill，${records.length} 次调用，分布在 ${sessions.size} 个会话\n`);

  const nameWidth = Math.max(8, ...report.map((item) => item.skill.length));
  const head = ['调用次数'.padEnd(8), '会话数'.padEnd(8), '项目数'.padEnd(8), '最近调用'.padEnd(18), 'skill'].join(' ');
  console.log(head);
  console.log('-'.repeat(head.length + 12));
  for (const item of report) {
    console.log(
      [
        String(item.count).padEnd(8),
        String(item.sessions.size).padEnd(8),
        String(item.projects.size).padEnd(8),
        fmtTime(item.last).padEnd(18),
        item.skill,
      ].join(' ')
    );
  }
  console.log('');
}

/* ------------------------------ 入口 ------------------------------ */

const command = process.argv[2] || 'hook';
try {
  if (command === 'hook') void runHook();
  else if (command === 'report') runReport();
  else console.log(`未知子命令: ${command}`);
} catch (err) {
  // hook 模式下任何异常都不能影响会话
  if (command === 'hook') {
    if (debug()) console.error('[skill-usage] hook 出错：', err && err.message);
    process.exit(0);
  }
  console.error(err.message);
  process.exit(1);
}
