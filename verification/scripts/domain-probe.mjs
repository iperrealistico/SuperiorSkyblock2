import { pathToFileURL } from 'node:url'
import assert from 'node:assert/strict'

const port = Number(process.env.SUPERIOR_TEST_PORT)
if (port < 29010 || port > 29029 || !process.env.SUPERIOR_MINEFLAYER) throw new Error('Owned private fixture required')
const { default: mineflayer } = await import(pathToFileURL(process.env.SUPERIOR_MINEFLAYER).href)
const options = { host: '127.0.0.1', port, auth: 'offline', version: process.env.SUPERIOR_TEST_VERSION, checkTimeoutInterval: 30000 }
const primary = mineflayer.createBot({ ...options, username: 'PVCSkyProbe' })
const auxiliary = mineflayer.createBot({ ...options, username: 'PVCSkyProbeB' })
const messages = [], waiters = []
let started = false, finished = false, next = 0
const pause = delay => new Promise(resolve => setTimeout(resolve, delay))
const timeout = setTimeout(() => finish(new Error('Native domain timeout')), 500000)
const spawned = bot => new Promise((resolve, reject) => {
  bot.once('spawn', resolve); bot.once('error', reject)
  bot.once('kicked', reason => reject(new Error('Native client kicked: ' + JSON.stringify(reason))))
})
primary.on('message', message => {
  const text = (message.unsigned ?? message).toString(); messages.push(text)
  if (text.includes('SUPERIOR_')) console.log(text)
  if (text.includes('SUPERIOR_DOMAIN_FAIL') || text.includes('SUPERIOR_PROBE_FAIL')) finish(new Error(text))
  for (const waiter of [...waiters]) if (text.includes(waiter.marker)) {
    clearTimeout(waiter.timer); waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(text)
  }
})
async function send(text) {
  await pause(Math.max(0, next - Date.now())); next = Date.now() + 1200; primary.chat(text)
}
async function command(text, marker, delay = 140000) {
  const from = messages.length; await send(text)
  const existing = messages.slice(from).find(message => message.includes(marker))
  if (existing) return existing
  return new Promise((resolve, reject) => {
    const waiter = { marker, resolve, timer: setTimeout(() => {
      waiters.splice(waiters.indexOf(waiter), 1); reject(new Error('Missing native marker: ' + marker))
    }, delay) }; waiters.push(waiter)
  })
}
async function domain(action, marker) { return command('/pvcskyprobe domain-' + action, marker) }
async function ready() {
  for (let attempt = 0; attempt < 100; ++attempt) {
    const status = await domain('status', 'SUPERIOR_DOMAIN_STATUS')
    if (status.includes('ready=true')) return
    await pause(300)
  }
  throw new Error('Cold domain recovery did not converge')
}
async function run() {
  if (started) return; started = true
  await ready()
  const phase = process.env.SUPERIOR_PHASE
  if (phase === 'initial') {
    await command('/pvcskyprobe permissions', 'SUPERIOR_PERMISSIONS_READY')
    await domain('create-race', 'SUPERIOR_DOMAIN_CREATE_RACE_PASS')
    await domain('memberships', 'SUPERIOR_DOMAIN_MEMBERSHIP_PASS')
    await domain('select-first', 'SUPERIOR_DOMAIN_SELECTION_READY')
    const state = await command('/pvcskyprobe status', 'SUPERIOR_STATUS')
    const ids = state.match(/ids=([^ ]+)/)[1].split(',').map(Number)
    assert.equal(ids.length, 2)
    await send('/is home --island ' + ids[1]); await pause(2000)
    await domain('selected-stable', 'SUPERIOR_DOMAIN_SELECTOR_PASS')
    await domain('transfer-two', 'SUPERIOR_DOMAIN_TRANSFER_TWO_PASS')
    await domain('transfer-denied', 'SUPERIOR_DOMAIN_TRANSFER_DENIED_PASS')
    await domain('reset', 'SUPERIOR_DOMAIN_RESET_PASS')
    await domain('delete', 'SUPERIOR_DOMAIN_DELETE_PASS')
    await domain('checkpoint', 'SUPERIOR_DOMAIN_CHECKPOINT_ACK')
  } else if (phase === 'storage-reset-failure' || phase === 'storage-delete-failure' || phase === 'storage-reset-hold') {
    const deleting = phase.includes('delete')
    await domain(phase, 'SUPERIOR_DOMAIN_STORAGE_' + (deleting ? 'DELETE' : 'RESET') +
      (phase.endsWith('-hold') ? '_WORLD_ACK_HELD' : '_FAILURE_PASS'))
  } else if (phase.startsWith('storage-') && phase.endsWith('-restart')) {
    await domain('storage-restart-check', 'SUPERIOR_DOMAIN_STORAGE_RESTART_PASS')
  } else if (phase.endsWith('-intent')) {
    await domain('checkpoint', 'SUPERIOR_DOMAIN_CHECKPOINT_ACK')
    const action = phase.slice(0, -7)
    await domain(action + '-intent', 'SUPERIOR_DOMAIN_COLD_' + action.slice(5).toUpperCase() + '_INTENT_ACK')
  } else if (phase === 'delete-restart') {
    await domain('cold-delete-check', 'SUPERIOR_DOMAIN_COLD_DELETE_PASS')
  } else {
    await domain('restart-check', 'SUPERIOR_DOMAIN_RESTART_PASS')
  }
  console.log('SUPERIOR_CLIENT_PASS ' + phase); finish()
}
function finish(error) {
  if (finished) return; finished = true; clearTimeout(timeout)
  for (const waiter of waiters) clearTimeout(waiter.timer)
  if (error) { console.error(error.stack ?? error); process.exitCode = 1 }
  primary.quit(); auxiliary.quit(); setTimeout(() => process.exit(process.exitCode ?? 0), 500)
}
Promise.all([spawned(primary), spawned(auxiliary)]).then(run).catch(finish)
primary.on('error', finish); auxiliary.on('error', finish)
