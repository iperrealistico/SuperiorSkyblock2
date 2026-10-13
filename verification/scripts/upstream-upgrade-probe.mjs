import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { readFileSync, realpathSync, lstatSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

// Test-only client: original 2026.3 -> populated LEGACY migration -> retained restart.
// No production connections, installation, configuration edits or dependency downloads.
const ORIGINAL_SHA = '552a9ad27b2ccf0b6a8e124805220333c125e361a992a8e82deb62088ba831c1'
const port = Number(process.env.SUPERIOR_TEST_PORT)
const phase = process.env.SUPERIOR_PHASE
const version = process.env.SUPERIOR_TEST_VERSION
if (!Number.isInteger(port) || port < 29010 || port > 29029 || version !== '26.2'
    || !['upstream-seed', 'fork-upgrade', 'fork-upgrade-restart'].includes(phase)
    || process.env.SUPERIOR_PROFILE !== 'upgrade' || !process.env.SUPERIOR_TEST_ROOT
    || !process.env.SUPERIOR_MINEFLAYER) throw new Error('Explicit owned Leaf 26.2 upstream upgrade fixture required')
const root = path.resolve(process.env.SUPERIOR_TEST_ROOT)
const samePath = (one, two) => process.platform === 'win32' ? one.toLowerCase() === two.toLowerCase() : one === two
if (path.basename(path.dirname(root)) !== 'local-servers' || !path.basename(root).startsWith('superior-runtime-')
    || lstatSync(root).isSymbolicLink() || !samePath(root, realpathSync(root))
    || !lstatSync(path.join(root, '.superior-owned')).isFile()
    || !lstatSync(path.join(root, '.superior-upgrade-owned')).isFile()) throw new Error('Owned upgrade fixture path required')
const properties = readFileSync(path.join(root, 'server.properties'), 'utf8')
const property = key => properties.split(/\r?\n/).find(line => line.startsWith(key + '='))?.slice(key.length + 1)
if (property('server-ip') !== '127.0.0.1' || Number(property('server-port')) !== port
    || property('online-mode') !== 'false') throw new Error('Private native listener guard failed')
const inputs = JSON.parse(readFileSync(path.join(root, 'runtime-input.json'), 'utf8'))
if (inputs.lane?.id !== 'leaf-262' || inputs.lane?.platform !== 'Leaf' || inputs.lane?.minecraft !== '26.2'
    || inputs.port !== port || inputs.productionTouched !== false) throw new Error('Exact Leaf upgrade input guard failed')
const workspace = path.dirname(path.dirname(root))
const clientRoot = realpathSync(path.join(workspace, '.ai-control', 'workspace-local', 'pvc-wither-client'))
const clientModule = realpathSync(process.env.SUPERIOR_MINEFLAYER)
const moduleRelative = path.relative(clientRoot, clientModule)
if (!moduleRelative || moduleRelative.startsWith('..') || path.isAbsolute(moduleRelative)
    || path.basename(clientModule) !== 'index.js') throw new Error('Maintained workspace mineflayer module required')
const digest = bytes => createHash('sha256').update(bytes).digest('hex')
if (phase === 'upstream-seed') {
  assert.equal(digest(readFileSync(path.join(root, 'plugins', 'SuperiorSkyblock2.jar'))), ORIGINAL_SHA)
  assert(lstatSync(path.join(root, 'plugins', 'SuperiorSkyblockUpstreamSeed.jar')).isFile())
} else {
  assert.equal(digest(readFileSync(path.join(root, 'plugins', 'SuperiorSkyblock2.jar'))), inputs.pluginSHA256.toLowerCase())
  assert.equal(digest(readFileSync(path.join(root, 'plugins', 'SuperiorSkyblockPVCProbe.jar'))), inputs.probeSHA256.toLowerCase())
}
function checkpoint() {
  const bytes = readFileSync(path.join(root, 'upstream-island-checkpoint.properties'))
  const recorded = readFileSync(path.join(root, 'upstream-island-checkpoint.properties.sha256'), 'utf8').trim()
  assert.equal(digest(bytes), recorded.toLowerCase(), 'Immutable original island checkpoint digest differs')
  return recorded.toLowerCase()
}
const checkpointBefore = phase === 'upstream-seed' ? null : checkpoint()
const { default: mineflayer } = await import(pathToFileURL(clientModule).href)
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'PVCSkyProbe', auth: 'offline',
  version, checkTimeoutInterval: 30000 })
const messages = [], waiters = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let next = 0, finished = false, started = false, creationExpected = false, creationClicks = 0
const timeout = setTimeout(() => finish(new Error('Native populated upstream upgrade timeout')), 400000)

