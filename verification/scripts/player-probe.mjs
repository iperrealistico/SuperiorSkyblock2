import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'

const port = Number(process.env.SUPERIOR_TEST_PORT)
if (port < 29010 || port > 29029 || !process.env.SUPERIOR_MINEFLAYER) throw new Error('Private fixture required')
const { default: mineflayer } = await import(pathToFileURL(process.env.SUPERIOR_MINEFLAYER).href)
const bot = mineflayer.createBot({ host: '127.0.0.1', port, username: 'PVCSkyProbe', auth: 'offline',
  version: process.env.SUPERIOR_TEST_VERSION, checkTimeoutInterval: 30000 })
const messages = [], waiters = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let next = 0, finished = false, started = false, teleportTrace = false
const advancedEnd = process.env.SUPERIOR_PROFILE === 'end'
// Protocol diagnostics contain only fixed local-test command text and loading
// metadata, never signing keys, signatures or account credentials.
function traceProtocol(client, label) {
  const write = client.write
  client.write = function (name, data) {
    if (['chat_command', 'chat_command_signed', 'chat_message', 'teleport_confirm', 'player_loaded', 'configuration_acknowledged', 'finish_configuration'].includes(name))
      console.log('SUPERIOR_PROTOCOL_OUT ' + label + ' ' + name + ' ' + JSON.stringify({ command: data?.command, teleportId: data?.teleportId }))
    return write.call(this, name, data)
  }
  client.on('state', (state, previous) => console.log('SUPERIOR_PROTOCOL_STATE ' + label + ' ' + previous + '->' + state))
  client.on('packet', (data, meta) => {
    if (['position', 'respawn', 'start_configuration', 'finish_configuration', 'game_state_change', 'login'].includes(meta.name))
      console.log('SUPERIOR_PROTOCOL_IN ' + label + ' ' + meta.name + ' ' + JSON.stringify({ x: data.x, y: data.y, z: data.z,
        teleportId: data.teleportId, reason: data.reason, gameMode: data.gameMode, worldName: data.worldName }))
  })
}
if (advancedEnd) traceProtocol(bot._client, 'primary')
const timeout = setTimeout(() => finish(new Error('Native fixture timeout')), advancedEnd ? 900000 : 450000)
const bossTrace = [[], []]
let auxiliary, auxiliarySpawn
bot._client.on('boss_bar', packet => bossTrace[0].push({ id: packet.entityUUID.toLowerCase(), action: packet.action, at: Date.now() }))
if (advancedEnd && process.env.SUPERIOR_PHASE !== 'restart') {
  auxiliary = mineflayer.createBot({ host: '127.0.0.1', port, username: 'PVCSkyProbeB', auth: 'offline',
    version: process.env.SUPERIOR_TEST_VERSION, checkTimeoutInterval: 30000 })
  auxiliarySpawn = new Promise((resolve, reject) => {
    auxiliary.once('spawn', resolve); auxiliary.once('error', reject)
    auxiliary.once('kicked', reason => reject(new Error('Auxiliary kicked: ' + JSON.stringify(reason))))
  })
  auxiliarySpawn.catch(finish)
  traceProtocol(auxiliary._client, 'auxiliary')
  auxiliary._client.on('boss_bar', packet => bossTrace[1].push({ id: packet.entityUUID.toLowerCase(), action: packet.action, at: Date.now() }))
}
bot.on('message', message => {
  const text = (message.unsigned ?? message).toString()
  messages.push(text)
  if (text.includes('SUPERIOR_')) console.log(text)
  else if (teleportTrace || advancedEnd) console.log('SUPERIOR_CLIENT_TELEPORT_FEEDBACK ' + text)
  if (text.includes('SUPERIOR_PROBE_FAIL')) finish(new Error(text))
  for (const waiter of [...waiters]) if (waiter.test(text)) {
    clearTimeout(waiter.timer); waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(text)
  }
})
async function send(text) {
  await pause(Math.max(0, next - Date.now())); next = Date.now() + 1200; bot.chat(text)
}
// A real Folia teleport can delay entity-owned command delivery while its
// native destination chunks settle. Observe beyond the plugin's 30s bounded
// storage/load deadline; do not replace native errors with a retry or fake ACK.
function waitFor(test, from = messages.length, delay = 45000) {
  const existing = messages.slice(from).find(test)
  if (existing) return Promise.resolve(existing)
  return new Promise((resolve, reject) => {
    const waiter = { test, resolve, timer: setTimeout(() => {
      waiters.splice(waiters.indexOf(waiter), 1); reject(new Error('Missing native response'))
    }, delay) }; waiters.push(waiter)
  })
}
async function command(text, marker, delay = 45000) {
  const readOnly = text === '/pvcskyprobe status' || text.startsWith('/pvcskyprobe physical ')
  // Folia explicitly rejects a packet sent during temporary entity removal.
  // Retry only these two reads after that observed native denial; never retry
  // a mutation, infer a successful response, or suppress a plugin failure.
  for (let attempt = 0; attempt < (readOnly ? 4 : 1); ++attempt) {
    const from = messages.length; await send(text)
    try {
      const answer = await waitFor(value => value.includes(marker) ||
        (readOnly && value.includes('Chat disabled in client options.')), from, delay)
      if (answer.includes(marker)) return answer
      console.log('SUPERIOR_READ_RETRY_NATIVE_TRANSITION ' + text)
      await pause(300)
    } catch (error) { throw new Error('Missing native response to ' + text + ' (expected ' + marker + ')', { cause: error }) }
  }
  throw new Error('Native entity transition repeatedly rejected the read: ' + text)
}
async function advanced(action, marker, delay = 110000) { return command('/pvcskyprobe advanced-' + action, marker, delay) }
async function status() {
  const text = await command('/pvcskyprobe status', 'SUPERIOR_STATUS')
  const entries = Object.fromEntries([...text.matchAll(/(count|quota|ids|radii|selected|available)=([^ ]+)/g)].map(match => [match[1], match[2]]))
  return { count: Number(entries.count), quota: Number(entries.quota), selected: Number(entries.selected), available: entries.available === 'true',
    ids: entries.ids === 'none' ? [] : entries.ids.split(',').map(Number),
    radii: entries.radii === 'none' ? [] : entries.radii.split(',').map(Number) }
}
async function until(test) {
  for (let i = 0; i < 35; ++i) { const state = await status(); if (state.available && test(state)) return state; await pause(250) }
  throw new Error('Native state did not converge')
}
async function radius(value) { return until(state => state.count > 0 && state.radii.every(radius => radius === value)) }
async function policy(action) { await command('/pvcskyprobe ' + action, 'SUPERIOR_POLICY_SAVED') }
async function createSettled(count) {
  const from = messages.length
  await send('/is create')
  // ACTIVE is the durable world/SQL boundary, but its automatic player
  // teleport finishes later. Observe the real success-only API event before
  // sending another packet during Folia's temporary entity removal.
  await waitFor(text => text.includes('SUPERIOR_PLAYER_POST_CREATED count=' + count + ' '), from, 60000)
  return until(state => state.count === count)
}

