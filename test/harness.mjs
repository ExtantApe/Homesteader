// Test harness: throwaway Paper servers in fresh temp folders on 127.0.0.1:25614, driven from the console,
// with mineflayer bots as players. It never copies or touches a real server: every server is built here from
// downloaded jars, with made-up world and group names. Everything it makes lives under HS_TEST_WORK.
import { spawn } from 'node:child_process'
import { copyFileSync, existsSync, mkdirSync, readdirSync, rmSync, writeFileSync, readFileSync } from 'node:fs'
import path from 'node:path'
import mineflayer from 'mineflayer'

export const HOST = '127.0.0.1'
// 25614 is Homesteader's assigned test port. HS_TEST_PORT overrides it when something else holds it.
export const PORT = Number(process.env.HS_TEST_PORT ?? 25614)
export const WORK = process.env.HS_TEST_WORK
export const JARS = process.env.HS_TEST_JARS
if (!WORK || !JARS) throw new Error('Set HS_TEST_WORK (an empty temp folder) and HS_TEST_JARS (paper and plugin jars)')

const ROOT = new URL('../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs')
const built = existsSync(LIBS) ? readdirSync(LIBS).filter(f => /^Homesteader-.*\.jar$/.test(f)) : []
if (built.length !== 1) throw new Error(`Run ./gradlew clean build first: expected one jar in build/libs, found ${built.length}`)
export const JAR = path.join(LIBS, built[0])

process.setMaxListeners(100)
export const sleep = ms => new Promise(r => setTimeout(r, ms))
export async function until(fn, ms = 10000, step = 100) {
  const end = Date.now() + ms
  while (Date.now() < end) { if (await fn()) return true; await sleep(step) }
  return !!(await fn())
}

// ---------- results ----------
let passed = 0; const failures = []
export function check(name, ok, detail = '') {
  if (ok) { passed++; console.log('  ok   ' + name) }
  else { failures.push(name); console.log('  FAIL ' + name + (detail ? '\n         ' + String(detail).slice(0, 600) : '')) }
  return ok
}
export function section(title) { console.log('\n# ' + title) }
export function summary() {
  console.log(`\n${passed} passed, ${failures.length} failed`)
  for (const f of failures) console.log('  - ' + f)
  return failures.length
}

// ---------- servers ----------
export function jar(re) {
  const f = readdirSync(JARS).filter(n => re.test(n))
  if (f.length !== 1) throw new Error(`expected one jar matching ${re} in ${JARS}, found ${f.join(', ') || 'none'}`)
  return path.join(JARS, f[0])
}

/** A fresh server folder. The owner accepted Mojang's EULA for Homesteader's throwaway test servers (2026-11-07). */
export function freshServer(name, { paper, plugins = [], props = {}, config }) {
  const dir = path.join(WORK, name)
  rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
  mkdirSync(path.join(dir, 'plugins', 'Homesteader'), { recursive: true })
  copyFileSync(paper, path.join(dir, 'paper.jar'))
  for (const p of [JAR, ...plugins]) copyFileSync(p, path.join(dir, 'plugins', path.basename(p)))
  writeFileSync(path.join(dir, 'eula.txt'), 'eula=true\n')
  const all = { 'server-ip': HOST, 'server-port': PORT, 'online-mode': 'false', 'spawn-protection': 0,
    'enable-query': 'false', 'enable-rcon': 'false', 'view-distance': 4, 'simulation-distance': 4, 'max-players': 40,
    'enforce-secure-profile': 'false', 'white-list': 'false', 'enforce-whitelist': 'false', 'spawn-monsters': 'false', 'difficulty': 'peaceful', ...props }
  writeFileSync(path.join(dir, 'server.properties'), Object.entries(all).map(([k, v]) => `${k}=${v}`).join('\n') + '\n')
  if (config) writeConfig(dir, config)
  return dir
}
export function writeConfig(dir, text) { writeFileSync(path.join(dir, 'plugins/Homesteader/config.yml'), text) }
export function records(dir) {
  const f = path.join(dir, 'plugins/Homesteader/records.yml')
  return existsSync(f) ? readFileSync(f, 'utf8') : ''
}

