import assert from 'node:assert/strict'
import { lstatSync, readFileSync, realpathSync } from 'node:fs'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const port = Number(process.env.SUPERIOR_TEST_PORT), phase = process.env.SUPERIOR_PHASE, version = process.env.SUPERIOR_TEST_VERSION
if (!Number.isInteger(port) || port < 29010 || port > 29029 || !['initial', 'restart'].includes(phase)
    || !['1.21.11', '26.2'].includes(version) || process.env.SUPERIOR_PROFILE !== 'missions'
    || !process.env.SUPERIOR_TEST_ROOT || !process.env.SUPERIOR_MINEFLAYER)
  throw new Error('Explicit private two-client mission environment required')
const root = path.resolve(process.env.SUPERIOR_TEST_ROOT)
const samePath = (one, two) => process.platform === 'win32' ? one.toLowerCase() === two.toLowerCase() : one === two
if (path.basename(path.dirname(root)) !== 'local-servers' || !path.basename(root).startsWith('superior-runtime-')
    || lstatSync(root).isSymbolicLink() || !samePath(root, realpathSync(root))
    || !lstatSync(path.join(root, '.superior-owned')).isFile()
    || !lstatSync(path.join(root, '.superior-missions-owned')).isFile()) throw new Error('Separate owned mission fixture required')
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
const messages = [], waiters = [], pause = ms => new Promise(resolve => setTimeout(resolve, ms))
let started = false, finished = false, next = 0
const timer = setTimeout(() => finish(new Error('Native mission fixture timeout')), 360000)
const auxiliarySpawned = new Promise((resolve, reject) => {
  auxiliary.once('spawn', resolve); auxiliary.once('error', reject)
  auxiliary.once('kicked', reason => reject(new Error('Auxiliary kicked: ' + JSON.stringify(reason))))
})
// Register immediately: a rejected auxiliary connection must not become an
// unhandled promise while the primary connection is still configuring.
auxiliarySpawned.catch(finish)
for (const bot of [primary, auxiliary]) {
  bot.on('message', message => {
    const text = (message.unsigned ?? message).toString()
    if (bot === primary) {
      messages.push(text)
      if (text.includes('SUPERIOR_')) console.log(text)
      for (const waiter of [...waiters]) if (waiter.test(text)) {
        clearTimeout(waiter.timer); waiters.splice(waiters.indexOf(waiter), 1); waiter.resolve(text)
      }
    }
    if (text.includes('SUPERIOR_MISSION_PROBE_FAIL') || text.includes('SUPERIOR_PROBE_FAIL')) finish(new Error(text))
  })
  bot.on('error', finish)
  bot.on('kicked', reason => finish(new Error(bot.username + ' kicked: ' + JSON.stringify(reason))))
}
async function send(bot, text) { await pause(Math.max(0, next - Date.now())); next = Date.now() + 1200; bot.chat(text) }
function waitFor(test, from = messages.length, timeout = 30000) {
  const previous = messages.slice(from).find(test)
  if (previous) return Promise.resolve(previous)
  return new Promise((resolve, reject) => {
    const waiter = { test, resolve, timer: setTimeout(() => {
      const index = waiters.indexOf(waiter); if (index >= 0) waiters.splice(index, 1)
      reject(new Error('Missing native mission response'))
    }, timeout) }; waiters.push(waiter)
  })
}
async function mission(action, marker, timeout = 30000) {
  const from = messages.length; await send(primary, '/pvcskyprobe mission-' + action)
  return waitFor(text => text.includes(marker), from, timeout)
}
async function status() {
  const text = await mission('status', 'SUPERIOR_MISSION_STATUS')
  return Object.fromEntries([...text.matchAll(/([A-Za-z]+)=([^ ]+)/g)].map(match => [match[1],
    match[2] === 'true' ? true : match[2] === 'false' ? false : Number(match[2])]))
}
async function until(test, timeout = 30000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) { const state = await status(); if (test(state)) return state; await pause(150) }
  throw new Error('Native mission state did not converge')
}
function position(bot, xyz) {
  const point = bot.entity.position.clone(); point.x = xyz[0]; point.y = xyz[1]; point.z = xyz[2]; return point
}
async function craft(bot) {
  const planks = bot.registry.itemsByName.oak_planks
  assert(planks, 'Native registry has no oak planks')
  const recipe = bot.recipesFor(planks.id, null, 1, null)[0]
  assert(recipe, 'Real supplied oak logs do not expose the 2x2 crafting recipe')
  await bot.craft(recipe, 1, null)
}
async function complete(bot, name, islandId) {
  await send(bot, '/is mission complete ' + name + ' --island ' + islandId)
}
async function run() {
  await auxiliarySpawned
  if (phase === 'restart') {
    await until(state => state.craftA === 4 && state.craftB === 4 && state.farmA === 1 && state.farmB === 0
      && state.craftDoneA && !state.craftDoneB && state.farmDoneA && !state.farmDoneB && state.itemsDoneA && state.itemsDoneB)
    await mission('restartfinish', 'SUPERIOR_MISSION_RESTART_PASS')
    console.log('SUPERIOR_MISSION_CLIENT_PASS restart actors=2 subjects=persisted'); finish(); return
  }
  const prepared = await mission('prepare', 'SUPERIOR_MISSION_PREPARED', 150000)
  const first = Number(prepared.match(/first=(\d+)/)[1]), second = Number(prepared.match(/second=(\d+)/)[1])
  const crop = prepared.match(/crop=([\d,-]+)/)[1].split(',').map(Number)
  assert(first > 0 && second > first)
  const baseline = await status()
  assert.equal(baseline.primarySelected, second); assert.equal(baseline.auxSelected, first)
  assert.equal(baseline.craftA, 0); assert.equal(baseline.craftB, 0)
  // Actual result-slot clicks from two different physical island regions.
  await Promise.all([craft(primary), craft(auxiliary)])
  await until(state => state.craftA === 4 && state.craftB === 4 && state.primaryCraftEvents > 0 && state.auxCraftEvents > 0)
  await complete(primary, 'pvc_craft', first)
  await until(state => state.craftDoneA && !state.craftDoneB)
  await mission('craftcheck', 'SUPERIOR_MISSION_CRAFT_ISOLATION_PASS')

  await mission('plant', 'SUPERIOR_MISSION_PLANT_READY')
  const seeds = primary.inventory.items().find(item => item.name === 'wheat_seeds')
  assert(seeds); await primary.equip(seeds, 'hand')
  let farmland
  for (let attempt = 0; attempt < 20; attempt++) {
    farmland = primary.blockAt(position(primary, [crop[0], crop[1] - 1, crop[2]]))
    if (farmland?.name === 'farmland') break
    await pause(200)
  }
  assert.equal(farmland?.name, 'farmland', 'Native fixture farmland was not received')
  await primary.placeBlock(farmland, position(primary, [0, 1, 0]))
  await until(state => state.plantEvents > 0)
  await mission('grow', 'SUPERIOR_MISSION_GROW_READY')
  const meal = auxiliary.inventory.items().find(item => item.name === 'bone_meal')
  assert(meal); await auxiliary.equip(meal, 'hand')
  for (let attempt = 0; attempt < 12; attempt++) {
    const block = auxiliary.blockAt(position(auxiliary, crop))
    if (block?.name !== 'wheat') { await pause(250); continue }
    if (Number(block.getProperties().age) === 7) break
    await auxiliary.activateBlock(block); await pause(350)
  }
  await until(state => state.farmA === 1 && state.farmB === 0 && state.growEvents > 0 && state.boneMealEvents > 0)
  // The placer is physically and persistently selected on B when A's crop grows.
  assert.equal((await status()).primarySelected, second)
  await complete(primary, 'pvc_farm', first)
  await until(state => state.farmDoneA && !state.farmDoneB)
  await mission('farmcheck', 'SUPERIOR_MISSION_FARM_ISOLATION_PASS')

  await mission('items', 'SUPERIOR_MISSION_NATIVE_ITEMS_DROPPED')
  await until(state => state.primaryItemsA === 2 && state.auxItemsA === 0 && state.pickupEvents > 0)
  await mission('actorcache', 'SUPERIOR_MISSION_ACTOR_CACHE_PASS')
  await mission('auxitems', 'SUPERIOR_MISSION_AUX_ITEMS_READY')
  await until(state => state.primaryItemsA === 2 && state.auxItemsA === 2)
  await complete(primary, 'pvc_items', first)
  await until(state => state.itemsDoneA && !state.itemsDoneB && state.primaryStone === 0 && state.auxStone === 2)
  await mission('consumecheck', 'SUPERIOR_MISSION_NATIVE_CONSUMPTION_PASS')
  await complete(auxiliary, 'pvc_items', second)
  await until(state => state.itemsDoneA && state.itemsDoneB && state.primaryStone === 0 && state.auxStone === 0)
  await mission('finish', 'SUPERIOR_MISSION_PROBE_PASS')
  console.log('SUPERIOR_MISSION_CLIENT_PASS initial actors=2 nativeCraft=2 nativeFarm=1 nativePickup=1 consumption=isolated')
  finish()
}
function finish(error) {
  if (finished) return; finished = true; clearTimeout(timer)
  for (const waiter of waiters) clearTimeout(waiter.timer)
  if (error) { console.error(error.stack ?? error); process.exitCode = 1 }
  primary.quit(); auxiliary.quit(); setTimeout(() => process.exit(process.exitCode ?? 0), 500)
}
primary.on('spawn', () => { if (started) return; started = true; run().catch(finish) })
