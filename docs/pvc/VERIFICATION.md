# Release verification

**Status: qualified for the exact builds and scenarios below on 2026-10-13.**
All 95 selected native phase runs completed successfully; every
phase running the fork used the exact identified main JAR. The populated upstream
seed deliberately ran the unchanged original JAR. Two full clean builds,
automated/API/artifact gates and retained restarts passed. The portable
[qualification ledger](../../verification/qualification-2026.3-pvc.2.json) records
the exact artifact, native inputs and sanitized phase results. This does not
authorize a production installation, permission change or restart.

The [preceding qualification](../../verification/qualification-2026.3-pvc.1.json)
is retained unchanged as historical evidence for `2026.3-pvc.1`; its outcomes do
not substitute for the new exact-artifact matrix.

## Artifact and provenance

| Property | Recorded value |
| --- | --- |
| Plugin version | `2026.3-pvc.2` |
| Compiled code commit | `21ee0ecd44fe07d5a114d6fda2b405a2563e121b` |
| Release source tree reference | `pvc/2026.3-pvc.2` |
| Qualification date | 2026-10-13 |
| Upstream baseline | `868bebf41df19615621828619f59e2d8386f7ace`, version `2026.3` |
| Main JAR SHA-256 | `A860A1DD9A56C250FA805204D79D7F0254DD4319D4183245DEF5203461D2AF2D` |
| Main JAR size | 8,866,847 bytes |
| Full clean builds | Two independent main JAR outputs match byte for byte |
| License | Original GPL-3.0 and upstream package/API identity retained |

Source-built Paperweight module archives have reproducible ZIP metadata before
embedding. This changes packaging metadata, not compiled class/resource payloads;
official native server inputs remain unmodified. Documentation and sanitized
qualification records are subsequently committed in the release tree; the
compiled-code revision is the one recorded above.

## Completed automated gates

| Gate | Result |
| --- | --- |
| JUnit | PASS: 172 tests across 37 reports, zero failures/errors |
| Strict dependency provenance | PASS: 695 artifact identities, 5,731 recorded uses |
| API artifact inspection | PASS: 363 inspected API classes |
| Embedded platform resources | PASS: original platform resources and both modern Folia adapters inspected |
| Two full clean builds | PASS: identical final main JAR size and SHA-256 |

Tests cover the declared quota/radius, cache/provider admission, serialized
storage, configuration migration, ownership reservations, SQL failure/recovery,
mission subject/coordinate and command-intent contracts. Focused source tests
cover explicit versus inherited destinations, payload escaping, business-level
upgrade acknowledgement, deferred charging, honest partial failures, per-island
roles and membership invalidation. The native command profile additionally
exercises TTL expiry with one bounded owner-scheduler resume after authoritative
quota refresh and the same-handler captured dispatch against real mutations.
Software concurrency tests are not Minecraft player-count benchmarks.

Artifact inspection recorded this class-version distribution:

| Class major | Java version | Class count |
| --- | --- | ---: |
| 52 | 8 | 2,607 |
| 55 | 11 | 4 |
| 61 | 17 | 28 |
| 65 | 21 | 60 |
| 69 | 25 | 14 |

Core/API retain their Java 8 baseline. Existing adapters retain their own Java
baselines; modern scheduler/NMS adapters use conditional loading. The actual
Minecraft 1.21.11 lanes use Java 21 and 26.2 lanes Java 25. This distribution
does not make modern Minecraft runnable on Java 8.

## Final native matrix

All selected entries are **PASS** on the new artifact. The matrix comprises
commands 8, core 8, LEGACY 8,
missions 8, domain 56, Folia End 4
and populated upstream upgrade 3 phase runs. Restart phases
reuse populated state; diagnostic candidate runs do not contribute to the totals.

| Platform / Minecraft | Commands: initial + restart | Core: initial + restart | LEGACY: initial + restart | Missions: initial + restart | Domain: 14 phases | End: initial + restart |
| --- | --- | --- | --- | --- | --- | --- |
| Leaf 1.21.11 | PASS | PASS | PASS | PASS | PASS | Not selected |
| Leaf 26.2 | PASS | PASS | PASS | PASS | PASS | Not selected |
| Folia 1.21.11 | PASS | PASS | PASS | PASS | PASS | PASS |
| Folia 26.2 | PASS | PASS | PASS | PASS | PASS | PASS |

