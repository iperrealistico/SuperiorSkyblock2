# Genuine native merge/split observer

`NativeTopologyProbe` is test infrastructure, excluded from the plugin release.
It refuses production paths, ports outside 29010–29029, missing ownership
markers, non-Folia runtimes and grids whose saved ceiling is not 1000. It runs
once in a separate owned End fixture; root launches and stops that fixture.

The routed client command is `/pvcskyprobe advanced-native-topology`. Run it
after **both actual End reactivation arrivals** and before the occupied End reset.
Both real fixture clients must remain in End. AdvancedProbe emits
`SUPERIOR_NATIVE_TOPOLOGY_PASS` only after the returned future proves actual
native topology changes and removes all observer-owned tickets. Its original
actual merge/split/failure counter gates remain required at `advanced-finish`.
This tests native merge/split in the reserved **normal** island cell while the
independent two-island End battle sequence remains active; it does not claim
that the End arenas themselves merged.

## Maintained-source rationale

The official Folia `26.2.build.7-beta` development bundle contains these source
entries (the same contracts must be checked on the paired 1.21.11 lane):

- `patches/net/minecraft/server/level/ServerLevel.java.patch`: regionizer
  construction uses grid exponent, minimum recalculation sections, dead-section
  fraction `1/6`, empty-section radius and merge radius.
- `patches/io/papermc/paper/threadedregions/ThreadedRegionizer.java`: adding a
  section searches surrounding regions; removal marks dead sections; release
  prunes dead sections after the threshold and partitions disconnected sections.
- `patches/ca/spottedleaf/moonrise/paper/util/BaseChunkSystemHooks.java`:
  chunk-holder creation/deletion invokes real regionizer add/remove operations.
- `patches/org/bukkit/craftbukkit/CraftWorld.java` and
  `patches/net/minecraft/world/level/TicketStorage.java.patch`: plugin tickets
  use the actual native ticket store; removal does not load a chunk or synthesize
  a callback. These ticket APIs delegate to the thread-safe native store.

With the fixture's actual `grid-exponent: 4`, one section spans 16 chunks.
Minimum recalculation size is eight sections; the empty and merge radii are one.
The regionizer tracks **all native holders**, not only FULL chunks. An
entity-ticking plugin ticket therefore retains a wider section footprint than
its immediately visible FULL chunks. A contiguous 17×17 load can expand one
existing region without merging two. Removing one narrow bridge can also leave
too few dead sections to trigger a split.

## Geometry and real proof

Choose the first actually owned island's normal-world center. Derive its section
coordinates `(sx, sz)` from block coordinates shifted by eight. Requested chunk
coordinates use the middle chunk of each section, `(section * 16 + 8)`:

| Role | Section coordinates | Lifetime |
| --- | --- | --- |
| Anchor A | `(sx - 3, sz)` | Retained through partition proof |
| Anchor B | `(sx + 3, sz)` | Retained through partition proof |
| Bridge | `(sx, sz)` | Removed after real merge proof |
| Temporary perpendicular branch | `(sx, sz + 3)` | Removed with bridge |

Every requested FULL chunk and its radius-two FULL halo must belong to the same
canonical reserved island cell. The helper verifies the four halo corners
against actual grid ownership before loading. It generates no whole cell,
places no blocks and teleports no actor. Native holder propagation can also
create surrounding lightweight holders; the grid's empty spacing separates
these from occupied neighboring cells.

The perpendicular branch increases the merged region's dead section fraction
when removed. For the isolated default shape, about 70 section positions remain
before pruning, versus about 50 retained by the two anchors: roughly 20/70
become dead, above the real `1/6` threshold. The helper does not assume this
estimate proves a split; it requires the actual owner identities and callbacks.

1. Load/ticket both anchors and initialize the maintained tracker on each actual
   region owner. Poll the anchors until their native region IDs are distinct and
   stable across A–B–A owner queries. Record real tracker counter baselines.
2. Load/ticket bridge and branch. Require stable **equal** anchor region IDs and
   an increase of the real `RegionalTrackingFactory#getMergeCount()`.
3. Remove only this observer's bridge/branch tickets. Keep both anchors alive.
   Await natural native unload/recalculation; require stable **different**
   anchor region IDs and an increase of the real split counter.
4. Require the native failure counter to remain zero. Remove both anchors and
   verify that none of the four coordinates retains this observer's ticket.

Owner IDs come from the actual current `TickRegionScheduler` region's public
`id` field on its owning callback. No private callback is invoked, counter is
written, event is fabricated or regionizer is manually altered. During unload
polling, callbacks target only the retained anchors: scheduling a region task
on the emptied bridge could recreate tickets and prevent the split.

## Bounds, cleanup and limits

There are at most four observer plugin tickets and two concurrent native chunk
load requests. Each load has a 30-second bound, owner queries five seconds,
initial separation/merge waits 30 seconds each and the split wait 60 seconds.
The entire work future has a 150-second deadline, below the 180-second stage
budget. The fixture can keep `delay-chunk-unloads-by: 10s`; no production or
fixture configuration is changed by this helper.

Success, failure and the observer's real `PluginDisableEvent` all remove only
the four possible tickets belonging to this observer. Foreign plugin tickets
are never removed. Late owner callbacks observe closure before adding a ticket;
if cancellation races admission, they remove the late ticket immediately.
Cleanup errors fail the proof. Timeout is a diagnostic failure, not permission
to replace actual split/counter checks with an assumed result or retry forever.

This is a bounded lifecycle test, not a population/load benchmark. Preserve its
actual logs and matching final JAR identity with the release qualification.
