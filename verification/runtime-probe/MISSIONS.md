# Two-client native mission observer

Root adds a lazy `NativeMissionProbe(this, superior)` adapter for
`/pvcskyprobe mission-<action>` and invokes `execute(Player, action)`. Instantiate
after the server finishes loading its mission module. The helper, driver and
fixture data are excluded from the release. No event is constructed or fired by
the observer: its listeners only record actual native client/world events.

Use an empty separate owned `local-servers/superior-runtime-*` fixture, bound to
127.0.0.1 on a free port 29010 through 29029. Both `.superior-owned` and
`.superior-missions-owned` must be regular marker files. Configure
`max-island-size: 96` and `default-values.island-size: 32`; the helper accepts a
hard cap up to 128 and a default radius of at least 24. This profile is separate
from exact Cielo LEGACY qualification. Keep the real Classic or normal schematic.

Copy the contents of `verification/fixtures/native-missions/` into the owned
fixture's `plugins/SuperiorSkyblock2/modules/missions/` before startup. The
native module extracts its actual embedded CraftingMissions, FarmingMissions
and ItemsMissions JARs. The isolated category defines three island missions:

- `pvc_craft`: craft four oak planks.
- `pvc_farm`: grow one wheat crop.
- `pvc_items`: deliver two stone items.

Automatic reward is disabled, progress is retained after completion, and there
are no rewards or external commands. Other category folders may remain, but
the fixture category configuration references only `pvc_native`.

Run `verification/scripts/mission-probe.mjs` with `SUPERIOR_PROFILE=missions`,
absolute `SUPERIOR_TEST_ROOT`, and the maintained `SUPERIOR_MINEFLAYER`,
`SUPERIOR_TEST_VERSION`, `SUPERIOR_TEST_PORT` and `SUPERIOR_PHASE` environment.
The driver independently validates the owned fixture, listener and maintained
client module. Versions are restricted to 1.21.11 and 26.2. Actual clients
`PVCSkyProbe` and `PVCSkyProbeB` connect normally to the private offline listener.
Permissions are assigned only to these fixed fixture accounts: ordinary island,
mission completion, home and selection access, plus an explicit ownership
limit of two for each real client. The auxiliary owns no island but
joins both subjects; its explicit quota grant allows the temporary selector
under the current multi-island command gate. The separate commands profile
retains the quota-one presentation and revocation checks. No group or
wildcard/OP permission is assigned.

The helper creates two real schematic islands A/B, adds the auxiliary as a
member of both through acknowledged metadata writes, and places small platforms
and farmland in their actual owning chunks. After A becomes ACTIVE, it advances
only the disposable fixture's actual grid cursor to a safely aligned far
coordinate through `GridManager.setLastIslandPosition` and the grid bridge's
durable SQL acknowledgement before creating B normally. The resulting actual
island centers must be at least 4096 blocks apart; the small reserved-cell cap
and reset footprint remain unchanged. The helper observes real post-creation
events when creation teleports are enabled, preventing an unfinished teleport
from changing the prepared actors. On Folia, preparation callbacks on each
actual player owner require `Bukkit.isOwnedByCurrentRegion(self)` to be true and
the same check for the other player to be false. `regionalActors=distinct` is
emitted only after both checks succeed; Leaf skips the Folia-only negation.
No production cursor, existing island or native event is modified/fabricated.
Both players receive ordinary test
materials and move through native teleports. The clients then execute the flow:

1. Primary physically crafts in A while selected B; auxiliary physically crafts
   in B while selected A. Both native result-slot clicks must be observed.
   Explicit `/is mission complete pvc_craft --island <A>` completes A alone;
   A/B retain separate progress and completion subjects.
2. Primary really plants wheat in A, then moves physically and persistently to
   B. Auxiliary applies bone meal to A's crop through real use-item packets.
   Actual place/grow/interaction events must occur, and farming belongs to A.
   Explicit mission completion from the primary in B completes A alone.
3. Native stone item entities are dropped beside primary, picked up normally,
   and observed in actual pickup events. Reading the shared A mission from both
   actor owners returns primary 2/auxiliary 0. Auxiliary then receives two own
   items. Completing A through primary consumes only primary's two stone;
   auxiliary's inventory and B completion remain intact. Auxiliary completes
   B through its own ordinary command and consumes its own two items.
4. Initial checkpoint saves mission data and records both durable island UUIDs,
   short IDs, selections, subject progress and completion state. The restart
   phase reconnects both actual clients and requires an identical checkpoint.
   Initial/restart also read the actual crafting/farming YAML subject records
   and native committed SQLite completion rows. Item inventory caches are
   intentionally ephemeral; an item-progress YAML is neither expected nor
   fabricated. A logged-but-swallowed mission save failure cannot pass this gate.

Progress snapshots use explicit island scopes and actual entity-owner callbacks.
Live inventories never cross actor boundaries. The source helper has no generic
item, command, name, permission or file-target parameters; all actions are fixed
to the isolated profile. No CraftingStore, Redis or production PVCItems operation
is involved.

Result markers are `SUPERIOR_MISSION_PROBE_PASS` and
`SUPERIOR_MISSION_RESTART_PASS`, followed by `SUPERIOR_MISSION_CLIENT_PASS` from
the actual client. Source preparation and compilation alone do not pass native
acceptance; root runs and retains the exact server/client evidence.
