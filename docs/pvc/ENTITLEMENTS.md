# Owner entitlements and size overrides

The fork resolves island ownership and radius from the owner's effective
permissions, not from rank names, OP state, a Jets command, or a member's rank.
This policy and the conditional command presentation below describe
`2026.3-pvc.3`; completed qualification is recorded separately in
[VERIFICATION.md](VERIFICATION.md).

## Nodes

- `superior.island.ownership.limit.N`: the highest true positive integer grant
  sets the ownership limit. Missing grants mean one owned island.
- `superior.island.ownership.limit.unlimited`: an independently resolved grant
  removes the numeric quota. Infrastructure and admission limits still apply.
- `superior.island.size.radius.N`: the highest true positive integer grant sets
  the automatic radius. Values are radii in blocks, not edge lengths.

Numbers use canonical decimal notation without signs or leading zeroes. False
nodes and malformed/overflowing suffixes do not grant advantages. LuckPerms
resolves inheritance, negations and wildcards. Numeric wildcard evaluation is
finite: effective numeric nodes, configured radius candidates and the saved
grid ceiling. It does not enumerate an unbounded numeric range. A broad
ownership wildcard can grant the distinct `unlimited` node unless denied.

Assign the donor a larger radius grant than every playtime rank. Grants never
sum. Removing donor restores the highest remaining grant, unless a documented
administrative override or legacy per-island fixed-size exception takes priority.

## Conditional command presentation

Personal list/select tools, ID suggestions and temporary island selectors require
both a verified ownership quota above one and more than one current published
owned/team membership. A larger quota with only one membership still presents
ordinary commands; it permits another `/is create` only when the ordinary create
permission, quota reservation and other creation checks succeed. Team membership
is an available command target but does not consume an ownership slot.

`superior.island.list` and `superior.island.select` have plugin `default: true`
and remain subject to that visibility/handler gate. Explicit permission denies
remain effective. This metadata change writes no LuckPerms rank, user, node or
assignment. The ownership/radius namespaces still default to false; personal
tools do not grant other ordinary or administrative commands. Permitted staff
commands can address islands regardless of the staff member's own quota.

Revoking the larger quota hides personal multiple-island tools without deleting
islands, worlds, UUIDs, short IDs or memberships. The implicit limited target
prefers the sole owned island; with retained multiple properties it uses a valid
selected owned island, otherwise the oldest owned short ID. With no property,
it uses a valid selected team membership, then the oldest team membership.
Admission still refuses further ownership while the user is above the current
quota. See [command routing](COMMANDS.md).

## Modes and commands

`pvc.size-mode: LEGACY` is the migration default. Existing upstream default,
upgrade and per-island API sizing remains unchanged.

`PERMISSION_AUTOMATIC` enables owner policy sizing. It requires
`build-outside-island: false` and no active border-size upgrade levels; other
upgrade types are unaffected. The size policy order is:

1. Persistent owner-wide administrative override.
2. Explicit legacy fixed-size exception belonging to the individual island.
3. Highest effective owner radius grant.
4. Existing configured default radius.

All results are bounded by the saved grid's `max-island-size`. New grants do not
alter island spacing, regenerate terrain, or make changes to the grid safe.

In automatic mode, `/is admin setsize OWNER RADIUS` writes a durable owner-wide
override for every current and future owned island. An owner nickname means
that UUID's properties, never the unrelated island of which they are a member.
Island names resolve to their actual owner. `*` deduplicates owners and admits
one durable write at a time.

When an automatic-size command is included in the optional `dangerous-commands`
list, approval freezes owner UUID intent instead of resolving one island by
nickname. A known owner with zero or several current properties can therefore
receive the same durable current/future-island override. A wildcard request
captures its addressed owner set. Rename or incidental membership must not
redirect the approved policy to another owner. The list is empty by default;
the fork does not impose approvals unless the operator configures them.

`/is admin addsize OWNER AMOUNT` uses the owner's current policy radius as its
base, not an arbitrary selected island's legacy exception. Without an owner
override the permission source must resolve authoritatively before addition.

`/is admin clearsizeoverride OWNER` removes only the owner-wide override.
Existing legacy per-island exceptions remain visible in diagnostics.
`/is admin clearsizeoverride OWNER legacy` intentionally also clears those
fixed-size exceptions without changing other upgrade settings.

`/is admin sizepolicy OWNER` refreshes and displays the quota, radius, owner
override, number of legacy exceptions, saved grid cap, source freshness and
revision. New administrative nodes are `superior.admin.clearsizeoverride` and
`superior.admin.sizepolicy`.

The legacy `Island#setIslandSize(int)` remains island-scoped. It does not
silently modify the owner's other islands. An explicit owner override has
priority even over such an API exception.

## Convergence and failure behavior

Hot reads use an immutable snapshot and lock-free bounded owner cache. Events
invalidate owner policy, but periodic refresh and mandatory admission queries
also repair missed events. The LuckPerms provider uses static backend context
plus the configured island world's stable context, including offline owners.
The Bukkit fallback is online-only and excludes default/OP attachment grants;
it does not pretend to supply authoritative offline permissions.

Queries, caller deadlines, provider slots, refresh batches, geometry work and
storage admission are bounded. Timed-out provider work retains its physical
slot until completion, preventing unlimited abandoned queries. Errors back
off. An unavailable source retains last-known dimensions and cannot authorize
new ownership or unverified advantages. A missing source is not a revocation.

Geometry updates read the latest policy and process bounded island batches.
They update metadata and schedule each viewer's feedback through the owning
entity scheduler. Invalidation revisions prevent obsolete permission results
from replacing newer state. Downgrades do not delete terrain, entities, items,
or islands. Full reserved island cells remain protected.

## Persistence and config migration

`pvc/owner-policy.properties` belongs to this fork and is independent of native
fixed-size fields. `override.UUID` records are administrative state;
`snapshot.UUID` records are explicitly non-authoritative last-known cache.
Restart and cache eviction require a fresh query for admission. Permission
radius is never written into native fixed size, so donor revocation remains
possible. Native synced size `-2` is not an override.

Override changes are published only after an atomic durable replacement.
Additive overrides read, modify and commit on the serialized storage lane, so
concurrent additions retain every increment. An addition with no current
override requires a fresh authoritative permission radius. Explicit legacy
exception clearing awaits each native SQL acknowledgement before reporting
success; a partial failure reports the remaining retry instead of claiming
that all islands were committed.
`.bak` retains the immediately preceding policy file. Trimming bounded
derived history never discards a real owner override. Malformed policy files
fail closed rather than resetting rights. Storage is limited to 32 MiB and
rejects oversized writes rather than silently losing records.

New `pvc` settings are inserted only when missing. Existing values, comments,
private settings and newline style remain. The original file is copied to
`config.yml.pvc-backup` before atomic replacement. Complete configs are not
rewritten. Invalid existing values or unsupported inline parent mappings are
reported without overwriting the original. Size-mode changes, queue/history
capacities and runtime chunk/dragon arena allocations require a normal restart.
The full plugin reload is rejected while either the current or proposed size
mode is automatic, because upstream module reload is not transactional; other
validated policy settings can reload only while both modes remain legacy.

The pure resolver, cache, storage and config-migration tests do not prove native
Folia gameplay, actual LuckPerms integration or crash recovery by themselves;
those require the fork's separate native qualification matrix.
