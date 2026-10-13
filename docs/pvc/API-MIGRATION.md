# API and addon migration

The fork retains upstream package names, original API entry points and the
Java 8 core/API baseline. New methods are additive. An old addon can still link
without implementing them, but linkage does not establish multi-island,
durability or Folia compatibility. Qualify addons against the exact release and
its recorded Minecraft 1.21.11 and 26.2 native lanes before deployment.

## Island identity, membership and selection

Use `Island#getUniqueId()` as the persistent island key. An owner UUID is no
longer a unique island key: one owner can own several islands, and an island can
change owner. `Island#getShortId()` is the durable, server-local display ID;
short IDs are never reused after deletion or abandoned creation. Names are
optional and mutable. Do not persist a name, list position or current owner as
the identity of an island.

| Previous assumption | Replacement |
| --- | --- |
| `player.getIsland()` means the player's only property | `player.getOwnedIslands()` or `grid.getOwnedIslands(ownerUUID)` enumerates ownership |
| One team island per player | `player.getIslands()` enumerates owned and team memberships |
| A visit counts against ownership quota | Visits and team membership do not consume ownership slots |
| Selection is the physical island | `player.getSelectedIsland()` reads persistent selection; resolve physical event targets from the event location |
| One role belongs to the player | `player.getPlayerRole(island)` and `player.setPlayerRole(island, role)` use the specific membership |
| `setIsland` moves membership | `selectIsland`/`setIsland` selects an existing membership; use the intended island's member-management API to change its team |

The core's legacy `getIsland()` follows an active execution scope first. Outside
a scope it can use the player's physical member island on the player's owning
thread, then persistent selection or a sole membership. It can return `null`
when several memberships have no valid selection. `hasIsland()` means that at
least one membership exists; it does not promise that `getIsland()` resolves an
unambiguous target. A scope for a non-member does not lend that player a role
from a different island.

Lists are snapshots, not reservations. Unpublished creation candidates are
excluded from public lists. Existing islands with an operation in progress can
still appear in a list; their presence does not authorize a concurrent mutation
or prove that an operation has completed. Use the operation's future and current
permissions instead of polling only an ownership count.

Use `Island#addMember`, `kickMember`, per-island roles and
`Island#hasPermission(actor, privilege)` against the captured target. Do not use
membership-wrapper helpers to bypass an island's member-management rules.
`PlayerChangeRoleEvent#getIsland()` identifies the affected membership; listeners
must not infer its target from a later selection.

## Commands, events and delayed callbacks

The ordinary single-island command syntax remains valid. In `2026.3-pvc.2`,
personal multiple-island presentation requires both a verified ownership quota
above one and more than one actual owned/team membership. `/is list` displays
short IDs, `/is select <selector>` (alias `/is use`) changes persistent selection,
and `--island <selector>` targets one command without changing selection when
that presentation is unlocked; permitted administrative selectors are independent
of the administrator's personal quota. A selector can be a short ID, UUID or
name; typed forms `id:`, `#`, `uuid:` and
`name:` disambiguate them. A plain numeric selector is an ID, even when an island
has a numeric name. Island-scoped named administration must reject several owned
islands rather than silently choosing one. A sole owned island takes priority
over other team memberships. Intentionally owner-wide commands,
such as automatic-mode size overrides, operate on that owner's policy instead.

`IslandExecutionScope` is lexical and thread-local:

```java
try (IslandExecutionScope ignored = IslandExecutionScope.open(target)) {
    PlayerRole role = actor.getPlayerRole(target);
    // Existing context-aware API calls now see this target.
}
```

The scope supplies context, not authority. Check membership, command permission
and island privilege separately. `open(target, false)` supplies implicit context;
`open(target)` supplies an explicit target. An active scope with a `null` target
suppresses automatic physical fallback. Always close a scope, including on
failure; nested scopes restore the preceding scope.

