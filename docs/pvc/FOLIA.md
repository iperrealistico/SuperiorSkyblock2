# Folia architecture and operating requirements

This document describes the maintained execution and storage contracts in the
PVC source fork. It is not a native test result or an authorization to change a
server. Use the exact artifact identity, platform builds, completed scenarios
and limitations in the release [verification record](VERIFICATION.md) when
published. Compilation, `folia-supported`, and successful startup alone do not
qualify a release. Installation, backups, world configuration and rollback are
covered in [INSTALLATION.md](INSTALLATION.md).

## Platform boundaries

The native Folia targets are Minecraft **1.21.11** and **26.2**. The core and API
retain their Java 8 source/bytecode baseline, and existing upstream platform
adapters remain in the artifact. Modern code is loaded lazily through explicit
adapter boundaries:

| Component | Java baseline | Responsibility |
| --- | --- | --- |
| Core/API | Java 8 | Domain metadata, public contracts and lazy platform boundaries |
| `Hooks/Folia` | Java 21 | Paper/Folia global, region, entity and asynchronous schedulers |
| `NMS/Folia-1_21_11` | Java 21 | Exact 1.21.11 native tracking, storage and regional dragon integration |
| `NMS/Folia-26_2` | Java 25 | Exact 26.2 native tracking, storage and regional dragon integration |

Retained upstream NMS adapters keep their respective platform Java baselines.
Use the Java version required by the actual server release. A Java 8 core does
not make modern Minecraft runnable on Java 8. The scheduler bridge also serves
matching modern Paper/Leaf releases; its use does not imply region threading on
those platforms.

`QualifiedNativeVersion` accepts exact `1.21.11`/`26.2`, their ordinary Bukkit
`-...` metadata, and canonical `.build.<digits>[-...]` metadata such as
`26.2.build.7-beta`. It does not alias `26.2.1`, `1.21.110` or a future release to
the nearest adapter. An unknown Folia native tracking/storage ABI fails closed.
Future Minecraft/Folia versions require maintained-source changes and a fresh
qualification matrix; upstream merging does not provide automatic support.

## Native asynchronous player arrival