async function run() {
  if (process.env.SUPERIOR_PHASE === 'restart') {
    const state = await until(state => state.count === 3 && state.radii.every(radius => radius === 120))
    assert.equal(state.quota, 3)
    await command('/pvcskyprobe restartfinish', 'SUPERIOR_RESTART_PASS')
    console.log('SUPERIOR_CLIENT_PASS restart'); finish(); return
  }
  await command('/pvcskyprobe permissions', 'SUPERIOR_PERMISSIONS_READY'); await pause(1500)
  await advanced('opbaseline', 'SUPERIOR_ADV_OP_BASELINE_PASS')
  await advanced('wildcard', 'SUPERIOR_ADV_WILDCARD_PASS')
  await advanced('revokewildcard', 'SUPERIOR_ADV_WILDCARD_REVOKED_PASS')
  assert.equal((await status()).count, 0)
  const first = await createSettled(1)
  assert.equal(first.quota, 1); assert.equal(first.radii[0], 100)
  await send('/is create'); await pause(2000)
  assert.equal((await status()).count, 1, 'Default owner created a second island')
  await policy('limit3'); await until(state => state.quota === 3)
  await createSettled(2)
  const three = await createSettled(3)
  assert.equal(new Set(three.ids).size, 3)
  await send('/is create'); await pause(2000)
  assert.equal((await status()).count, 3, 'Quota exceeded')
  await policy('radius80'); await radius(80)
  await policy('radius160'); await radius(160)
  await policy('revoke160'); await radius(80)
  await policy('donor240'); await radius(240)
  await command('/pvcskyprobe override120', 'SUPERIOR_ADMIN_REQUESTED'); await radius(120)
  await policy('revokedonor'); await radius(120)
  await advanced('addsizepair', 'SUPERIOR_ADV_ADDSIZE_PAIR_PASS'); await radius(130)
  await command('/pvcskyprobe override120', 'SUPERIOR_ADMIN_REQUESTED'); await radius(120)
  await command('/pvcskyprobe clearoverride', 'SUPERIOR_ADMIN_REQUESTED'); await radius(80)
  await policy('donor240'); await radius(240)
  await command('/pvcskyprobe override120', 'SUPERIOR_ADMIN_REQUESTED'); await radius(120)
  await policy('limit1'); await until(state => state.quota === 1)
  await send('/is create'); await pause(2000)
  assert.equal((await status()).count, 3, 'Quota revocation deleted or admitted islands')
  await policy('limit3'); await until(state => state.quota === 3)
  await send('/is select ' + three.ids[0]); await until(state => state.selected === three.ids[0])
  teleportTrace = true
  await send('/is home --island ' + three.ids[1])
  let reached = false
  for (let attempt = 0; attempt < 25; ++attempt) {
    const result = await command('/pvcskyprobe physical ' + three.ids[1], 'SUPERIOR_PHYSICAL_HOME_')
    if (result.includes('SUPERIOR_PHYSICAL_HOME_PASS')) { reached = true; break }
  }
  assert(reached, 'Native home did not reach the requested owned island within 30 seconds')
  teleportTrace = false
  assert.equal((await status()).selected, three.ids[0], 'One-command selector changed persistent selection')
  await send('/is list'); await pause(1000)
  if (advancedEnd) {
    await auxiliarySpawn
    await command('/pvcskyprobe clearoverride', 'SUPERIOR_ADMIN_REQUESTED'); await radius(240)
    await advanced('opbaseline', 'SUPERIOR_ADV_OP_BASELINE_PASS')
    await advanced('wildcard', 'SUPERIOR_ADV_WILDCARD_PASS')
    await advanced('revokewildcard', 'SUPERIOR_ADV_WILDCARD_REVOKED_PASS')
    await advanced('endprepare', 'SUPERIOR_ADV_END_PREPARED')
    await advanced('endenter', 'SUPERIOR_ADV_END_ENTERED')
    await advanced('endactive', 'SUPERIOR_ADV_TWO_ACTIVE_FIGHTS_PASS')
    const fights = await command('/pvcskyprobe advanced-status', 'SUPERIOR_ADV_FIGHTS')
    const ids = ['firstBoss', 'secondBoss'].map(key => fights.match(new RegExp(key + '=([^ ]+)'))[1].toLowerCase())
    assert.notEqual(ids[0], ids[1])
    for (let client = 0; client < 2; ++client) {
      assert(bossTrace[client].some(packet => packet.id === ids[client] && packet.action === 0), 'Missing native arena boss add packet')
      assert(!bossTrace[client].some(packet => packet.id === ids[1 - client]), 'Foreign arena boss packet delivered')
    }
    const hideAt = Date.now(); await advanced('bosshide', 'SUPERIOR_ADV_BOSS_HIDE_PASS'); await pause(1000)
    for (let client = 0; client < 2; ++client)
      assert(bossTrace[client].some(packet => packet.id === ids[client] && packet.action === 1 && packet.at >= hideAt), 'Missing native boss remove packet')
    const showAt = Date.now(); await advanced('bossshow', 'SUPERIOR_ADV_BOSS_SHOW_PASS'); await pause(1000)
    for (let client = 0; client < 2; ++client)
      assert(bossTrace[client].some(packet => packet.id === ids[client] && packet.action === 0 && packet.at >= showAt), 'Missing native boss re-add packet')
    await advanced('killfirst', 'SUPERIOR_ADV_NATIVE_DRAGON_DEATH_STARTED')
    await advanced('deathwait', 'SUPERIOR_ADV_NATIVE_DRAGON_DEATH_PASS')
    await advanced('respawnfirst', 'SUPERIOR_ADV_NATIVE_RESPAWN_STARTED')
    await advanced('respawnwait', 'SUPERIOR_ADV_NATIVE_RESPAWN_PASS')
    await advanced('endleave', 'SUPERIOR_ADV_END_LEFT')
    await advanced('released', 'SUPERIOR_ADV_INACTIVE_TICKETS_RELEASED_PASS')
    await advanced('endreactivate', 'SUPERIOR_ADV_REACTIVATED_PASS')
    await advanced('finish', 'SUPERIOR_ADVANCED_PROBE_PASS')
    await advanced('endleave', 'SUPERIOR_ADV_END_LEFT')
    await advanced('released', 'SUPERIOR_ADV_INACTIVE_TICKETS_RELEASED_PASS')
    await policy('limit3'); await until(state => state.quota === 3)
    await command('/pvcskyprobe override120', 'SUPERIOR_ADMIN_REQUESTED'); await radius(120)
    console.log('SUPERIOR_CLIENT_BOSS_TRACE ' + JSON.stringify(bossTrace))
  }
  await command('/pvcskyprobe finish', 'SUPERIOR_PROBE_PASS')
  console.log('SUPERIOR_CLIENT_PASS initial quotas=1 highest=1 revoke=1 donor=1 override=1 ids=1 selector=1'); finish()
}
function finish(error) {
  if (finished) return; finished = true; clearTimeout(timeout)
  for (const waiter of waiters) clearTimeout(waiter.timer)
  if (error) { console.error(error.stack ?? error); process.exitCode = 1 }
  auxiliary?.quit(); bot.quit(); setTimeout(() => process.exit(process.exitCode ?? 0), 500)
}
bot.on('spawn', () => { if (started) return; started = true; run().catch(finish) })
bot.on('windowOpen', window => {
  // The exact current Cielo fixture has a single Classic creation button in slot 4.
  // Exercise its native GUI rather than replacing the operator's existing menu file.
  pause(250).then(() => bot.clickWindow(4, 0, 0)).catch(finish)
})
bot.on('error', finish)
bot.on('kicked', reason => finish(new Error('Kicked: ' + JSON.stringify(reason))))
