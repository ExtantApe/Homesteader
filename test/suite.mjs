// Homesteader acceptance suite. Usage: node suite.mjs <26.1.2|26.2|26.3> [only=name,name]
// Builds a throwaway server from downloaded jars, with made-up worlds and groups, and drives it with bots.
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { execSync } from 'node:child_process'
import { createHash } from 'node:crypto'
import { bot, check, freshServer, has, inv, jar, quit, records, section, Server, sleep, summary, until, writeConfig } from './harness.mjs'

const MC = process.argv[2]
if (!['26.1.2', '26.2', '26.3'].includes(MC)) throw new Error('usage: node suite.mjs <26.1.2|26.2|26.3> [only=a,b]')
const ONLY = (process.argv.find(a => a.startsWith('only=')) || '').slice(5).split(',').filter(Boolean)
const want = n => ONLY.length === 0 || ONLY.includes(n)
// The bots speak 1.21.11 and connect through ViaVersion and ViaBackwards. Homesteader runs entirely on the
// server, so the client version only affects how the bots see the world, not what the plugin does.
const BOT_VERSION = process.env.HS_TEST_BOT_VERSION || '1.21.11'
// Paper 26.3 kicks bots whose movement packets come through ViaBackwards ("Invalid move player packet"),
// so on 26.3 the bots send none and are moved only by server teleports. Homesteader never relies on the
// client moving, but it means 26.3 isn't tested with a walking player.
if (MC === '26.3' && process.env.HS_TEST_BOT_STILL === undefined) process.env.HS_TEST_BOT_STILL = '1'

const plugins = [jar(/^multiverse-core/), jar(/^multiverse-inventories/), jar(/^GriefPrevention-16\.18\.7/),
  jar(/^ViaVersion/), jar(/^ViaBackwards/)]

// Made-up groups. alpha: items and a move. beta: items and commands, one of which fails. delta: for the
// "existing players" check, off at first. tiny: small border and claims. gamma: an MV-Inventories group
// Homesteader doesn't know, so it must do nothing there.
const config = ({ delta = false, alphaMove = true } = {}) => `
settle-ticks: 10
claims: auto
groups:
  alpha:
    enabled: true
    worlds: []
    starter:
      items: [CHEST, BREAD 8, OAK_SAPLING 2]
    move:
      enabled: ${alphaMove}
      world: alpha
      center: spawn
      min-distance: 100
      max-distance: 400
      claim-buffer: 16
      attempts: 24
  beta:
    enabled: true
    worlds: []
    starter:
      items: [CHEST, APPLE 3]
      commands: ["nosuchcommand {player}", "give {player} stick"]
  omega:
    enabled: true
    worlds: []
    skip-existing-players: false
    starter:
      items: []
      commands: ["give {player} stick"]
    messages:
      starter: "<green>Your stick has arrived.</green>"
  delta:
    enabled: ${delta}
    worlds: []
    starter:
      items: [CHEST, COOKIE 5]
  tiny:
    enabled: true
    worlds: [tiny]
    starter:
      items: [CHEST]
    move:
      enabled: true
      world: tiny
      center: 0,0
      min-distance: 0
      max-distance: 2000
      claim-buffer: 12
      border-margin: 8
      attempts: 24
  hazard:
    enabled: true
    worlds: [hazard]
    starter:
      items: []
    move:
      enabled: true
      world: hazard
      center: 0,0
      min-distance: 0
      max-distance: 70
      claim-buffer: 0
      border-margin: 0
      attempts: 24
  sea:
    enabled: true
    worlds: [sea]
    starter:
      items: [CHEST]
    move:
      enabled: true
      world: sea
      center: 0,0
      min-distance: 0
      max-distance: 60
      claim-buffer: 0
      border-margin: 0
      attempts: 6
messages:
  starter: "<green>Welcome! Here's a starter chest and a little food.</green>"
  moved: "<gray>You've been given a quiet spot of your own, away from spawn.</gray>"
  no-spot: "<yellow>We couldn't find a safe spot away from spawn, so you're starting here.</yellow>"
  no-room: "<yellow>Your starter is waiting. Free up {slots} inventory slot(s) and it will arrive.</yellow>"
  record-problem: "<red>Homesteader's record file is damaged, so it's giving nothing. See the server log.</red>"
`

let dir = freshServer('mc-' + MC, { paper: jar(new RegExp('^paper-' + MC.replace(/\./g, '\\.'))), plugins, config: config() })
// GriefPrevention's anti-spam login cooldown would block the quick relogs these tests make.
mkdirSync(path.join(dir, 'plugins/GriefPreventionData'), { recursive: true })
writeFileSync(path.join(dir, 'plugins/GriefPreventionData/config.yml'), 'GriefPrevention:\n  MaxPlayersPerIpAddress: 100\n  Spam:\n    Enabled: false\n    LoginCooldownSeconds: 0\n  Claims:\n    Mode:\n      world: Survival\n      tiny: Survival\n')
const JAVA = process.env.HS_TEST_JAVA || 'java' // Paper 26.x needs Java 25.
let s = new Server({ dir, java: JAVA })
const bots = new Map()

