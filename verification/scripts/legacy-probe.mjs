import assert from 'node:assert/strict'
import { readFileSync, realpathSync, lstatSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

// The maintained client connects only to a root-owned loopback fixture. It never
// installs dependencies, opens a production connection or edits configuration.
const port = Number(process.env.SUPERIOR_TEST_PORT)
const phase = process.env.SUPERIOR_PHASE
const version = process.env.SUPERIOR_TEST_VERSION
if (!Number.isInteger(port) || port < 29010 || port > 29029
    || !['initial', 'restart'].includes(phase) || !['1.21.11', '26.2'].includes(version)
    || process.env.SUPERIOR_PROFILE !== 'legacy' || !process.env.SUPERIOR_TEST_ROOT
    || !process.env.SUPERIOR_MINEFLAYER) throw new Error('Explicit owned LEGACY fixture environment required')
const root = path.resolve(process.env.SUPERIOR_TEST_ROOT)
const samePath = (one, two) => process.platform === 'win32' ? one.toLowerCase() === two.toLowerCase() : one === two
if (path.basename(path.dirname(root)) !== 'local-servers' || !path.basename(root).startsWith('superior-runtime-')
    || lstatSync(root).isSymbolicLink() || !samePath(root, realpathSync(root))
    || !lstatSync(path.join(root, '.superior-owned')).isFile()) throw new Error('Owned fixture path required')
const properties = readFileSync(path.join(root, 'server.properties'), 'utf8')
const property = key => properties.split(/\r?\n/).find(line => line.startsWith(key + '='))?.slice(key.length + 1)
if (property('server-ip') !== '127.0.0.1' || Number(property('server-port')) !== port
    || property('online-mode') !== 'false') throw new Error('Private native listener guard failed')
const workspace = path.dirname(path.dirname(root))
const clientRoot = realpathSync(path.join(workspace, '.ai-control', 'workspace-local', 'pvc-wither-client'))
const clientModule = realpathSync(process.env.SUPERIOR_MINEFLAYER)
const moduleRelative = path.relative(clientRoot, clientModule)
if (!moduleRelative || moduleRelative.startsWith('..') || path.isAbsolute(moduleRelative)
    || path.basename(clientModule) !== 'index.js') throw new Error('Maintained workspace mineflayer module required')
const sourceConfig = readFileSync(path.join(root, 'staged-before-config.yml'))
const { default: mineflayer } = await import(pathToFileURL(clientModule).href)
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'PVCSkyProbe', auth: 'offline',
  version, checkTimeoutInterval: 30000 })
const messages = [], waiters = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let next = 0, finished = false, started = false, creationExpected = false, creationClicks = 0
const timeout = setTimeout(() => finish(new Error('Native LEGACY fixture timeout')), 300000)

bot.on('message', message => {
  const text = (message.unsigned ?? message).toString()
  messages.push(text)
  if (text.includes('SUPERIOR_')) console.log(text)
  if (text.includes('SUPERIOR_LEGACY_PROBE_FAIL') || text.includes('SUPERIOR_PROBE_FAIL')) finish(new Error(text))
  for (const waiter of [...waiters]) if (waiter.test(text)) {
    clearTimeout(waiter.timer); waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(text)
  }
})
async function send(text) {
  await pause(Math.max(0, next - Date.now())); next = Date.now() + 1200; bot.chat(text)
}
function waitFor(test, from = messages.length, delay = 20000) {
  const existing = messages.slice(from).find(test)
  if (existing) return Promise.resolve(existing)
  return new Promise((resolve, reject) => {
    const waiter = { test, resolve, timer: setTimeout(() => {
      const index = waiters.indexOf(waiter); if (index >= 0) waiters.splice(index, 1)
      reject(new Error('Missing native LEGACY response'))
    }, delay) }; waiters.push(waiter)
  })
}
async function command(text, marker, delay = 20000) {
  const from = messages.length; await send(text); return waitFor(text => text.includes(marker), from, delay)
}
async function legacy(action, marker, delay = 30000) { return command('/pvcskyprobe legacy-' + action, marker, delay) }
async function status() {
  const text = await legacy('status', 'SUPERIOR_LEGACY_STATUS')
  const entries = Object.fromEntries([...text.matchAll(/(mode|count|quota|ids|uuids|radii|selected|pending)=([^ ]+)/g)]
    .map(match => [match[1], match[2]]))
  assert.equal(entries.mode, 'LEGACY')
  assert.equal(Number(entries.quota), 1)
  return { count: Number(entries.count), selected: Number(entries.selected), pending: entries.pending === 'true',
    ids: entries.ids === 'none' ? [] : entries.ids.split(',').map(Number),
    uuids: entries.uuids === 'none' ? [] : entries.uuids.split(','),
    radii: entries.radii === 'none' ? [] : entries.radii.split(',').map(Number) }
}
async function sole() {
  for (let attempt = 0; attempt < 40; ++attempt) {
    const state = await status()
    if (state.count === 1 && !state.pending && state.selected === state.ids[0]) {
      assert.equal(state.radii[0], 100); assert(state.ids[0] > 0)
      assert.match(state.uuids[0], /^[0-9a-f-]{36}$/i)
      return state
    }
    await pause(250)
  }
  throw new Error('Sole LEGACY island did not reach terminal selected state')
}
function compareBackup() {
  const backup = readFileSync(path.join(root, 'plugins', 'SuperiorSkyblock2', 'config.yml.pvc-backup'))
  assert(sourceConfig.equals(backup), 'Migration backup differs from exact staged configuration bytes')
}
async function home(expectedFeedback) {
  await legacy('away', 'SUPERIOR_LEGACY_AWAY_READY')
  const from = messages.length
  await send('/is home')
  // Native home succeeds asynchronously; each check executes on the actual
  // player owner thread, without an ID or selector argument.
  await waitFor(text => text.includes(expectedFeedback), from, 30000)
  await legacy('homecheck', 'SUPERIOR_LEGACY_HOME_PASS')
}
async function create() {
  assert.equal(creationExpected, false)
  creationExpected = true
  await send('/is create')
}