The exact official Folia source bundles for both targeted versions distinguish
native asynchronous teleporting from Bukkit's usual synchronous teleport flow.
`CraftEntity.teleportAsync` invokes `Entity.teleportAsync`; that path retains an
event TODO rather than firing `PlayerTeleportEvent`. `ServerPlayer` fires
`PlayerChangedWorldEvent` in its synchronous teleport implementation, which
Folia rejects. A listener for those two events alone therefore cannot establish
physical arrival on Folia. These contracts are checked against the official
[Folia maintained source](https://github.com/PaperMC/Folia) and exact source
bundles used by the corresponding native modules.

For plugin-owned player teleports, `EntityTeleports` runs the ordinary island
movement/protection preflight before calling the async provider. Denial prevents
the native teleport. Successful completion follows the player's entity owner,
checks the actual world, canonical island and captured world generation, then
reconciles physical inside membership, defensive location snapshots, current
effects/flight/time/weather/border policy and End-fight activation. Failure and
retirement cannot report successful arrival; cancelled preflight is reconciled
back to the player's actual position. Paper/Leaf retain their native cancellable
Bukkit event flow.

Folia's `ServerLevel` destination tracking callback fires the real
`EntityAddToWorldEvent` while the player is valid. The Folia-only arrival observer
uses that event for vanilla portals and external asynchronous arrivals as well.
It performs one coalesced entity-following check per player, with canonical
identity/generation validation and the configured chunk-load deadline. It does
not fabricate Bukkit teleport events or claim to cancel an external teleport
that has already physically completed. Native qualification remains recorded
separately in [VERIFICATION.md](VERIFICATION.md).

## Shared worlds and startup

The supported built-in topology uses the default shared-world provider: islands
occupy reserved cells in the same configured dimension worlds. Per-island-world
and slime-world addons are separate integrations. Custom world, creation,
database and mission providers must implement the relevant durable identity and
Folia capability contracts; a capability flag is not evidence of correctness.
See the provider requirements in [INSTALLATION.md](INSTALLATION.md) and
[MISSION-MIGRATION.md](MISSION-MIGRATION.md).

Folia's provider adopts **already loaded** worlds. It never substitutes runtime
`WorldCreator` creation/loading for startup preparation. Every enabled configured
dimension must have the exact name, environment and SuperiorSkyblock2 void
generator expected by the plugin. Missing worlds, a wrong environment or a
different generator are reported as startup failures.

The plugin loads early (`STARTUP`) so Bukkit can obtain its bootstrap void
generator. Full world/provider initialization is deferred until the server has
loaded its worlds; a one-shot `ServerLoadEvent` path avoids initializing against
an absent default world. Existing loaded chunk identities are adopted after
initialization, with tracker installation dispatched to their actual region
owners. Future chunk loads install their region-local trackers as well.

For the minimal normal-world layout, align `server.properties` `level-name`,
SuperiorSkyblock2's configured shared world name, and the `bukkit.yml` generator
entry. Enabled Nether/End worlds must also be created by the server's startup
dimension arrangement under the configured names and corresponding environments.
Detailed examples are in [INSTALLATION.md](INSTALLATION.md). Changing a generator
entry does not convert existing terrain to void.

A common spawn in the preloaded normal world is the minimal Folia arrangement.
A separate spawn world needs its own qualified startup loading arrangement; the
fork does not dynamically load an existing separate Leaf spawn on Folia. A safe
spawn platform and first join, island home, void/death respawn and any external
spawn plugin must be rehearsed with the proposed topology.

Paper/Leaf retain the default provider's existing separate-world loading path.
Do not apply the Folia `level-name` arrangement to an unchanged Leaf setup: that
path rejects an island world already loaded before the provider. Migrating a
backend to Folia is a separate world/configuration and plugin-stack operation.
This fork does not make other installed plugins or addons Folia-compatible.

## Thread ownership and retirement

`BukkitExecutor` separates four execution domains:

| Domain | Allowed work |
| --- | --- |
| Global | Plugin/domain metadata and bounded coordination; not live world ownership |
| Region | Blocks, chunk capture, NMS chunk sessions, region-local tickers and world mutations at owned coordinates |
| Entity | Player/entity state, inventory, packets, teleport completion and actions that follow an entity across regions |
| Asynchronous | Detached snapshot serialization, bounded IO/provider work and immutable computation |

On Folia, `sync`, `global` and `SYNC_EXECUTOR` mean global metadata execution;
they do not grant access to every island or player. World operations dispatch
by chunk coordinates. Player operations follow the entity scheduler and recheck
their current world/location and canonical island identity before applying
delayed effects. Location snapshots are defensive copies for foreign readers;
they are not a license to access live blocks from an asynchronous callback.

Entity retirement is handled separately from ordinary task cancellation. A
player leaving or an entity being removed must not leave a pending callback
pretending that its actor still exists. Scheduler handles close cleanly during
shutdown, reject new admission and deliver retirement handling at most once.
No chunk, permission or storage future is joined on a Folia tick thread.

The native tracking factories integrate with Folia's actual region-data
merge/split lifecycle. Region-local block ticking and neighbor-update tracking
preserve the native queues instead of replacing them with a global ticker.
Split/merge counters represent callbacks received from the server; creating a
synthetic event is not a substitute for qualification of actual region changes.

## Bounded runtime budgets

Missing-only configuration migration adds these defaults under `pvc.runtime`:

```yaml
pvc:
  runtime:
    chunk-load-concurrency: 8
    chunk-load-queue-limit: 512
    chunk-load-timeout-seconds: 30
    dragon-arena-chunk-budget: 1156
    dragon-arena-queue-limit: 128
    dragon-arena-activation-timeout-seconds: 60
```

These allocation settings require a normal restart. Existing values are
preserved and validated; increasing a limit is not a performance guarantee.

Chunk loading has bounded global admission, coalesces equivalent requests and
streams chunk operations instead of allocating a future for every cell chunk.
Each ordinary sequential operation holds at most one load/callback in flight.
Independent reset event/clear stages and modern storage acknowledgements instead
share one lazy round-robin bulk window across all islands, using the same
`chunk-load-concurrency` (default 8) and `chunk-load-queue-limit` (default 512
admitted bulk jobs). The window remains occupied until each owner's callback
and real IO acknowledgement completes; it is not multiplied per island, and
there is no future allocated for every coordinate. This avoids three repeated
one-owner-tick-per-chunk passes over a large existing End footprint. A failed
job stops admitting coordinates and drains already-admitted work before failing
its caller; shutdown fails callers while keeping unresolved slots in a permanently
closed pipeline. Existing-only loads, world-generation fences and all physical
block/entity/POI readback/flush barriers remain mandatory.
After shutdown or timeout, wrapper counters do not prove that uncancellable
native IO has drained; the closed service and retained intent prohibit replacement
admission until restart reconciliation.
The storage acknowledgement service has its own bounded admission using the
same configured concurrency, queue limit and deadline. Queue residence counts
toward those deadlines.

A queued chunk request is retired atomically before it can start after its
deadline. If an in-flight chunk request times out, new island operations and
chunk/storage admission stop rather than recycling slots while uncancellable
native futures remain alive. Storage acknowledgement timeouts also stop new
admission. Preserve the durable operation state, resolve the reported cause and
restart normally for reconciliation; do not erase journals or repeatedly retry
creation/reset/delete to bypass the failure.

Radius changes do not generate a whole island cell. Loaded crop/ticker
recalibration is coalesced per island and spread across bounded scheduling
batches; later chunk loads apply the current policy. Cleanup covers the reserved
cell through batched region-file metadata queries, then loads and clears only
existing chunks. It does not generate every coordinate in a large cell. A large
`max-island-size` still increases possible terrain, cleanup and player-view
costs; the runtime budgets do not make that cost disappear.

The maintained SQL gateway has one serialized writer and at most 1024 queued
writes in addition to that active writer. It acknowledges actual JDBC transaction completion and
does not treat callback submission as a commit. Entitlement/provider budgets
are separate; see [ENTITLEMENTS.md](ENTITLEMENTS.md).

## World writes and genuine storage acknowledgement

SQL durability and Minecraft world durability are different barriers. A create,
paste, reset or deletion must not become publicly complete merely because its
metadata write was submitted or a native save task was queued.

The write pipeline establishes these barriers:

1. Persist the operation/cell reservation and planned dirty footprint through
   the maintained SQL acknowledgement path before physical writes begin.
2. Capture a world-generation token and perform each physical chunk mutation
   on its current owner. Native write admission is serialized against advancing
   that token; stale work cannot enqueue data for a newer island generation.
3. Capture complete block, entity and POI payloads on the owning region. Detached
   serialization and native IO run off the tick thread. Incomplete entity/POI
   state is retried within the deadline, rather than serialized as empty data.
4. Submit all three payload types to their native ordered region IO paths.
   Check native write failure state and perform real region-file readback.
   A temporary namespaced NBT token distinguishes the captured payload from a
   newer native save; superseded snapshots receive only bounded retries.
5. Complete the native region-file flush contract and propagate IO failure.
   Only after world storage acknowledgement may cleanup clear its dirty state
   or the domain publish its final committed operation state.

The disk check does not use a native pending-write cache as proof of disk
contents. A `World.save()` call or an immediately returned native future is not
used as a modern Folia acknowledgement. The exact modern adapters use ordered
readback plus synchronous native file flushing for block, entity and POI data.
Legacy non-Folia platforms retain their upstream world-save contract once per
operation/world; that fallback can be expensive and is never used on Folia.

Dirty snapshots are revisioned and SQL admission is serialized with the planned
write intent, preventing an older coalesced snapshot from erasing a newer
planned footprint. Paste completion carries an immutable receipt captured inside
the callback. Later creation stages do not reread transient shared schematic
fields. Delayed entity spawns retain the original generation and require the
same canonical, available island before they run.

Reset/delete cleanup also checks existing region-file metadata beyond the
planned footprint, covering stored chunks produced by native physics. It uses
existing-only loads and the same storage barrier before final completion. A
failed or uncertain SQL/world write leaves its durable intent for reconciliation;
it must not be interpreted as a successful reset or a reusable occupied cell.

On Folia, resetting any island dimension evacuates its current players to the
configured common spawn. In particular, a Nether/End reset does not target the
same island's normal home while its reset journal makes that island unavailable.
This keeps ordinary canonical/availability/generation checks intact; after the
reset finishes, players can use their normal island home command again.
Paper/Leaf retain upstream's non-default-dimension evacuation to the unchanged
normal island home, and default-dimension resets evacuate to spawn on all
platforms.

### Leaf region-file ABI

Leaf's maintained configurable region-format patch changes the Moonrise cache
getter return descriptor from `RegionFile` to `abomination.IRegionFile`, including
when the selected format is ordinary Anvil. See the maintained
[Leaf patch at commit 5f64751](https://github.com/Winds-Studio/Leaf/blob/5f64751/leaf-server/minecraft-patches/features/0107-Luminol-Configurable-region-format-framework.patch)
and its [IRegionFile contract](https://github.com/Winds-Studio/Leaf/blob/5f64751/leaf-server/src/main/java/abomination/IRegionFile.java).

`NativeRegionFiles` resolves the cache getters by name and parameters and caches
methods per runtime class, avoiding a hard-coded JVM return descriptor. It also
invokes the actual file's `hasChunk(ChunkPos)` and synchronous `flush()` contract.
Absent files remain absent, and native IO exceptions remain failed
acknowledgements. This is a source-level adapter, not a binary patch or a fallback
that bypasses readback. Qualification of one concrete Leaf configuration does
not qualify every alternative region format or future Leaf ABI.

## Regional End fights

Each active Folia End fight requests a contiguous **17 × 17 chunk** arena
(289 chunks) around its island center. Activation loads/tickets chunks on their
owners and waits until the complete arena is owned by the fight actor's current
region. The default budget of 1156 reserves at most four such arenas at once;
the 128-entry waiting queue and 60-second activation deadline prevent unbounded
preloading. The budget counts reservations per arena, not the entire server's
loaded chunks, neighboring Folia sections or other plugins' tickets.

Fight state, portal/spike geometry and native ticking are scoped to that island
and its region. Viewer discovery does not scan foreign live player locations.
The exact regional geometry adapters position every multipart dragon body at
its actual arena location before native AI first queries its AABBs. They also
initialize vanilla's 24-node navigation graph using the unchanged adjacency
table and island-relative owner-local heightmap reads, preserving spike-aware
height adjustment. This prevents newly created/loaded parts and initial path
queries from accessing the vanilla origin before upstream offsetting runs;
subsequent movement, collision, damage and phase AI remain native.
Boss-bar delivery uses immutable state and the viewer's entity scheduler, with
ordered hide/remove/re-add handling. Entry into an enabled End island wakes its
fight; arenas without End visitors release their plugin tickets and become
dormant rather than keeping every player's End permanently loaded.

Overlapping plugin tickets are reference-counted. Release acknowledgements
retain failed ticket identities and the arena reservation; a failed removal does
not free budget while native tickets may still exist. Bounded retries and logged
failure preserve that accounting. Native fight, respawn, boss-bar, ticket release
and actual region merge/split scenarios belong in the release verification
record; their implementation alone is not a claim that those scenarios passed.

An End reset awaits actual player evacuation, removes/stops its cached fight,
and awaits native dragon removal and all real plugin-ticket release
acknowledgements before clearing chunks. Repeated removal requests share the
pending completion; failed ticket removals retain their identities/reservations
and fail the reset rather than returning an early success. Existing-only cold
cleanup must not register a fight from old dragon NBT for an unavailable,
noncanonical or not-generated island; such stale entities are retired on their
owner instead of resuming fight AI.

## Maintainer acceptance

Keep the core/modern boundary, exact-version factories and separate patch
families documented in [PATCHES.md](PATCHES.md). Integrate upstream updates on an
integration branch using [UPSTREAM.md](UPSTREAM.md), then repeat the required
Java/API, configuration/data migration, Paper/Leaf and both Folia version gates.
Native qualification must exercise physical world results, restart/recovery,
IO failures, retirement, budgets and enabled built-in features, not just log
messages or logical metadata. Final evidence must identify the same released
JAR and exact runtime builds; no production rollout is implied by that evidence.
