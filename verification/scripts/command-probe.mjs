import assert from 'node:assert/strict'
import { lstatSync, readFileSync, realpathSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const port = Number(process.env.SUPERIOR_TEST_PORT), phase = process.env.SUPERIOR_PHASE, version = process.env.SUPERIOR_TEST_VERSION
if (!Number.isInteger(port) || port < 29010 || port > 29029 || !['initial', 'restart'].includes(phase)
    || !['1.21.11', '26.2'].includes(version) || process.env.SUPERIOR_PROFILE !== 'commands'
    || !process.env.SUPERIOR_TEST_ROOT || !process.env.SUPERIOR_MINEFLAYER)
  throw new Error('Explicit private native command environment required')
const root = path.resolve(process.env.SUPERIOR_TEST_ROOT)
const samePath = (one, two) => process.platform === 'win32' ? one.toLowerCase() === two.toLowerCase() : one === two
if (path.basename(path.dirname(root)) !== 'local-servers' || !path.basename(root).startsWith('superior-runtime-')
    || lstatSync(root).isSymbolicLink() || !samePath(root, realpathSync(root))
    || !lstatSync(path.join(root, '.superior-owned')).isFile()
    || !lstatSync(path.join(root, '.superior-commands-owned')).isFile()) throw new Error('Owned command fixture required')
const properties = readFileSync(path.join(root, 'server.properties'), 'utf8')
const property = key => properties.split(/\r?\n/).find(line => line.startsWith(key + '='))?.slice(key.length + 1)
if (property('server-ip') !== '127.0.0.1' || Number(property('server-port')) !== port || property('online-mode') !== 'false')
  throw new Error('Private native listener guard failed')
const workspace = path.dirname(path.dirname(root))
const maintainedRoot = realpathSync(path.join(workspace, '.ai-control', 'workspace-local', 'pvc-wither-client'))
const modulePath = realpathSync(process.env.SUPERIOR_MINEFLAYER)
const relative = path.relative(maintainedRoot, modulePath)
if (!relative || relative.startsWith('..') || path.isAbsolute(relative) || path.basename(modulePath) !== 'index.js')
  throw new Error('Maintained workspace client required')
const { default: mineflayer } = await import(pathToFileURL(modulePath).href)
const connect = username => mineflayer.createBot({ host: '127.0.0.1', port, username, auth: 'offline', version, checkTimeoutInterval: 30000 })
const primary = connect('PVCSkyProbe'), auxiliary = connect('PVCSkyProbeB')
const messages = [[], []], waiters = [], commandPackets = [], tabPackets = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let started = false, finished = false, next = 0
const timer = setTimeout(() => finish(new Error('Native command fixture timeout')), 600000)
const auxiliarySpawned = new Promise((resolve, reject) => {
  auxiliary.once('spawn', resolve); auxiliary.once('error', reject)
  auxiliary.once('kicked', reason => reject(new Error('Auxiliary kicked: ' + JSON.stringify(reason))))
})
auxiliarySpawned.catch(finish)
for (const [index, bot] of [primary, auxiliary].entries()) {
  bot.on('message', message => {
    const text = (message.unsigned ?? message).toString(); messages[index].push(text)
    if (text.includes('SUPERIOR_')) console.log(text)
    for (const waiter of [...waiters]) if (waiter.index === index && waiter.test(text)) {
      clearTimeout(waiter.timer); waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(text)
    }
    if (text.includes('SUPERIOR_COMMAND_PROBE_FAIL') || text.includes('SUPERIOR_PROBE_FAIL')) finish(new Error(text))
  })
  bot.on('error', finish)
  bot.on('kicked', reason => finish(new Error(bot.username + ' kicked: ' + JSON.stringify(reason))))
  if (index === 0) {
    bot._client.on('declare_commands', packet => commandPackets.push(packet))
    bot._client.on('tab_complete', packet => tabPackets.push(packet))
  }
}
async function send(bot, text) { await pause(Math.max(0, next - Date.now())); next = Date.now() + 1200; bot.chat(text) }
function waitFor(test, from = messages[0].length, timeout = 30000, index = 0) {
  const previous = messages[index].slice(from).find(test)
  if (previous) return Promise.resolve(previous)
  return new Promise((resolve, reject) => {
    const waiter = { index, test, resolve, timer: setTimeout(() => {
      const position = waiters.indexOf(waiter); if (position >= 0) waiters.splice(position, 1)
      reject(new Error('Missing native command response'))
    }, timeout) }; waiters.push(waiter)
  })
}
async function probe(action, marker, timeout = 30000) {
  const from = messages[0].length; await send(primary, '/pvcskyprobe command-' + action)
  return waitFor(text => text.includes(marker), from, timeout)
}
async function status() {
  const text = await probe('status', 'SUPERIOR_COMMAND_STATUS')
  return Object.fromEntries([...text.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(match => [match[1],
    match[1] === 'ids' ? (match[2] === 'none' ? [] : match[2].split(',').map(Number)) :
      match[2] === 'true' ? true : match[2] === 'false' ? false : Number(match[2])]))
}
async function until(test, timeout = 45000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const state = await status(); if (test(state)) return state; await pause(150) }
  throw new Error('Actual native command state did not converge')
}
async function create(count) {
  const from = messages[0].length; await send(primary, '/is create')
  await waitFor(text => text.includes('SUPERIOR_PLAYER_POST_CREATED count=' + count + ' '), from, 90000)
  return until(state => state.count === count && state.available)
}
const matches = values => values.map(value => typeof value === 'string' ? value : value.match ?? value.text ?? value.value ?? '').filter(Boolean)
async function userInterface(expectTools, label) {
  const from = messages[0].length
  for (let page = 1; page <= 4; page++) await send(primary, '/is help ' + page)
  await pause(500)
  const help = messages[0].slice(from).join('\n')
  const names = matches(await primary.tabComplete('/is ', true, false))
  let selectors, selectorResponse = 'packet'
  try {
    selectors = matches(await primary.tabComplete('/is home --island ', true, false, 5000))
  } catch (error) {
    if (expectTools || !/^Event tab_complete did not fire within timeout of 5000ms$/.test(error.message)) throw error
    // An unavailable selector can reject this Brigadier request without a reply.
    // Record the actual absence of an ID response, then require a fresh normal
    // completion packet to exclude a dead listener or disconnected client.
    selectors = []; selectorResponse = 'none-within-5000ms'
    const ordinary = matches(await primary.tabComplete('/is ', true, false))
    assert(ordinary.some(value => value.toLowerCase() === 'help'), 'Normal completion did not acknowledge the live native listener after a hidden-selector request')
  }
  assert.equal(names.some(value => value.toLowerCase() === 'list'), expectTools, label + ' native list TAB visibility')
  assert.equal(names.some(value => value.toLowerCase() === 'select'), expectTools, label + ' native select TAB visibility')
  assert.equal(/\b(?:is|island|islands) list\b/i.test(help), expectTools, label + ' native list help visibility')
  assert.equal(/\b(?:is|island|islands) select\b/i.test(help), expectTools, label + ' native select help visibility')
  assert.equal(selectors.length > 0, expectTools, label + ' native temporary-selector TAB visibility')
  assert(tabPackets.length > 0, 'No actual server tab-complete packet was observed')
  console.log('SUPERIOR_COMMAND_NATIVE_UI_PASS case=' + label + ' tools=' + expectTools + ' selectorResponse=' + selectorResponse + ' receivedTabPackets=' + tabPackets.length)
}
async function rate(islandId) {
  const opening = new Promise((resolve, reject) => {
    const timeout = setTimeout(() => { auxiliary.removeListener('windowOpen', opened); reject(new Error('Actual rating GUI was not opened')) }, 15000)
    const opened = window => { clearTimeout(timeout); resolve(window) }
    auxiliary.once('windowOpen', opened)
  })
  await send(auxiliary, '/is rate ' + islandId)
  const window = await opening
  assert(window.slots[7], 'Fixed official rating layout has no five-star button')
  await auxiliary.clickWindow(7, 0, 0)
  await pause(350)
  if (auxiliary.currentWindow) auxiliary.closeWindow(auxiliary.currentWindow)
}
async function approval(action) { await probe('approve-' + action, 'SUPERIOR_COMMAND_APPROVAL_DISPATCHED command=' + action) }

async function run() {
  await auxiliarySpawned
  if (phase === 'restart') {
    await until(state => state.count === 2 && state.levelA === 3 && state.levelB === 2 && state.sizeA === 52 && state.sizeB === 52
      && state.memberA && state.memberB && state.missionA && !state.missionB && state.ratingsA === 0 && state.ratingsB === 1)
    await probe('restartfinish', 'SUPERIOR_COMMAND_RESTART_PASS')
    console.log('SUPERIOR_COMMAND_CLIENT_PASS restart actors=2 ids=durable commands=persisted'); finish(); return
  }
  await probe('prepare', 'SUPERIOR_COMMAND_PREPARED')
  await send(primary, '/is admin setsize PVCSkyProbe 32'); await approval('setsize')
  await until(state => state.count === 0 && state.ownerOverride === 32)
  await probe('zero-sizecheck', 'SUPERIOR_COMMAND_ZERO_OWNED_SIZE_PASS')
  await create(1); await send(primary, '/is name PVCCommandA')
  await userInterface(false, 'quota1-singleton')
  await probe('limit3', 'SUPERIOR_COMMAND_QUOTA quota=3')
  await userInterface(false, 'quota3-singleton')
  const two = await create(2); await send(primary, '/is name PVCCommandB')
  const [a, b] = two.ids; assert(a > 0 && b > a)
  await userInterface(true, 'quota3-two-islands')
  await probe('limit1', 'SUPERIOR_COMMAND_QUOTA quota=1')
  await userInterface(false, 'quota1-grandfathered-two-islands')
  assert.deepEqual((await status()).ids, [a, b], 'Quota revocation removed an island')
  await probe('limit3', 'SUPERIOR_COMMAND_QUOTA quota=3')
  await send(primary, '/is select ' + a); await until(state => state.selected === a)
  await send(primary, '/is home --island ' + a); await pause(1000)
  await send(primary, '/is setwarp pvc-public')
  await send(auxiliary, '/is warp ' + a + ' pvc-public'); await until(state => state.auxPhysical === a)
  await probe('warpcheck', 'SUPERIOR_COMMAND_PUBLIC_WARP_PASS')

  await rate(a); await rate(b); await until(state => state.ratingsA === 1 && state.ratingsB === 1)
  await send(primary, '/is admin removeratings PVCSkyProbeB')
  await send(primary, '/is select ' + b); await approval('removeratings')
  await until(state => state.ratingsA === 0 && state.ratingsB === 0)
  await rate(a); await rate(b); await until(state => state.ratingsA === 1 && state.ratingsB === 1)
  await send(primary, '/is admin removeratings PVCSkyProbeB --island ' + a)
  await approval('removeratings')
  await until(state => state.ratingsA === 0 && state.ratingsB === 1)
  await probe('ratingscheck', 'SUPERIOR_COMMAND_RATINGS_PASS')

  await send(auxiliary, '/is admin join ' + a); await until(state => state.memberA)
  await send(auxiliary, '/is admin join ' + b); await until(state => state.memberA && state.memberB)
  await probe('auxlimit3', 'SUPERIOR_COMMAND_AUX_QUOTA quota=3')
  await send(primary, '/is select ' + b); await until(state => state.selected === b)
  await send(auxiliary, '/is localchat --island ' + a + ' PVC_NATIVE_MEMBER_CHAT')
  await send(auxiliary, '/is localchat --island ' + a)
  await probe('chatcheck', 'SUPERIOR_COMMAND_LOCALCHAT_PASS')
  await send(auxiliary, '/is localchat --island ' + a)
  await send(auxiliary, '/is home --island ' + b); await until(state => state.auxPhysical === b)

  await send(primary, '/is rankup pvc-native --island ' + a)
  await until(state => state.levelA === 2 && state.levelB === 1)
  await send(primary, '/is admin rankup ' + b + ' pvc-native')
  await until(state => state.levelA === 2 && state.levelB === 2)
  await probe('upgradecheck', 'SUPERIOR_COMMAND_UPGRADE_PASS')
  await probe('items', 'SUPERIOR_COMMAND_ITEMS_READY')
  await send(primary, '/is mission complete pvc_command_items --island ' + a)
  await until(state => state.missionA && !state.missionB && state.levelA === 3 && state.levelB === 2)
  await probe('missioncheck', 'SUPERIOR_COMMAND_MISSION_REWARD_PASS')

  await send(primary, '/is promote PVCSkyProbeB --island ' + a)
  await send(primary, '/is promote PVCSkyProbeB --island ' + b)
  await until(state => state.roleA === 1 && state.roleB === 1)
  await probe('demote-snapshot', 'SUPERIOR_COMMAND_DEMOTE_SNAPSHOT')
  await send(primary, '/is admin demote PVCSkyProbeB --island ' + a)
  await send(primary, '/is select ' + b); await approval('demote')
  await until(state => state.roleA === 0 && state.roleB === 1)
  await probe('demotecheck', 'SUPERIOR_COMMAND_DANGEROUS_DEMOTE_PASS')
  await probe('stale-demote-snapshot', 'SUPERIOR_COMMAND_STALE_DEMOTE_SNAPSHOT')
  await send(primary, '/is admin demote PVCSkyProbeB --island ' + b)
  await send(primary, '/is promote PVCSkyProbeB --island ' + b); await until(state => state.roleB === 2)
  await approval('demote'); await pause(500)
  await probe('stale-demotecheck', 'SUPERIOR_COMMAND_STALE_DEMOTE_PASS')

  await send(primary, '/is admin kick PVCSkyProbeB --island ' + a)
  await send(primary, '/is kick PVCSkyProbeB --island ' + a); await until(state => !state.memberA && state.memberB)
  await send(auxiliary, '/is admin join ' + a); await until(state => state.memberA && state.memberB)
  await approval('kick'); await pause(500)
  await probe('stale-kickcheck', 'SUPERIOR_COMMAND_STALE_KICK_PASS')
  await send(primary, '/is admin kick PVCSkyProbeB --island ' + a)
  await send(primary, '/is select ' + b); await approval('kick')
  await until(state => !state.memberA && state.memberB)
  await probe('kickcheck', 'SUPERIOR_COMMAND_DANGEROUS_KICK_PASS')
  await send(auxiliary, '/is admin join ' + a); await until(state => state.memberA && state.memberB)

  await probe('disbands-snapshot', 'SUPERIOR_COMMAND_DISBANDS_SNAPSHOT')
  const beforeDisbands = await status()
  await send(primary, '/is admin adddisbands * 3')
  await send(primary, '/is select ' + b); await approval('adddisbands')
  await until(state => state.disbandsPrimary === beforeDisbands.disbandsPrimary + 3
    && state.disbandsAuxiliary === beforeDisbands.disbandsAuxiliary + 3)
  await probe('disbandscheck', 'SUPERIOR_COMMAND_BULK_DISBANDS_PASS')

  await send(primary, '/is admin setsize PVCSkyProbe 48')
  await send(primary, '/is select ' + b); await approval('setsize')
  await until(state => state.sizeA === 48 && state.sizeB === 48)
  await send(primary, '/is admin addsize PVCSkyProbe 4')
  await send(primary, '/is select ' + a); await approval('addsize')
  await until(state => state.sizeA === 52 && state.sizeB === 52)
  await probe('sizecheck', 'SUPERIOR_COMMAND_DANGEROUS_SIZE_PASS')

  const primaryFrom = messages[0].length, auxiliaryFrom = messages[1].length
  await send(primary, '/is admin msgall ' + a + ' PVC_NATIVE_LITERAL_MSG --island id:0')
  await waitFor(text => text.includes('PVC_NATIVE_LITERAL_MSG --island id:0'), primaryFrom)
  await waitFor(text => text.includes('PVC_NATIVE_LITERAL_MSG --island id:0'), auxiliaryFrom, 30000, 1)
  await send(primary, '/is admin cmdall ' + a + ' true pvcskyprobe command-capture {player-name} --island id:0')
  await until(state => state.payloadActors === 2)
  await probe('payloadcheck', 'SUPERIOR_COMMAND_LITERAL_PAYLOAD_PASS')
  await send(primary, '/is select ' + b); await until(state => state.selected === b)
  await probe('finish', 'SUPERIOR_COMMAND_PROBE_PASS')
  console.log('SUPERIOR_COMMAND_CLIENT_PASS initial actors=2 nativeCommands=actual tabs=actualPackets commandTrees=' + commandPackets.length
    + ' ratings=actualGuiClicks upgrades=actualTargetLevels mission=actualInventoryCompletion approvals=actualConsole')
  finish()
}
function finish(error) {
  if (finished) return; finished = true; clearTimeout(timer)
  for (const waiter of waiters) clearTimeout(waiter.timer)
  if (error) { console.error(error.stack ?? error); process.exitCode = 1 }
  primary.quit(); auxiliary.quit(); setTimeout(() => process.exit(process.exitCode ?? 0), 500)
}
primary.on('spawn', () => { if (started) return; started = true; run().catch(finish) })