async function start(jvm = []) { s = new Server({ dir, jvm, java: JAVA }); await s.start() }
async function restart(jvm = []) { for (const b of bots.values()) await quit(b); bots.clear(); await s.stop(); await start(jvm) }
async function join(name) { const b = await bot(name, { version: BOT_VERSION }); bots.set(name, b); await sleep(1500); return b }
async function leave(name) { await quit(bots.get(name)); bots.delete(name); await sleep(1000) }
async function world(name) {
  const lines = await s.ask(`data get entity ${name} Dimension`, 600)
  const m = lines.join('\n').match(/entity data: "([^"]+)"/)
  if (!m) return null
  const w = m[1].replace(/^minecraft:/, '')
  return w === 'overworld' ? 'world' : w
}
async function go(name, w, wait = 2500) { s.cmd(`mvtp ${name} ${w}`); if (wait) await sleep(wait) }
async function pos(name) {
  const lines = await s.ask(`data get entity ${name} Pos`, 600)
  const m = lines.join('\n').match(/entity data: \[([-\d.]+)d, ([-\d.]+)d, ([-\d.]+)d\]/)
  return m ? m.slice(1).map(Number) : null
}
const gave = (name, group, from = 0) => s.count(new RegExp(`Gave ${name} the starter for '${group}'`), from)
const kitAlpha = b => ({ chest: has(b, 'chest'), bread: has(b, 'bread'), sapling: has(b, 'oak_sapling') })
const kitBeta = b => ({ chest: has(b, 'chest'), apple: has(b, 'apple'), stick: has(b, 'stick') })
const eq = (a, b) => JSON.stringify(a) === JSON.stringify(b)

// ---------- setup ----------
section(`Setup on Paper ${MC}`)
await start()
check('Homesteader enabled', s.all.some(l => /\[Homesteader\] .*group\(s\) on/.test(l)), s.all.filter(l => /Homesteader/.test(l)).slice(-5).join('\n'))
for (const c of ['mv create alpha normal', 'mv create alpha_two normal --world-type flat', 'mv create beta normal --world-type flat',
  'mv create gamma normal --world-type flat', 'mv create delta normal --world-type flat', 'mv create tiny normal --world-type flat',
  'mv create hazard normal --world-type flat --no-structures', 'mv create sea normal --world-type flat --no-structures', 'mv create omega normal --world-type flat']) {
  await s.ask(c, 6000)
}
// Groups written straight into MV-Inventories' file, then reloaded: its create-group command won't take 'all'.
writeFileSync(path.join(dir, 'plugins/Multiverse-Inventories/groups.yml'), 'groups:\n' + Object.entries({
  default: ['world', 'world_nether', 'world_the_end'], alpha: ['alpha', 'alpha_two'], beta: ['beta'], gamma: ['gamma'],
  delta: ['delta'], omega: ['omega'], tiny: ['tiny'], hazard: ['hazard'], sea: ['sea'],
}).map(([g, ws]) => `  ${g}:\n    worlds:\n${ws.map(w => `    - ${w}\n`).join('')}    shares:\n    - all\n`).join(''))
await s.ask('mvinv reload', 3000)
// GriefPrevention drops modes for worlds that didn't exist when it started, so turn claims on in 'tiny' now.
{
  const gp = path.join(dir, 'plugins/GriefPreventionData/config.yml')
  const t = readFileSync(gp, 'utf8')
  if (!/\n      tiny: Survival/.test(t)) writeFileSync(gp, t.replace(/\n    Mode:\n/, '\n    Mode:\n      tiny: Survival\n'))
  await s.ask('gpreload', 2000)
}
const groupsOut = await s.ask('mvinv list', 1500)
check('MV-Inventories groups exist', ['alpha', 'beta', 'gamma', 'delta', 'tiny'].every(g => groupsOut.join(' ').includes(g)), groupsOut.join('\n'))
const reload = await s.ask('homesteader reload', 1500)
console.log('    ' + reload.filter(l => /Homesteader/.test(l)).join('\n    '))