`IslandExecutionScope.openInherited(target)` provides a captured console-chain
fallback with `isInheritedTarget()`. Unlike an explicit target, this fallback
does not override a configured island ID/name/UUID or nested selector. Legacy
nickname mutations can inherit it when the subject belongs to the captured
island. Restore a captured scope on the owning executor; do not turn arbitrary
external commands into forced origin-island operations.

Put temporary selectors before free-form payloads. Nested command strings,
message/title text and `data set` values remain opaque after their payload starts;
use `--` to start a literal payload whose first word resembles a selector.
Ordinary required positional parameters are not removed by a temporary selector.
The precise routing, invitation, completion and approval contracts are in
[COMMANDS.md](COMMANDS.md).

A scope does not cross an executor or future boundary. Capture the island UUID
and relevant owner/operation state, then re-resolve and revalidate the target
inside the scheduled action before opening a new scope. Never retain only a
player nickname or call `getIsland()` later to rediscover a destructive target.
Confirmations and menus also need an expiry, one-shot consumption and current
permission checks. A selection or owner change must not redirect an already
displayed action to another island.

The fork's optional dangerous-command requests additionally capture a transient
membership revision. Per-island role changes and remove/rejoin cycles invalidate
old member operations even if the final role is equal to its original value.
Automatic-size approval records owner UUIDs rather than a nickname's single
island; rating-author approval records the author UUID and original operation
kind. These are command-intent fences, not a durable external-command replay or
transaction protocol.

For block/entity events, derive the target from the event's actual location.
For inventory actions, retain the intended island and acting player's identity.
Schedule player inventory, feedback and teleport work with
`SuperiorSkyblock#executePlayer`; schedule live blocks/chunks with
`executeRegion`. `executeGlobal` is for global metadata, not access to arbitrary
players or chunks. `executeAsync` is for detached computation/I/O. Do not block
an entity/region/global thread with `join`, `get`, sleeps or filesystem work.

## Transfer, deletion and completion

Prefer `Island#transferIslandAsync(targetPlayer)`. In the core, its future
completes only after the transfer intent, authoritative quota check, native
ownership/member writes and terminal journal acknowledgment. `false` represents
a transfer which did not apply; an exceptional result is not success. An
uncertain SQL outcome keeps the island/reservation fenced for recovery. Do not
compensate it by blindly transferring back, retrying on a new target, or deleting
its journal.

The old boolean `transferIsland` is retained only for the Paper/Leaf primary
thread, an available island whose owner owns at most one island, and a recipient
who owns none. It returns `false` with an actionable diagnostic for unsupported
Folia/multi-island use. The retained legacy path does not offer the async API's
durable completion barrier. A boolean must not be interpreted as acceptance of
an asynchronously scheduled operation.

Use `disbandIslandAsync()` when dependent work requires completed deletion. The
core future includes physical cleanup/storage acknowledgment and the database
outcome. The legacy void method cannot communicate that completion. Additive
default implementations on external custom `Island` objects preserve linkage;
they cannot upgrade that implementation's durability automatically.

## Creation algorithms and immutable paste receipts

`IslandCreationResult` retains its old three- and four-argument constructors.
The additive six-argument constructor carries a copied, unmodifiable list of
`AffectedChunk` values and a deferred teleport callback. Each value holds world
name, dimension and chunk X/Z. Obtain the footprint and callback **inside the
schematic completion callback**, before transient paste state is cleared or
reused, then complete the result future. The core consumes
`getAffectedChunkSnapshot()` and `getOnTeleportCallback()` from that result;
it must not reread a shared schematic's transient fields later.

The old constructors deliberately provide no footprint receipt. They do not
assert that delayed cleanup can recover the original paste footprint. Custom
creation algorithms are limited to legacy single-island Paper/Leaf behavior
until a durable adapter is implemented and qualified; Folia, ownership limits
above one and automatic sizing reject an unqualified custom algorithm. Direct
construction of an island is not a quota reservation, published creation or
world-write acknowledgment. Use the grid creation flow.

## Worlds-provider capability contracts

`WorldsProvider` exposes three default-false methods. Each is a metadata query:
it must not access live world state, perform I/O or invoke external callbacks.