| Populated upgrade | Phases | Result |
| --- | --- | --- |
| Leaf 26.2 | Unchanged upstream seed → fork migration → retained fork restart | PASS |

These are fixed qualified builds, not a claim about the latest available release:

| Platform / Minecraft | Build / channel | Java | Official metadata |
| --- | --- | --- | --- |
| Leaf 1.21.11 | 174 / default | 21 | [Official build](https://api.leafmc.one/v2/projects/leaf/versions/1.21.11/builds/174) |
| Leaf 26.2 | 123 / experimental | 25 | [Official build](https://api.leafmc.one/v2/projects/leaf/versions/26.2/builds/123) |
| Folia 26.2 | 7 / BETA | 25 | [Official build](https://fill.papermc.io/v3/projects/folia/versions/26.2/builds/7) |
| Folia 1.21.11 | 14 / STABLE | 21 | [Official build](https://fill.papermc.io/v3/projects/folia/versions/1.21.11/builds/14) |

| Server input | SHA-256 |
| --- | --- |
| Leaf 1.21.11 build 174 | `1E38B84653381B63640829312D8EE209E94D6A12D660DD47787A59871ECA7B4C` |
| Leaf 26.2 build 123 | `AA84A1816829721F3C48E918E3A06236470570E4BA575BD4191FB2CA6D461C07` |
| Folia 26.2 build 7 | `128A634192261CD38BB4A5DC54075018A0F896FD6C6F529E37DCA6E99E32B3B3` |
| Folia 1.21.11 build 14 | `F52C408490A0225611E67907A3CA19F7E6DA2C6BC899E715D5F46844E7103C39` |

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

**Commands: eight additional phase runs.** Two actual native clients execute
commands and click real rating GUI buttons. Help text and TAB replies preserve
ordinary quota-one presentation, including retained properties after revocation;
quota above one with only one membership still hides personal ID-management tools.
Actual creation of another island exposes those tools. Selection, UUIDs and
short IDs survive revocation and retained restart. Actual command-tree packets
are recorded; Bukkit's opaque greedy argument tree is not described as fabricated
literal command nodes. The negative hidden-selector case accepts only the
documented bounded TAB-event timeout and then requires another actual ordinary
completion to prove the listener remains responsive.

The profile requires an external client to reach a public warp; real rating GUI
clicks and console approvals distinguish author-wide removal from explicit
island clearing; localchat keeps the actual member actor. Player/admin rankup
hooks use unchanged `%player%` commands against the captured island. A real
Items mission consumes that actor's inventory and its scheduled reward advances
only the completed island's upgrade. The formal `CapturedCommandDispatch` path
recognizes the plugin's actually registered labels/aliases and its own namespace,
executes the same registered handlers and validation, and leaves other plugins'
commands with Bukkit. Explicit configured destinations override the inherited
fallback. Successful dispatch alone cannot acknowledge level advancement.

Optional dangerous approvals retain owner UUID intent for zero/multiple/future
properties, member targets and frozen player sets. Selection changes do not
retarget requests; actual intervening role changes and remove/rejoin cycles
invalidate them. `msgall`/`cmdall` retain literal nested selector tokens. Restart
checks native identities, selection, roles, ratings, levels, mission completion,
owner overrides and disband counters. See the
[command observer](../../verification/runtime-probe/COMMANDS.md) and
[command contract](COMMANDS.md).

The upgrade fixture uses zero price. Real economy settlement and arbitrary
third-party commands are not qualified; source tests cover deferred charging,
actual-level acknowledgement, cancellation, external-only ordering and partial
failure. Purge targeting is covered by focused source tests, not by invoking
destructive native purge in this retained-evidence profile.

## Earlier diagnostics and corrective work

The preceding release's Folia arrival, retained FULL-chunk ticket/storage,
bounded native cleanup and world/SQL durability corrections remain in this
artifact and are rechecked by the new full matrix. The command audit identified
twelve gaps outside the original qualified scenarios; those gaps motivated the
maintained-source corrections and the additional command profile. Historical
PASS outcomes never established universal custom-command compatibility.

Observer-only fixture corrections use real modern item materials and rating
slots, public test warps, and a deliberately disabled private-fixture player
kick confirmation for its direct kick-command assertion. Production settings,
ordinary handler behavior and main plugin bytes were not patched to fabricate
success. Native ownership, actual mutations, retained state and phase outcomes
remain required.

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