const spawnOf = async w => {
  const m = (await s.ask(`mv info ${w}`, 1200)).join('\n').match(/Spawn Location: X: (-?[\d.]+) Y: (-?[\d.]+) Z: (-?[\d.]+)/)
  return m ? m.slice(1).map(Number) : null
}
const dist2d = (a, b) => Math.hypot(a[0] - b[0], a[2] - b[2])
// Offline-mode UUIDs are derived from the name, so they're the same across restarts.
const offlineUuid = name => {
  const h = createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const rec = (name, group) => {
  const lines = records(dir).split('\n')
  const i = lines.indexOf(`  ${offlineUuid(name)}:`)
  if (i < 0) return null
  let j = i + 1
  while (j < lines.length && lines[j].startsWith('    ')) {
    if (lines[j] === `    ${group}:`) {
      const out = []
      for (let k = j + 1; k < lines.length && lines[k].startsWith('      '); k++) out.push(lines[k].trim())
      return out.join(' ')
    }
    j++
  }
  return null
}
const SAVED_EXC = []
const noteExceptions = () => { SAVED_EXC.push(...s.exceptions()) }

// ---------- off groups ----------
if (want('off')) {
  section('Off groups')
  const o = await join('Olive')
  await sleep(2000)
  check('nothing in an unconfigured group (lobby)', eq(inv(o), {}) && gave('Olive', '.*') === 0, JSON.stringify(inv(o)))
  await go('Olive', 'gamma', 4000)
  check('nothing in an MV-Inventories group Homesteader doesn\'t know', (await world('Olive')) === 'gamma' && eq(inv(o), {}) && gave('Olive', '.*') === 0, JSON.stringify(inv(o)))
  check('no record for Olive', rec('Olive', 'gamma') === null && !records(dir).includes('gamma:'))
  await leave('Olive')
}

// ---------- once only, per group, commands ----------
if (want('once')) {
  section('Once only')
  const a = await join('Ann')
  const sp = await spawnOf('alpha')
  const mA = s.mark()
  await go('Ann', 'alpha', 0)
  await s.waitLog(/Moved Ann |No safe spot for Ann/, 90000, mA); await sleep(2000)
  const p = await pos('Ann')
  const d = sp && p ? dist2d(sp, p) : NaN
  check('first visit: starter in the alpha inventory', eq(kitAlpha(a), { chest: 1, bread: 8, sapling: 2 }), JSON.stringify(inv(a)))
  check(`moved once, between 100 and 400 blocks from spawn (${Math.round(d)})`, d >= 99 && d <= 401 && s.count(/Moved Ann /) === 1, `spawn ${sp} pos ${p}`)
  check('both messages shown', a.chatLog.some(m => /quiet spot/.test(m)) && a.chatLog.some(m => /starter chest/.test(m)), a.chatLog.join(' | '))
  await go('Ann', 'alpha_two', 3000)
  check('second world in the same group: nothing more', gave('Ann', 'alpha') === 1 && eq(kitAlpha(a), { chest: 1, bread: 8, sapling: 2 }), JSON.stringify(inv(a)))
  await go('Ann', 'world', 3000)
  check('lobby inventory is separate and empty', eq(inv(a), {}), JSON.stringify(inv(a)))
  await go('Ann', 'alpha', 4000)
  check('leaving and returning: nothing more, no second move', gave('Ann', 'alpha') === 1 && s.count(/Moved Ann /) === 1 && eq(kitAlpha(a), { chest: 1, bread: 8, sapling: 2 }), JSON.stringify(inv(a)))
  await leave('Ann'); const a2 = await join('Ann'); await sleep(2500)
  check('relog: nothing more', gave('Ann', 'alpha') === 1 && eq(kitAlpha(a2), { chest: 1, bread: 8, sapling: 2 }), JSON.stringify(inv(a2)))
  s.cmd('kill Ann'); await sleep(4000)
  check('death and respawn: nothing more', gave('Ann', 'alpha') === 1 && s.count(/Moved Ann /) === 1)

  section('Per group and starter commands')
  const m0 = s.mark()
  await go('Ann', 'beta', 4000)
  const b = bots.get('Ann')
  check('second group gives its own starter', eq(kitBeta(b), { chest: 1, apple: 3, stick: 1 }) && gave('Ann', 'beta') === 1, JSON.stringify(inv(b)))
  check('a failing command is logged and the next one still runs', s.since(m0).some(l => /nosuchcommand Ann/.test(l)) && has(b, 'stick') === 1,
    s.since(m0).filter(l => /Homesteader/.test(l)).join('\n'))
  check('commands ran with the right name, once', s.since(m0).filter(l => /Starter command for Ann .*give Ann stick/.test(l)).length === 1)
  await go('Ann', 'alpha', 3000)
  check('beta items never appear in alpha', has(b, 'apple') === 0 && has(b, 'stick') === 0, JSON.stringify(inv(b)))
  await go('Ann', 'beta', 3000)
  check('back in beta: nothing more', gave('Ann', 'beta') === 1 && eq(kitBeta(b), { chest: 1, apple: 3, stick: 1 }), JSON.stringify(inv(b)))
}

// ---------- commands only ----------
if (want('commands')) {
  section('A starter that is only a command')
  const c = await join('Wanda')
  await go('Wanda', 'omega', 4000)
  check('the command runs once, after arrival, with the right name', has(c, 'stick') === 1 && gave('Wanda', 'omega') === 1, JSON.stringify(inv(c)))
  check('the group\'s own welcome is shown instead of the server-wide one', c.chatLog.some(m => /Your stick has arrived/.test(m)) && !c.chatLog.some(m => /starter chest/.test(m)), c.chatLog.join(' | '))
  await go('Wanda', 'world', 2000); await go('Wanda', 'omega', 3000)
  check('not again on return', has(c, 'stick') === 1 && gave('Wanda', 'omega') === 1, JSON.stringify(inv(c)))
  s.cmd('give Wanda stone 2240'); await sleep(1000)
  await s.ask('homesteader reset Wanda omega', 4000)
  check('with no free slot, the command waits instead of dropping its item', has(c, 'stick') === 1 && c.chatLog.some(m => /Free up 1 inventory slot/.test(m)), c.chatLog.slice(-2).join(' | '))
  s.cmd('clear Wanda stone 64'); await sleep(7000)
  check('and runs once there is room', has(c, 'stick') === 2 && gave('Wanda', 'omega') === 2, JSON.stringify(inv(c)))
  const n0 = c.chatLog.length
  c.chat('/homesteader status Wanda'); await sleep(1500)
  check('a player without homesteader.admin can\'t use /homesteader', !c.chatLog.slice(n0).some(m => /Wanda in omega/.test(m)), c.chatLog.slice(n0).join(' | '))
  s.cmd('op Wanda'); await sleep(1000)
  const n1 = c.chatLog.length
  c.chat('/homesteader status Wanda'); await sleep(1500)
  check('an op can, through the Brigadier command', c.chatLog.slice(n1).some(m => /Wanda in omega: starter/.test(m)), c.chatLog.slice(n1).join(' | '))
  s.cmd('deop Wanda')
  await leave('Wanda')
}

// ---------- mark as done ----------
if (want('mark')) {
  section('Mark as done')
  await join('Mark'); await leave('Mark')
  const out = await s.ask('homesteader done Mark beta', 800)
  check('admin marks a player done', out.some(l => /Marked Mark as done in 'beta'/.test(l)), out.join('\n'))
  const m = await join('Mark'); await go('Mark', 'beta', 4000)
  check('marked player gets nothing on their first visit, commands included', eq(inv(m), {}) && gave('Mark', 'beta') === 0, JSON.stringify(inv(m)))
  await leave('Mark')
}

// ---------- existing inventories ----------
if (want('existing')) {
  section('Players who already had an inventory in a group')
  const o = await join('Oldtimer')
  await go('Oldtimer', 'delta', 3000)
  s.cmd('give Oldtimer cookie 1'); await sleep(800)
  await go('Oldtimer', 'world', 3000)
  // The other two signs of an existing player: only XP, or only something in the ender chest.
  const x = await join('Xpman'); await go('Xpman', 'delta', 3000)
  s.cmd('xp add Xpman 30 points'); await sleep(800)
  await go('Xpman', 'world', 3000)
  const e = await join('Enderman'); await go('Enderman', 'delta', 3000)
  s.cmd('item replace entity Enderman enderchest.0 with cookie 1'); await sleep(800)
  await go('Enderman', 'world', 3000)
  writeConfig(dir, config({ delta: true })); await s.ask('homesteader reload', 1500)
  const m0 = s.mark()
  await go('Oldtimer', 'delta', 4000)
  check('existing inventory is detected, no starter', s.since(m0).some(l => /Oldtimer arrived in 'delta' with items or XP already/.test(l)) && has(o, 'cookie') === 1 && has(o, 'chest') === 0,
    JSON.stringify(inv(o)) + '\n' + s.since(m0).filter(l => /Homesteader/.test(l)).join('\n'))
  await go('Xpman', 'delta', 4000); await go('Enderman', 'delta', 4000)
  check('only XP counts as existing too: no starter', gave('Xpman', 'delta', m0) === 0 && has(x, 'chest') === 0 && s.since(m0).some(l => /Xpman arrived in 'delta' with items or XP already/.test(l)))
  check('only an ender chest item counts as existing too: no starter', gave('Enderman', 'delta', m0) === 0 && has(e, 'chest') === 0 && s.since(m0).some(l => /Enderman arrived in 'delta' with items or XP already/.test(l)))
  await leave('Xpman'); await leave('Enderman')
  const n = await join('Newcomer'); await go('Newcomer', 'delta', 4000)
  check('a new player in the same group still gets it', eq({ chest: has(n, 'chest'), cookie: has(n, 'cookie') }, { chest: 1, cookie: 5 }), JSON.stringify(inv(n)))
  const out = await s.ask('homesteader reset Oldtimer delta', 4000)
  check('reset gives it on the next visit, past the existing-inventory check', has(o, 'chest') === 1 && has(o, 'cookie') === 6 && out.some(l => /Reset 1 player/.test(l)), JSON.stringify(inv(o)))
  await leave('Oldtimer'); await leave('Newcomer')
}

// ---------- swap timing ----------
if (want('swap')) {
  section('Swap timing')
  const h = await join('Hopper')
  // Vanilla teleports run in order, unlike Multiverse's, so the bot ends where the last one says.
  const spawns = { alpha: await spawnOf('alpha'), beta: await spawnOf('beta'), world: await spawnOf('world') }
  const tp = (who, w) => s.cmd(`execute in minecraft:${w === 'world' ? 'overworld' : w} run tp ${who} ${spawns[w][0]} ${spawns[w][1]} ${spawns[w][2]}`)
  tp('Hopper', 'alpha'); tp('Hopper', 'beta'); await sleep(6000)
  check('teleported into alpha and straight on to beta: only beta\'s starter, in beta', (await world('Hopper')) === 'beta'
    && eq(kitBeta(h), { chest: 1, apple: 3, stick: 1 }) && has(h, 'bread') === 0 && gave('Hopper', 'alpha') === 0, JSON.stringify(inv(h)))
  tp('Hopper', 'world'); await sleep(3000)
  check('lobby inventory untouched', eq(inv(h), {}), JSON.stringify(inv(h)))
  tp('Hopper', 'alpha'); await sleep(150); tp('Hopper', 'world'); await sleep(4000)
  check('in and out of alpha within the settle time: nothing given anywhere', (await world('Hopper')) === 'world' && eq(inv(h), {}) && gave('Hopper', 'alpha') === 0, JSON.stringify(inv(h)))
  const mS = s.mark()
  tp('Hopper', 'alpha'); await sleep(700); tp('Hopper', 'world'); await sleep(6000)
  // The search sometimes finishes inside 0.7 s. Either way, the lobby inventory stays empty and the record
  // matches what happened.
  const finished = s.count(/Moved Hopper /, mS) === 1
  check(`left alpha 0.7 s after arriving (${finished ? 'the search had already finished' : 'during the search'}): lobby untouched, record consistent`,
    (await world('Hopper')) === 'world' && eq(inv(h), {}) && (finished
      ? gave('Hopper', 'alpha') === 1 && /move: done/.test(rec('Hopper', 'alpha') || '')
      : gave('Hopper', 'alpha') === 0 && !/move: done/.test(rec('Hopper', 'alpha') || '')),
    JSON.stringify(inv(h)) + ' ' + rec('Hopper', 'alpha'))
  await go('Hopper', 'alpha', 8000)
  check('a real visit to alpha later: its starter once, in alpha, and no beta items there', eq(kitAlpha(h), { chest: 1, bread: 8, sapling: 2 }) && has(h, 'apple') === 0 && gave('Hopper', 'alpha') === 1, JSON.stringify(inv(h)))
  await go('Hopper', 'beta', 3000)
  check('back in beta: its starter is still there, nothing more', eq(kitBeta(h), { chest: 1, apple: 3, stick: 1 }) && gave('Hopper', 'beta') === 1, JSON.stringify(inv(h)))
  await leave('Hopper')

  await leave('Hopper')
}

// ---------- full inventory ----------
if (want('full')) {
  section('Full inventory')
  const f = await join('Packrat')
  await go('Packrat', 'beta', 4000)
  s.cmd('clear Packrat'); await sleep(500)
  s.cmd('give Packrat stone 2304'); await sleep(1500)
  check('inventory is full', has(f, 'stone') === 2304, JSON.stringify(inv(f)))
  const m0 = s.mark()
  await s.ask('homesteader reset Packrat beta', 3000)
  await sleep(6000)
  check('full inventory: nothing given yet, player told how many slots', has(f, 'chest') === 0 && f.chatLog.some(m => /Free up 4 inventory slot/.test(m)), f.chatLog.slice(-3).join(' | '))
  check('nothing was dropped', !s.since(m0).some(l => /dropped .* at Packrat/.test(l)))
  s.cmd('clear Packrat stone 256'); await sleep(7000)
  const counted = (await s.ask('clear Packrat stone 0', 800)).join(' ').match(/Found (\d+) matching/)
  const stone = counted ? +counted[1] : NaN
  check(`after making room: the starter arrives within a few seconds, and no stone is lost (server counts ${stone})`, eq(kitBeta(f), { chest: 1, apple: 3, stick: 1 }) && stone === 2048,
    'bot sees ' + JSON.stringify(inv(f)))
  await leave('Packrat')
}

// ---------- death on arrival ----------
if (want('death')) {
  section('Death on arrival')
  for (const name of ['Doomed1', 'Doomed2', 'Doomed3']) {
    await join(name)
    const m0 = s.mark()
    s.cmd(`mvtp ${name} alpha`)
    await s.waitLog(new RegExp(`Moved ${name} `), 20000, m0)
    s.cmd(`kill ${name}`)
    await sleep(6000)
    check(`${name}: killed on landing, starter given exactly once`, gave(name, 'alpha') === 1 && s.count(new RegExp(`Moved ${name} `)) === 1, s.since(m0).filter(l => /Homesteader|died|killed/.test(l)).join('\n'))
    await go(name, 'world', 2000); await go(name, 'alpha', 3000)
    check(`${name}: no repeat on return`, gave(name, 'alpha') === 1)
    await leave(name)
  }
}

// ---------- safety: made-up hazards ----------
const ISLANDS = [[-40, -40], [40, -40], [40, 40], [-40, 40], [0, 50]]
if (want('hazard')) {
  section('Safety on made-up hazards')
  const fill = async c => { const out = await s.ask('execute in minecraft:hazard run ' + c, 400); if (!out.some(l => /Successfully filled|filled/i.test(l))) console.log('    fill: ' + out.join(' ')) }
  await s.ask('execute in minecraft:hazard run forceload add -96 -96 96 96', 3000)
  await sleep(4000)
  // Quadrants: water (ocean), lava, tree canopy and magma, with five 7x7 grass islands.
  await fill('fill -90 -61 -90 -1 -61 -1 water')
  await fill('fill 0 -61 -90 90 -61 -1 lava')
  await fill('fill 0 -58 0 90 -57 90 oak_leaves[persistent=true]')
  await fill('fill -90 -61 0 -1 -61 90 magma_block')
  await fill('fill -1 -61 -90 0 -55 90 stone') // a cliff wall between halves
  for (const [x, z] of ISLANDS) {
    await fill(`fill ${x - 5} -58 ${z - 5} ${x + 5} -57 ${z + 5} air`)
    await fill(`fill ${x - 5} -61 ${z - 5} ${x + 5} -61 ${z + 5} grass_block`)
  }
  await fill('fill -5 -61 45 5 -50 55 stone') // the last island is a plateau, 11 blocks up
  await fill('fill -5 -50 45 5 -50 55 grass_block')
  const m0 = s.mark()
  s.cmd('homesteader findspot hazard 300')
  await s.waitLog(/findspot: \d+\/300 found/, 600000, m0)
  const spots = s.since(m0).map(l => l.match(/findspot (-?\d+) (-?\d+) (-?\d+) /)).filter(Boolean).map(m => m.slice(1).map(Number))
  const sumLine = s.since(m0).find(l => /findspot: \d+\/300/.test(l)) || ''
  console.log('    ' + sumLine.replace(/.*findspot: /, ''))
  // The islands are 11x11. On the water, lava and magma islands only the centre 3x3 is more than 4 blocks
  // from them. The canopy island is level with the grass under the leaves around it, so all of it is fine.
  // The plateau: only where the ground 2 blocks around is the plateau too, never an edge.
  const onIsland = ([x, y, z]) => ISLANDS.some(([ix, iz]) => {
    const r = ix === 40 && iz === 40 ? 5 : ix === 0 && iz === 50 ? 3 : 1
    const top = ix === 0 && iz === 50 ? -49 : -60
    return Math.abs(x - ix) <= r && Math.abs(z - iz) <= r && y === top
  })
  const bad = spots.filter(p => !onIsland(p))
  check(`300 searches over water, lava, canopy, magma and a cliff: ${spots.length} landings, all where the rules allow on an island`, spots.length > 0 && bad.length === 0, JSON.stringify(bad.slice(0, 10)))
  check('searches that found nothing failed cleanly', s.since(m0).filter(l => /findspot none/.test(l)).every(l => /no safe spot in 24 tries/.test(l)))
}

// ---------- safety: no spot at all ----------
if (want('sea')) {
  section('A world with no safe spot')
  await s.ask('execute in minecraft:sea run forceload add -96 -96 96 96', 3000); await sleep(3000)
  await s.ask('execute in minecraft:sea run fill -100 -61 -100 100 -61 0 water', 500)
  await s.ask('execute in minecraft:sea run fill -100 -61 1 100 -61 100 water', 500)
  const m0 = s.mark()
  const sailor = await join('Sailor')
  await go('Sailor', 'sea', 8000)
  const p = await pos('Sailor')
  check('no safe spot: player stays where they arrived and is told', sailor.chatLog.some(m => /couldn't find a safe spot/.test(m)) && s.since(m0).some(l => /No safe spot for Sailor in 'sea' \(no safe spot in 6 tries\)/.test(l)), sailor.chatLog.join(' | '))
  check('they still get the starter', has(sailor, 'chest') === 1)
  check('the move is not recorded as used', /move: none/.test(rec('Sailor', 'sea') || ''), rec('Sailor', 'sea'))
  await go('Sailor', 'world', 2000); await go('Sailor', 'sea', 8000)
  check('next visit tries the move again, without a second starter', s.count(/No safe spot for Sailor/, m0) === 2 && has(sailor, 'chest') === 1 && gave('Sailor', 'sea') === 1)
  await leave('Sailor')
}

// ---------- safety: natural terrain, checked by a bot's own view ----------
const UNSAFE_BELOW = /water|lava|leaves|magma|cactus|^(soul_)?campfire$|^(soul_)?fire$|powder_snow|cobweb|dripstone|bedrock|sweet_berry|kelp|seagrass|bubble/
const NOT_OPEN = /water|lava|^(soul_)?fire$|powder_snow|cobweb|sweet_berry|kelp|seagrass|bubble/
async function verifyWithBot(eye, w, spots) {
  const { Vec3 } = await import('vec3')
  const bad = []
  for (const [x, y, z] of spots) {
    const seen = () => eye.entity && Math.abs(eye.entity.position.x - (x + 0.5)) < 1 && Math.abs(eye.entity.position.z - (z + 0.5)) < 1
      && eye.blockAt(new Vec3(x, y - 1, z)) && eye.blockAt(new Vec3(x + 2, y - 3, z + 2))
    let ok = false
    for (let attempt = 0; attempt < 2 && !ok; attempt++) {
      s.cmd(`execute in minecraft:${w} run tp ${eye.username} ${x + 0.5} ${y} ${z + 0.5}`)
      ok = await until(seen, 10000, 50)
    }
    if (!ok) { bad.push([x, y, z, 'chunk not seen']); continue }
    const below = eye.blockAt(new Vec3(x, y - 1, z)), feet = eye.blockAt(new Vec3(x, y, z)), head = eye.blockAt(new Vec3(x, y + 1, z))
    const wet = b => b.getProperties?.().waterlogged === true
    if (below.boundingBox !== 'block' || UNSAFE_BELOW.test(below.name) || wet(below)) { bad.push([x, y, z, 'ground ' + below.name]); continue }
    if (feet.boundingBox !== 'empty' || head.boundingBox !== 'empty' || NOT_OPEN.test(feet.name) || NOT_OPEN.test(head.name)) { bad.push([x, y, z, 'body ' + feet.name + '/' + head.name]); continue }
    let near = null
    for (let dx = -2; dx <= 2 && !near; dx++) for (let dz = -2; dz <= 2 && !near; dz++) for (let dy = -3; dy <= 1 && !near; dy++) {
      const b = eye.blockAt(new Vec3(x + dx, y + dy, z + dz))
      if (b && (/water|lava|magma|^(soul_)?fire$|^(soul_)?campfire$|cactus|powder_snow/.test(b.name) || wet(b))) near = b.name + '@' + [dx, dy, dz]
    }
    if (near) bad.push([x, y, z, 'near ' + near])
  }
  return bad
}
if (want('terrain')) {
  section('Safety on generated terrain')
  const N = Number(process.env.HS_TEST_SPOTS ?? 300)
  const sp = await spawnOf('alpha')
  const m0 = s.mark()
  s.cmd(`homesteader findspot alpha ${N}`)
  await s.waitLog(new RegExp(`findspot: \\d+/${N} found`), 1200000, m0)
  const lines = s.since(m0)
  const spots = lines.map(l => l.match(/findspot (-?\d+) (-?\d+) (-?\d+) distance=(\d+)/)).filter(Boolean).map(m => m.slice(1).map(Number))
  const sumLine = (lines.find(l => /findspot: \d+\//.test(l)) || '').replace(/.*findspot: /, '')
  console.log('    ' + sumLine)
  check(`${N} searches on a normal world: most find a spot`, spots.length >= N * 0.8, sumLine)
  check('every landing is between 100 and 400 blocks from spawn', spots.every(p => p[3] >= 100 && p[3] <= 400 && Math.abs(Math.hypot(p[0] + 0.5 - sp[0], p[2] + 0.5 - sp[2]) - p[3]) < 2), JSON.stringify(spots.filter(p => p[3] < 100 || p[3] > 400).slice(0, 5)))
  const eye = await join('Eye'); s.cmd('gamemode spectator Eye'); await sleep(500)
  s.cmd('mvtp Eye alpha'); await sleep(3000)
  const bad = await verifyWithBot(eye, 'alpha', spots.map(p => p.slice(0, 3)))
  check(`a bot looked at all ${spots.length} landings itself: solid ground, open body space, no water, lava or hazard within 2 blocks`, bad.length === 0, JSON.stringify(bad.slice(0, 10)))
  const lag = s.since(m0).filter(l => /Can't keep up/.test(l))
  check('no "Can\'t keep up" during the searches', lag.length === 0, lag.join('\n'))
  await leave('Eye')
}

// ---------- border and claims ----------
if (want('claims')) {
  section('Border and claims')
  await s.ask('execute in minecraft:tiny run worldborder center 0 0', 500)
  await s.ask('execute in minecraft:tiny run worldborder set 160', 500)
  const c = await join('Claimer')
  s.cmd('op Claimer'); s.cmd('gamemode creative Claimer'); await go('Claimer', 'tiny', 6000)
  await s.ask('acb Claimer 200000', 500)
  await s.ask('item replace entity Claimer weapon.mainhand with golden_shovel', 500)
  const centres = [[-40, -40], [40, 40], [40, -40], [-40, 40], [0, 0]]
  for (const [x, z] of centres) {
    s.cmd(`execute in minecraft:tiny run tp Claimer ${x} -60 ${z}`); await sleep(1500)
    c.chat('/claim 14'); await sleep(1500)
  }
  const claimDir = path.join(dir, 'plugins/GriefPreventionData/ClaimData')
  await s.ask('save-all', 2000)
  const claims = existsSync(claimDir) ? readdirSync(claimDir).filter(f => f.endsWith('.yml')).map(f => {
    const t = readFileSync(path.join(claimDir, f), 'utf8')
    const lo = t.match(/Lesser Boundary Corner: (\S+);(-?\d+);(-?\d+);(-?\d+)/), hi = t.match(/Greater Boundary Corner: (\S+);(-?\d+);(-?\d+);(-?\d+)/)
    return lo && hi && lo[1] === 'tiny' ? { x1: +lo[2], z1: +lo[4], x2: +hi[2], z2: +hi[4] } : null
  }).filter(Boolean) : []
  check(`claims made in the tiny world (${claims.length})`, claims.length >= 4, c.chatLog.slice(-6).join(' | '))
  const m0 = s.mark()
  s.cmd('homesteader findspot tiny 200')
  await s.waitLog(/findspot: \d+\/200 found/, 600000, m0)
  const spots = s.since(m0).map(l => l.match(/findspot (-?\d+) (-?\d+) (-?\d+) /)).filter(Boolean).map(m => m.slice(1).map(Number))
  console.log('    ' + (s.since(m0).find(l => /findspot: \d+\//.test(l)) || '').replace(/.*findspot: /, ''))
  const outside = spots.filter(([x, , z]) => Math.abs(x + 0.5) > 72 || Math.abs(z + 0.5) > 72)
  const nearClaim = spots.filter(([x, , z]) => claims.some(k => x >= k.x1 - 12 && x <= k.x2 + 12 && z >= k.z1 - 12 && z <= k.z2 + 12))
  check(`${spots.length} landings, all inside the 160-block border with its 8-block margin`, spots.length > 0 && outside.length === 0, JSON.stringify(outside.slice(0, 5)))
  check('none inside or within 12 blocks of a claim', nearClaim.length === 0, JSON.stringify(nearClaim.slice(0, 5)))
  await leave('Claimer')
}

// ---------- load ----------
if (want('load')) {
  section('Load: several players arriving at once')
  const names = ['Crowd1', 'Crowd2', 'Crowd3', 'Crowd4', 'Crowd5', 'Crowd6', 'Crowd7', 'Crowd8']
  const crowd = []
  for (const n of names) crowd.push(await join(n))
  const m0 = s.mark()
  const t0 = Date.now()
  for (const n of names) s.cmd(`mvtp ${n} alpha`)
  await until(() => names.every(n => gave(n, 'alpha', m0) === 1), 120000, 250)
  const secs = ((Date.now() - t0) / 1000).toFixed(1)
  await sleep(2000) // let the item packets reach the bots
  const tries = s.since(m0).map(l => l.match(/after (\d+) tries/)).filter(Boolean).map(m => +m[1])
  check(`all ${names.length} got exactly one starter and one move (${secs}s)`, names.every((n, i) => gave(n, 'alpha', m0) === 1 && s.count(new RegExp(`Moved ${n} `), m0) === 1 && eq(kitAlpha(crowd[i]), { chest: 1, bread: 8, sapling: 2 })),
    names.map((n, i) => n + ' ' + JSON.stringify(inv(crowd[i]))).join(', '))
  check(`chunks loaded for the moves: ${tries.reduce((a, b) => a + b, 0)} in all, at most ${Math.max(...tries)} for one player`, tries.length === names.length && Math.max(...tries) <= 24)
  const lag = s.since(m0).filter(l => /Can't keep up/.test(l))
  const mspt = await s.ask('mspt', 800)
  console.log('    ' + mspt.filter(l => /\d/.test(l)).slice(0, 3).join(' / '))
  check('no "Can\'t keep up" while they arrived', lag.length === 0, lag.join('\n'))
  for (const n of names) await leave(n)
}

// ---------- restart ----------
if (want('restart')) {
  section('After a restart')
  for (const b of bots.values()) await quit(b); bots.clear(); await sleep(1000)
  const before = records(dir)
  await restart()
  check('records survived the restart', records(dir) === before && before.length > 0)
  const a = await join('Ann'); await go('Ann', 'alpha', 3000); await go('Ann', 'beta', 3000)
  check('Ann gets nothing more in alpha or beta', gave('Ann', '.*') === 0, JSON.stringify(inv(a)))
  if (want('mark')) {
    const m = await join('Mark'); await go('Mark', 'beta', 3000)
    check('the done mark survived the restart', gave('Mark', 'beta') === 0 && eq(inv(m), {}), JSON.stringify(inv(m)))
  }
  await leave('Ann'); if (bots.has('Mark')) await leave('Mark')
}

// ---------- interruptions ----------
if (want('crash')) {
  section('Interruptions: disconnects and kill -9')
  const P = n => [`-Dhomesteader.test.${n}=200`]

  // Disconnect after the items, before the commands.
  await restart(P('pause-after-give'))
  await join('Quitter'); s.cmd('mvtp Quitter beta'); await sleep(2500)
  await leave('Quitter'); await sleep(11000)
  // The pause is still on, so the rest waits 10 s again after the return.
  let q = await join('Quitter'); if ((await world('Quitter')) !== 'beta') await go('Quitter', 'beta', 3000); await sleep(12000)
  check('disconnect between items and commands: items kept, commands run on return, one starter', eq(kitBeta(q), { chest: 1, apple: 3, stick: 1 }), JSON.stringify(inv(q)) + ' ' + rec('Quitter', 'beta'))
  await leave('Quitter')
  check('settled as done once they logged out', /starter: done/.test(rec('Quitter', 'beta') || ''), rec('Quitter', 'beta'))
  await restart()

  // kill -9 after the items and commands, before the inventory was saved.
  await join('Crash1'); s.cmd('mvtp Crash1 beta'); await sleep(3000)
  await s.kill9(); bots.clear()
  check('record is pending at the crash, with the command counted', /starter: pending/.test(rec('Crash1', 'beta') || '') && /commands-run: 2/.test(rec('Crash1', 'beta') || ''), rec('Crash1', 'beta'))
  await start()
  q = await join('Crash1'); if ((await world('Crash1')) !== 'beta') await go('Crash1', 'beta', 4000); else await sleep(3000)
  const again = s.log.some(l => /Giving Crash1 the items of their 'beta' starter again/.test(l))
  check(`kill -9 before the save: one set of items in the end${again ? ' (they were lost and given again)' : ''}`, has(q, 'chest') === 1 && has(q, 'apple') === 3,
    JSON.stringify(inv(q)) + ' ' + s.log.filter(l => /Homesteader.*Crash1/.test(l)).join(' / '))
  check('the command already counted is not run again', s.count(/Starter command for Crash1 .*give Crash1 stick/) === 0 && has(q, 'stick') <= 1, JSON.stringify(inv(q)))
  await leave('Crash1')
  check('settled as done once they logged out', /starter: done/.test(rec('Crash1', 'beta') || ''), rec('Crash1', 'beta'))

  // kill -9 after the starter was settled.
  await join('Crash2'); s.cmd('mvtp Crash2 beta'); await sleep(3000)
  await go('Crash2', 'world', 3000)
  check('leaving the group settles it as done', /starter: done/.test(rec('Crash2', 'beta') || ''), rec('Crash2', 'beta'))
  await s.kill9(); bots.clear()
  await start()
  q = await join('Crash2'); await go('Crash2', 'beta', 4000)
  check('kill -9 after it was settled: nothing more, and the starter is still there', eq(kitBeta(q), { chest: 1, apple: 3, stick: 1 }) && gave('Crash2', 'beta') === 0 && !s.log.some(l => /Crash2 the items .* again/.test(l)),
    JSON.stringify(inv(q)))
  await leave('Crash2')

  // kill -9 between the teleport and the items.
  await restart(P('pause-after-move'))
  await join('Crash3'); const m0 = s.mark(); s.cmd('mvtp Crash3 alpha')
  await s.waitLog(/Moved Crash3 /, 30000, m0); await sleep(500)
  await s.kill9(); bots.clear()
  await start()
  q = await join('Crash3'); if ((await world('Crash3')) !== 'alpha') await go('Crash3', 'alpha', 8000); else await sleep(8000)
  check('kill -9 between teleport and items: the starter still arrives, once', eq(kitAlpha(q), { chest: 1, bread: 8, sapling: 2 }) && gave('Crash3', 'alpha') === 1,
    JSON.stringify(inv(q)) + ' ' + s.log.filter(l => /Homesteader.*Crash3/.test(l)).join(' / '))
  await leave('Crash3')

  // Disconnect between the teleport and the items.
  await restart(P('pause-after-move'))
  await join('Quitter2'); const m1 = s.mark(); s.cmd('mvtp Quitter2 alpha')
  await s.waitLog(/Moved Quitter2 /, 30000, m1); await leave('Quitter2'); await sleep(11000)
  q = await join('Quitter2'); if ((await world('Quitter2')) !== 'alpha') await go('Quitter2', 'alpha', 4000); await sleep(12000)
  check('disconnect between teleport and items: starter on return, once, and no second move', eq(kitAlpha(q), { chest: 1, bread: 8, sapling: 2 }) && gave('Quitter2', 'alpha') === 1 && s.count(/Moved Quitter2 /, m1) === 1,
    JSON.stringify(inv(q)) + ' ' + s.log.filter(l => /Homesteader.*Quitter2/.test(l)).join(' / '))
  await leave('Quitter2')
  noteExceptions()
  await restart()
}

// ---------- corrupt record ----------
if (want('corrupt')) {
  section('Damaged record file')
  const good = records(dir)
  await s.stop()
  const f = path.join(dir, 'plugins/Homesteader/records.yml')
  writeFileSync(f, good.slice(0, Math.floor(good.length / 2)) + '\n  ][ not yaml {{\n')
  const damaged = readFileSync(f, 'utf8')
  noteExceptions(); await start()
  check('reported at startup', s.log.some(l => /SEVERE|ERROR/.test(l) && /records\.yml can't be read/.test(l)), s.log.filter(l => /Homesteader/.test(l)).join('\n'))
  check('the damaged file is kept, with a copy beside it', readFileSync(f, 'utf8') === damaged && readdirSync(path.dirname(f)).some(n => n.startsWith('records.yml.corrupt-')))
  const fresh = await join('Freshface'); await go('Freshface', 'alpha', 6000)
  const ann = await join('Ann'); await go('Ann', 'alpha', 3000)
  check('nobody gets anything, new or old', eq(inv(fresh), {}) && gave('.*', '.*') === 0 && s.count(/Moved /) === 0, JSON.stringify(inv(fresh)) + JSON.stringify(inv(ann)))
  const out = await s.ask('homesteader reset Freshface alpha', 800)
  check('admin commands refuse to write over it', out.some(l => /can't be read/.test(l)), out.join('\n'))
  writeFileSync(f, good)
  await s.ask('homesteader reload', 1500)
  await go('Freshface', 'world', 2000); await go('Freshface', 'alpha', 8000)
  check('after the file is fixed and reloaded, it works again', eq(kitAlpha(fresh), { chest: 1, bread: 8, sapling: 2 }) && gave('Ann', '.*') === 0, JSON.stringify(inv(fresh)))
  await leave('Freshface'); await leave('Ann')
}

// ---------- wrap up ----------
section('Code checks')
const src = execSync(`grep -rnE "setType\\(|setBlockData\\(|\\.setBlock|breakNaturally|setBiome|\\.update\\(true|fill\\(" ${path.join(new URL('../', import.meta.url).pathname, 'src/main')} || true`).toString()
check('no call that changes a block anywhere in the plugin', src.trim() === '', src)
noteExceptions()
check('no Homesteader exceptions in any server log', SAVED_EXC.length === 0, SAVED_EXC.slice(0, 10).join('\n'))
for (const b of bots.values()) await quit(b)
await s.stop()
const failed = summary()
// One throwaway server at a time, and no copies left between runs. HS_TEST_KEEP=1 keeps it for debugging.
if (process.env.HS_TEST_KEEP !== '1') rmSync(dir, { recursive: true, force: true, maxRetries: 5, retryDelay: 500 })
process.exit(failed)