async function run() {
  const ready = await legacy('ready', 'SUPERIOR_LEGACY_READY')
  assert.match(ready, /mode=LEGACY quota=1/)
  assert(Number(ready.match(/preserved=(\d+)/)?.[1]) > 0)
  const denial = Buffer.from(ready.match(/denial=([^ ]+)/)[1], 'base64').toString('utf8')
  const homeFeedback = Buffer.from(ready.match(/home=([^ ]+)/)[1], 'base64').toString('utf8')
  const configuredMenu = ready.match(/skipSingleMenu=(true|false)/)?.[1]
  assert(configuredMenu, 'Observer did not publish the existing menu policy')
  const skipSingleMenu = configuredMenu === 'true'
  assert(denial.length > 0, 'Native creation failure message is missing')
  assert(homeFeedback.length > 0, 'Native home message is missing')
  compareBackup()
  if (phase === 'initial') {
    assert.equal((await status()).count, 0, 'Initial LEGACY fixture is not empty')
    await create()
    const first = await sole()
    creationExpected = false
    assert.equal(creationClicks, skipSingleMenu ? 0 : 1, 'Creation did not honor the existing single-item menu policy')
    await home(homeFeedback)
    const deniedFrom = messages.length
    await create()
    // Observe the real terminal plugin response, rather than passing after a
    // short delay while a second creation might still be in flight.
    await waitFor(text => text.includes(denial), deniedFrom, 30000)
    creationExpected = false
    assert.equal(creationClicks, skipSingleMenu ? 0 : 2, 'Quota denial did not honor the existing single-item menu policy')
    const after = await sole()
    assert.deepEqual(after, first, 'Second creation altered the sole island')
    await legacy('denycheck', 'SUPERIOR_LEGACY_QUOTA_DENIAL_PASS')
    await send('/is list'); await pause(1000)
    await legacy('finish', 'SUPERIOR_LEGACY_PROBE_PASS')
  } else {
    await sole()
    await home(homeFeedback)
    await send('/is list'); await pause(1000)
    await legacy('restartfinish', 'SUPERIOR_LEGACY_RESTART_PASS')
  }
  compareBackup()
  console.log('SUPERIOR_LEGACY_CLIENT_PASS ' + phase + ' mode=LEGACY quota=1 radius=100 migration=preserved backup=exact')
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
  const title = JSON.stringify(window.title)
  if (!title.includes('Create a new island') || window.slots[4]?.name !== 'grass_block') {
    finish(new Error('Exact current Cielo Classic menu was not opened')); return
  }
  creationClicks++
  pause(250).then(() => bot.clickWindow(4, 0, 0)).catch(finish)
})
bot.on('error', finish)
bot.on('kicked', reason => finish(new Error('Kicked: ' + JSON.stringify(reason))))