| Opt-in method | The addon must implement and qualify |
| --- | --- |
| `supportsFoliaExecution()` | Owner-region/entity scheduling, bounded world work, startup-only supported world adoption and safe player retirement |
| `supportsMultipleOwnedIslands()` | Island-UUID storage/world identity, collision-free concurrent allocation, transfer-independent identity and durable cleanup |
| `supportsAutomaticIslandSizePolicy()` | Changing usable bounds inside a fixed reserved cell, preserving out-of-bounds blocks/entities and compatible growth/shrink behavior |

The default shared-world provider opts into all three. The core checks required
capabilities before startup world work, provider registration and creation
admission/allocation. A legacy provider remains usable on Paper/Leaf with
`LEGACY` sizing and ownership limit one. Merely returning `true` does not prove
the implementation satisfies a contract. Per-island-world/slime-world providers
are not shipped or qualified by this fork. Do not hot-swap providers while
island operations are pending; rehearse a stopped migration instead.

Custom database bridges are rejected in every mode by this release, including
LEGACY single-island operation; there is no opt-in contract for them. The
built-in SQL bridge is required. Custom world factories and chunk providers
still require their own compatible lifecycle/durability contracts. Do not treat a void save
method, queued operation or swallowed SQL exception as a commit acknowledgment.
The maintained core SQL writer and qualified native storage adapters establish
the fork's barriers; installing the fork does not wrap every external provider
into those guarantees.

## Mission providers and persistence

An island mission provider must override `supportsIslandSubjects()` only after
migrating its event targets and persistent progress to island UUID subjects.
Until then, the legacy single-island path remains available but another owned
island is refused when that provider is enabled. Use `subjectEntrySet()` and
`loadSubjectData(UUID, data)` for the corresponding persistence model. Island
subjects survive ownership transfer; do not move progress to the new owner's
UUID or merge it into the recipient's other islands.

Legacy owner-keyed progress can map automatically only to one identifiable owned
island. An ambiguous owner entry must not be copied to every island or assigned
arbitrarily. `GridManager#isKnownIslandUUID` includes retired ID tombstones, so
deleted-island progress is not mistaken for a new owner-keyed record.

Keep metadata counters thread-safe and serialize detached snapshots. Call
external events/rewards outside metadata monitors; inventory consumption must
recount and mutate the acting player's own inventory on its entity owner.
`isProgressAvailable` and `canPersistProgress` allow safe migration quarantine
without overwriting uncertain retained progress. See
[mission migration](MISSION-MIGRATION.md) for the built-in coordinate-format
quarantine and backup rules. Arbitrary reward commands/economy providers lack a
general exactly-once contract across process loss; recovery does not replay
their external effects.

The maintained island-mission reward path captures the mission's island before
crossing the entity scheduler and restores that context for configured console
commands. Delayed callbacks must still revalidate availability, generation and
membership; changing persistent selection cannot redirect the pending reward.

## Migration and rollback boundaries

Test the exact current plugin/world/database/configuration snapshot and addon
set before cutover. The fork does not assign ranks, permissions or user metadata.
Keep missing-only config/language backups, native membership/role/completion
rows, PVC IDs/tombstones/selection/journals, owner overrides, mission files and
the corresponding physical worlds together. A successful compile or support
declaration is not runtime qualification.

Once fork-specific multi-island data exists, upstream's single-island model is
not a supported reader of that state. Removing the fork JAR, clearing new tables
or restoring YAML alone is not a rollback. Normally stop the backend, then
restore the complete consistent pre-upgrade JAR/plugin/database/world/server
configuration snapshot, including the relevant permission/backend data. Restore
external SQL consistently with the world snapshot; do not attach an old database
to newer occupied cells. This restoration also discards gameplay since that
snapshot. Preserve failed-migration evidence and resolve incomplete operations
before attempting a different release.

Future upstream merges must retain these additive contracts or supply an
explicit migration. Follow [patch families](PATCHES.md) and
[upstream integration](UPSTREAM.md), then repeat the release's API, migration,
failure/recovery and native acceptance gates.