bot.on('message', message => {
  const text = (message.unsigned ?? message).toString()
  messages.push(text)
  if (text.includes('SUPERIOR_')) console.log(text)
  if (/SUPERIOR_(UPSTREAM_SEED_FAIL|UPGRADE_FAIL|LEGACY_PROBE_FAIL|PROBE_FAIL)/.test(text)) finish(new Error(text))
  for (const waiter of [...waiters]) if (waiter.test(text)) {
    clearTimeout(waiter.timer); waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(text)
  }
})
async function send(text) {
  await pause(Math.max(0, next - Date.now())); next = Date.now() + 1200; bot.chat(text)
}
function waitFor(test, from = messages.length, delay = 30000) {
  const existing = messages.slice(from).find(test)
  if (existing) return Promise.resolve(existing)
  return new Promise((resolve, reject) => {
    const waiter = { test, resolve, timer: setTimeout(() => {
      const index = waiters.indexOf(waiter); if (index >= 0) waiters.splice(index, 1)
      reject(new Error('Missing populated upstream upgrade response'))
    }, delay) }; waiters.push(waiter)
  })
}
async function command(text, marker, delay = 30000) {
  const from = messages.length; await send(text); return waitFor(text => text.includes(marker), from, delay)
}
async function legacy(action, marker, delay = 30000) { return command('/pvcskyprobe legacy-' + action, marker, delay) }
async function upstreamStatus() {
  const text = await command('/pvcupstreamseed status', 'SUPERIOR_UPSTREAM_SEED_STATUS')
  const entries = Object.fromEntries([...text.matchAll(/(count|radius|pending|postCreated)=([^ ]+)/g)]
    .map(match => [match[1], match[2]]))
  return { count: Number(entries.count), radius: Number(entries.radius), pending: entries.pending === 'true',
    postCreated: entries.postCreated === 'true' }
}
async function waitUpstreamCreation() {
  for (let attempt = 0; attempt < 90; ++attempt) {
    const state = await upstreamStatus()
    if (state.count === 1 && state.radius === 100 && !state.pending && state.postCreated) return
    assert(state.count <= 1, 'Original plugin unexpectedly created several islands')
    await pause(250)
  }
  throw new Error('Original upstream island did not reach settled post-create state')
}
function compareBackup() {
  const source = readFileSync(path.join(root, 'staged-before-config.yml'))
  const backup = readFileSync(path.join(root, 'plugins', 'SuperiorSkyblock2', 'config.yml.pvc-backup'))
  assert(source.equals(backup), 'Fork migration backup differs from actual stopped upstream config bytes')
}
async function home(feedback) {
  await legacy('away', 'SUPERIOR_LEGACY_AWAY_READY')
  const from = messages.length; await send('/is home')
  await waitFor(text => text.includes(feedback), from, 30000)
  await legacy('homecheck', 'SUPERIOR_LEGACY_HOME_PASS')
}
async function run() {
  if (phase === 'upstream-seed') {
    assert.equal((await upstreamStatus()).count, 0, 'Original fixture must begin without an owned island')
    await command('/pvcupstreamseed ready', 'SUPERIOR_UPSTREAM_SEED_READY')
    creationExpected = true
    await send('/is create')
    await waitUpstreamCreation()
    creationExpected = false
    assert(creationClicks <= 1, 'Original creation opened more than its single Classic menu')
    await command('/pvcupstreamseed record', 'SUPERIOR_UPSTREAM_SEED_PASS', 110000)
    checkpoint()
  } else {
    const ready = await legacy('ready', 'SUPERIOR_LEGACY_READY')
    assert.match(ready, /mode=LEGACY quota=1/)
    assert(Number(ready.match(/preserved=(\d+)/)?.[1]) > 0)
    const feedback = Buffer.from(ready.match(/home=([^ ]+)/)?.[1] ?? '', 'base64').toString('utf8')
    assert(feedback.length > 0, 'Configured home feedback must be available')
    compareBackup()
    await home(feedback)
    await send('/is list'); await pause(1000)
    if (phase === 'fork-upgrade') await legacy('upgrade-check', 'SUPERIOR_UPGRADE_MIGRATION_PASS', 110000)
    else await legacy('upgrade-restart-check', 'SUPERIOR_UPGRADE_RESTART_PASS', 110000)
    assert.equal(checkpoint(), checkpointBefore, 'Fork rewrote the immutable original island checkpoint')
    compareBackup()
  }
  console.log('SUPERIOR_UPGRADE_CLIENT_PASS ' + phase + (phase === 'upstream-seed'
    ? ' source=unmodified populated=actual checkpoint=immutable'
    : ' populated=actual legacy=preserved home=actual checkpoint=immutable'))
  finish()
}
function finish(error) {
  if (finished) return; finished = true; clearTimeout(timeout)
  for (const waiter of waiters) clearTimeout(waiter.timer)
  if (error) { console.error(error.stack ?? error); process.exitCode = 1 }
  bot.quit(); setTimeout(() => process.exit(process.exitCode ?? 0), 500)
}
bot.on('spawn', () => { if (started) return; started = true; run().catch(finish) })
bot.on('windowOpen', window => {
  if (!creationExpected) return
  creationExpected = false
  if (!JSON.stringify(window.title).includes('Create a new island') || window.slots[4]?.name !== 'grass_block') {
    finish(new Error('Exact Classic creation menu was not opened')); return
  }
  creationClicks++
  pause(250).then(() => bot.clickWindow(4, 0, 0)).catch(finish)
})
bot.on('error', finish)
bot.on('kicked', reason => finish(new Error('Native upstream upgrade client kicked: ' + JSON.stringify(reason))))
