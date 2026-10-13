# Release verification

**Status: qualified for the exact builds and scenarios below on 2026-10-13.**
All 87 final native phases passed with the same identified main JAR. Automated
build gates and retained restarts also passed. The portable
[qualification ledger](../../verification/qualification-2026.3-pvc.1.json)
records the final artifact, native inputs and phase results. This record does
not authorize a production installation, permission change or restart.

## Artifact and provenance

| Property | Recorded value |
| --- | --- |
| Plugin version | `2026.3-pvc.1` |
| Compiled code commit | `f8fcdcbad8619e469108150a8daa830abf105c02` |
| Release source tree reference | `pvc/2026.3-pvc.1` |
| Qualification date | 2026-10-13 |
| Upstream baseline | `868bebf41df19615621828619f59e2d8386f7ace`, version `2026.3` |
| Main JAR SHA-256 | `03F5DF57E83A3D61EEEF7602AF84D04DC1EDA5BC472704590FEBDA1F4618BBAB` |
| Main JAR size | 8,836,554 bytes |
| Full clean builds | Two independent outputs match byte for byte for main, probe and seed JARs |
| License | Original GPL-3.0 and upstream package/API identity retained |

The source build normalizes ZIP metadata in source-built Paperweight module
archives before embedding them. All compiled class and resource bytes matched
before and after this packaging normalization. Archive metadata was the source
of an earlier reproducibility difference; code payloads remain identical.
Every final native phase running the fork used the exact main JAR digest above.
The upstream seed phase deliberately used the unchanged original. The release
tree additionally contains the completed documentation and sanitized evidence;
the compiled code revision remains the one recorded above.

## Completed automated gates

| Gate | Result |
| --- | --- |
| JUnit | PASS: 110 tests across 26 reports, zero failures |
| Strict dependency provenance | PASS: 695 artifact identities, 5,731 recorded uses |
| API artifact inspection | PASS: 363 inspected API classes |
| Embedded platform resources | PASS: all 18 NMS resources and both modern Folia adapters inspected |
| Two full clean builds | PASS: identical final main JAR size and SHA-256 |

The resolver, bounded owner cache/provider admission, serialized override
storage, configuration migration, ownership reservations, SQL transaction
failure/rollback and mission subject/coordinate tests cover their declared
contracts. Concurrent stress includes 50 worker threads and 10,000 owner
identities. These are software concurrency tests, not Minecraft player counts.

Artifact inspection recorded this class-version distribution:

| Class major | Java version | Class count |
| --- | --- | ---: |
| 52 | 8 | 2,595 |
| 55 | 11 | 4 |
| 61 | 17 | 28 |
| 65 | 21 | 60 |
| 69 | 25 | 14 |

Core/API keep the Java 8 baseline. Existing adapters retain their own baselines;
modern scheduler/NMS adapters load through explicit conditional boundaries.
Minecraft 1.21.11 uses Java 21 and Minecraft 26.2 uses Java 25 in this matrix.
This distribution does not make modern Minecraft runnable on Java 8.

## Final native matrix

All selected entries below are **PASS** on the identified final JAR. The completed
matrix contains 87 phase runs: core 8, LEGACY 8, missions 8, domain 56, Folia End
4 and populated upstream upgrade 3. Restart phases reused the populated fixture
rather than recreating its state. Earlier candidate runs are diagnostic history
and do not contribute to these totals.

| Platform / Minecraft | Core: initial + restart | LEGACY: initial + restart | Missions: initial + restart | Domain: 14 phases | End: initial + restart |
| --- | --- | --- | --- | --- | --- |
| Leaf 1.21.11 | PASS | PASS | PASS | PASS | Not selected |
| Leaf 26.2 | PASS | PASS | PASS | PASS | Not selected |
| Folia 1.21.11 | PASS | PASS | PASS | PASS | PASS |
| Folia 26.2 | PASS | PASS | PASS | PASS | PASS |

| Populated upgrade | Phases | Result |
| --- | --- | --- |
| Leaf 26.2 | Unchanged upstream seed → fork migration → retained fork restart | PASS |

The selected native server inputs are fixed builds, not a claim about the latest
available releases:

| Platform / Minecraft | Build / channel | Java | Official build metadata |
| --- | --- | --- | --- |
| Leaf 1.21.11 | 174 / default | 21 | [Build 174](https://api.leafmc.one/v2/projects/leaf/versions/1.21.11/builds/174) |
| Leaf 26.2 | 123 / experimental | 25 | [Build 123](https://api.leafmc.one/v2/projects/leaf/versions/26.2/builds/123) |
| Folia 1.21.11 | 14 / stable | 21 | [Build 14](https://fill.papermc.io/v3/projects/folia/versions/1.21.11/builds/14) |
| Folia 26.2 | 7 / beta | 25 | [Build 7](https://fill.papermc.io/v3/projects/folia/versions/26.2/builds/7) |

| Server input | SHA-256 |
| --- | --- |
| Leaf 1.21.11 build 174 | `1E38B84653381B63640829312D8EE209E94D6A12D660DD47787A59871ECA7B4C` |
| Leaf 26.2 build 123 | `AA84A1816829721F3C48E918E3A06236470570E4BA575BD4191FB2CA6D461C07` |
| Folia 1.21.11 build 14 | `F52C408490A0225611E67907A3CA19F7E6DA2C6BC899E715D5F46844E7103C39` |
| Folia 26.2 build 7 | `128A634192261CD38BB4A5DC54075018A0F896FD6C6F529E37DCA6E99E32B3B3` |

## Native acceptance criteria

**Core and entitlements.** Ordinary native creation/home and three durable
owned islands must work. Highest effective numeric quota/radius wins without
addition; explicit unlimited and deliberate parent wildcards are distinguished
from OP alone. Grant/revocation, donor-style radius changes, durable owner
overrides, concurrent additive admin commands, clear override, selectors and
stable UUID/short IDs/selection are checked across a retained restart.
The implementation contract applies owner policy to current and future islands;
it never stores a permission-derived radius as a native fixed override.
See [ENTITLEMENTS.md](ENTITLEMENTS.md).

**LEGACY and populated migration.** Existing operator configuration receives
only missing defaults, including `pvc.size-mode: LEGACY`. Original settings and
the byte-identical pre-migration backup must survive; a retained restart must
leave the migrated configuration unchanged. The unchanged original creates a
real populated island before the JAR-only upgrade. Its UUID, owner, center/home,
effective/raw size, roles and actual stable block-state samples must remain
identical; new short ID and singleton selection must persist. Automatic sizing
remains an explicit opt-in, with chosen rank values, outside-building disabled
and incompatible border-size upgrades removed.

**Domain and recovery.** Concurrent creation and incoming transfers must obey
active-plus-reserved quota accounting. Membership roles and explicit targets
must stay island-specific. Actual reset/delete sentinels, neighboring terrain,
identity preservation, short-ID tombstones, cold intent recovery and native
storage failure/held-ACK recovery must pass their fixed scenarios. Failure must
retain an incomplete operation and its cell; a logged error or incomplete SQL
intent cannot substitute for a durable completion acknowledgement.
See the [domain observer](../../verification/runtime-probe/DOMAIN.md).

The controlled storage-error phases install a guarded observer-only wrapper
around the native storage adapter. Ordinary calls delegate unchanged; exactly
one target-chunk save returns an injected `IOException`. The operation future
must fail, its canonical ownership/cell and unfinished journal must remain
reserved, and the neighboring sentinel must survive. The wrapper is restored
before the phase reports success; retained restart then checks actual recovery.

The selected abrupt-stop phase intercepts one target reset save **before calling
the original native adapter save**, after a real committed SQL intent and native
world mutation. Its returned future remains uncompleted without blocking a
thread; that target physical save has not started. After observing this boundary,
the runner terminates only its owned isolated fixture JVM before the storage
deadline and restarts the retained files to verify recovery. This is a real
process termination at a controlled pre-delegation save boundary, not a crash
during native disk I/O, an operating-system/hardware power-loss or a
disk-corruption test. Normal cold-intent phases use ordinary stop/restart and
are a separate controlled interruption.
No failure injection or process termination facility is in the main plugin.

**Missions.** Two native clients perform real crafting, crop growth and item
pickup/consumption with opposite physical/selected island contexts. Island UUID
subjects, actor-local inventories, neighboring progress, native completion SQL
and actual mission persistence files must remain isolated across restart.
Folia preparation must prove the clients occupy distinct actual region actors.
No mission reward or external command is fabricated.
See the [mission observer](../../verification/runtime-probe/MISSIONS.md).

**Folia End.** Two actual clients must occupy separate shared-world islands with
distinct native dragons/boss UUIDs and 289 actual plugin chunk tickets per
arena. Require real native dragon death/exit portal, four live crystals, a
genuine respawn stage and a different live dragon UUID; actual departure must
release arena reservations and physical tickets. Explicit boss register/hide/
re-add packets are checked on the actual inactive fights and delivered only to
their intended clients. Native fight visibility itself continues to follow the
live dragon state.

Reactivation must restore actual arena ownership/tickets. A separate bounded
normal-world topology stage must observe distinct → merged → split real region
identities, genuine tracker callbacks, zero tracker failures and removal of its
four observer tickets. This does not claim the independent End arenas merged.
Occupied End A reset must complete its real future, native storage and new
`RESET_DONE` SQL acknowledgement, evacuate the primary to the configured safe
common spawn, preserve UUID/short ID, clear generated state/sentinel, remove A's
fight/tickets and leave B's actual client, live dragon and sampled terrain
unchanged. Final departure must release all remaining arena tickets.
See the [End observer](../../verification/runtime-probe/ADVANCED.md) and
[topology proof](../../verification/runtime-probe/TOPOLOGY.md).

## Earlier diagnostics and corrective work

Retained earlier diagnostics informed maintained-source changes for native
Folia asynchronous arrival/presence, immutable chunk-position lifetimes,
region-owned initial dragon part/path geometry, acknowledged battle/ticket
removal, durable SQL/world completion and bounded existing-chunk cleanup.
Subsequent source changes retain native FULL chunks through persistence/readback
acknowledgement and actual owner ticket release, and hand Paper/Leaf world work
back to its required main thread after asynchronous persistence. Their additional
tests are included in the 110-test gate; the final matrix passed with the combined
native implementation.
The final matrix above verifies the combined implementation; those diagnostics
do not count as final passing phases.

Observer/fixture corrections respect native behavior: synchronous teleport
assumptions were replaced with actual arrival observations; explicit boss hide
is tested after genuine arena departure because live native ticks restore
visibility. Real bedrock waiting platforms avoid the death-created exit portal,
and a real saved common-spawn floor prevents gravity in the void fixture.
Read-only command retries occur only after an observed native transition denial.
Terrain samples include actual persisted stable platforms. Assertions,
operation deadlines, native stage/UUID checks and durable acknowledgements remain
required. The fixtures do not fabricate Bukkit or region lifecycle events.

## Limits and operator handoff

- Tests use fixed synthetic accounts through actual native protocol clients on
  isolated offline listeners. They do not qualify production authentication,
  Bungee routing, live network traffic or every optional command/GUI/reward hook.
- There is no 50-player or larger population/TPS benchmark. Bounded queues,
  chunk budgets and concurrency tests are not production capacity measurements.
  Automatic profiles use shortened refresh intervals for controlled testing.
- Native platform selection covers the four exact builds above. Older retained
  adapters are build/packaging checks, not newly executed native lanes. Later
  Minecraft/Folia versions require maintained-source adapters and a new matrix.
- End/dragon lifecycle qualification is Folia-only in this matrix. It does not
  imply the same advanced End scenarios were run on Leaf.
- The shared default world/creation provider is the qualified topology target.
  Slime/per-island-world and custom world/mission providers need their own
  tested contracts. Custom database bridges are rejected in every mode by this
  release; no opt-in exists. The fork does not make other backend plugins Folia-safe.
- Terrain checks inspect real selected block-state samples, including fixture
  platforms; they are not exhaustive preservation checks of all terrain,
  entities or inventories. Controlled interruption/failure tests do not prove
  operating-system power-loss behavior.
- Private configuration, player data, logs and fixture directories are excluded
  from this portable record and release. Probe/seed helpers and fault injection
  are excluded from the distributable main plugin.

Follow [INSTALLATION.md](INSTALLATION.md) for a separately authorized normal stopped
JAR replacement, consistent SQL/world/plugin-data backup and rollback. Avoid
PlugMan/hot replacement. Returning to upstream after multi-island state requires
the complete consistent pre-upgrade snapshot rather than a JAR-only rollback.