export class Server {
  constructor({ dir, memory = '3G', jvm = [], java = 'java' }) { Object.assign(this, { dir, memory, jvm, java }); this.log = []; this.all = []; this.proc = null }
  async start(ms = 300000) {
    this.log = []
    this.proc = spawn(this.java, [`-Xmx${this.memory}`, ...this.jvm, '-jar', 'paper.jar', '--nogui'], { cwd: this.dir, stdio: ['pipe', 'pipe', 'pipe'] })
    let buf = ''
    const onData = d => {
      buf += d.toString()
      let i; while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i).replace(/\x1b\[[0-9;]*m/g, ''); buf = buf.slice(i + 1)
        this.log.push(line); this.all.push(line); if (process.env.HS_TEST_VERBOSE) console.log('    | ' + line)
      }
    }
    this.proc.stdout.on('data', onData); this.proc.stderr.on('data', onData)
    this.exited = new Promise(r => this.proc.once('exit', r))
    // If the suite itself dies, don't leave this throwaway server running.
    const proc = this.proc
    process.once('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') })
    if (!await until(() => this.log.some(l => /Done \([\d.,]+s\)! For help/.test(l)) || this.proc.exitCode !== null, ms, 250)) throw new Error('server did not start')
    if (this.proc.exitCode !== null) throw new Error('server exited:\n' + this.log.slice(-30).join('\n'))
  }
  cmd(c) { this.proc.stdin.write(c + '\n') }
  async ask(c, ms = 800) { const from = this.log.length; this.cmd(c); await sleep(ms); return this.log.slice(from) }
  mark() { return this.log.length }
  since(mark) { return this.log.slice(mark) }
  async waitLog(re, ms = 10000, from = 0) { return until(() => this.log.slice(from).some(l => re.test(l)), ms) }
  count(re, from = 0) { return this.log.slice(from).filter(l => re.test(l)).length }
  exceptions() { return this.all.filter(l => /^\s*at com\.extantape\.homesteader|Exception.*homesteader/i.test(l)) }
  async stop() {
    if (!this.proc || this.proc.exitCode !== null) return
    this.cmd('stop')
    const t = setTimeout(() => this.proc.kill('SIGKILL'), 90000)
    await this.exited; clearTimeout(t)
  }
  /** kill -9: no shutdown, no saving. */
  async kill9() { if (this.proc && this.proc.exitCode === null) { this.proc.kill('SIGKILL'); await this.exited } }
}

// ---------- bots ----------
const MOVE_PACKETS = new Set(['position', 'position_look', 'look', 'flying'])
export async function bot(name, { version } = {}) {
  const b = mineflayer.createBot({ host: HOST, port: PORT, username: name, auth: 'offline', version: version || false,
    checkTimeoutInterval: 120000, hideErrors: true })
  if (process.env.HS_TEST_BOT_STILL === '1') {
    // The bot sends no movement packets at all, only teleport confirmations. See below.
    const write = b._client.write.bind(b._client)
    b._client.write = (packet, params) => { if (!MOVE_PACKETS.has(packet)) write(packet, params) }
  }
  b.on('error', () => {})
  b.on('kicked', () => {})
  await new Promise((ok, fail) => {
    const t = setTimeout(() => fail(new Error(name + ' did not spawn')), 60000)
    b.once('spawn', () => { clearTimeout(t); ok() })
    b.once('end', r => { clearTimeout(t); fail(new Error(name + ' ended: ' + r)) })
  })
  // HS_TEST_BOT_STILL=1 turns bot physics off, so it sends no movement packets of its own. Paper 26.3
  // kicks moving bots behind ViaBackwards ("Invalid move player packet"). Teleports still work.
  if (process.env.HS_TEST_BOT_STILL === '1') b.physicsEnabled = false
  b.chatLog = []
  b.on('messagestr', m => b.chatLog.push(m))
  return b
}
export function quit(b) { return new Promise(r => { if (!b || b._ended) return r(); b._ended = true; b.once('end', r); b.quit(); setTimeout(r, 3000) }) }

/** Item counts by name, from the bot's own view of its inventory. */
export function inv(b) {
  const out = {}
  for (const it of b.inventory.items()) out[it.name] = (out[it.name] || 0) + it.count
  return out
}
export const has = (b, name) => (inv(b)[name] || 0)
