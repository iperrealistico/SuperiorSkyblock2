# PVC3 command and shared-state corrections

This maintained-source release follows the PVC2 command and Folia shared-state
reviews. It retains the original command families, aliases and permissions,
ordinary single-island presentation, missing-only configuration migration,
Java 8 core/API and existing platform adapters. It does not enable disabled
modules or write LuckPerms assignments. Exact artifact qualification is recorded
separately in [VERIFICATION.md](VERIFICATION.md).

## Command intent and menus

Public island reports can explicitly address an external island. Free-form
Discord links, warp names and nested commands keep their payload boundaries.
Captured own commands in island/member chains and configured menus use the same
registered command handlers with the originating island as an inherited
fallback; explicit destinations still win. A stale menu is checked before its
configured click hooks run. Membership revision, current role hierarchy,
canonical identity, owner and operation epoch invalidate delayed island actions.

Console expulsion/team commands, wildcard generator approvals and rating event
authors preserve their real subjects and arguments. Personal/global menus do
not acquire an unrelated island dependency. A global island listing can use an
island UUID internally without displaying multi-island syntax to ordinary users.
Third-party commands retain their own contracts; this fork cannot grant them
region ownership or define whether their reported success means an actual
economic mutation.

## Concurrent shared metadata

Key maps publish their lazy backend safely and use atomic compute/merge paths.
Block counters, worth and level changes share a short per-island mutation
barrier. Chunk recalculation reconciles live changes by source and version;
entity reconciliation also preserves UUID identity rather than adding an entity
both from a scan and a live spawn. Detached preparation has bounded retries;
continuous changes preserve the existing live result instead of publishing a
partial replacement. An unknown legacy source aborts safely rather than losing
changes. World access remains on its actual owning region.

Dirty SQL metadata drains atomically per island. Aggregate totals publish as one
snapshot. Sorting owns one admission per type, preserves changes arriving during
the sort and reconciles the current island list before publication. Mission
snapshots copy their chunk-local state under short locks and assemble results
outside those locks. Shared date/number formatter prototypes are cloned for
each call.

Transient gameplay attribution uses bounded, expiring caches and detached world
coordinates or entity UUIDs. Inventory refunds and feedback follow the actual
player's entity scheduler; breeding items retain their individual feeding
actors. Message delay admission is atomic between regions. Administrative
notifications use the entity scheduler rather than Bukkit's legacy scheduler.
The built-in entity tracker also reserves event slots by UUID under its counter
barrier, preventing two regions from claiming the final configured slot. A
cancelled/filtered receipt releases its reservation; duplicate tracking receipts
do not count the same live entity twice. Custom trackers retain their declared
legacy limit-query path and require separate concurrent qualification.

## Bounded work and lifecycle

Unchanged effective radii avoid redundant border/crop work. All geometry setters
publish their effective radius under the same local barrier, so removing an
administrative override restores the correct bounds. Crop refreshes share one
loaded-coordinate snapshot per world in a small batch and retain admission
until every physical callback completes. Shutdown rejects late admissions and
retires callbacks from the preceding generation.

Entity recalculation chains asynchronous completion without blocking workers on
region futures. Block normalization and price preparation run on detached data;
the final global publication is short. Existing chunk budgets, native owner
checks, single SQL writer and durable world/SQL acknowledgements remain in place.

## Bank settlement and external integrations

Balance admission and log positions are atomic per bank. Provider calls, events,
command hooks and SQL acknowledgements run outside the short ledger lock.
Balance/log writes preserve ordering through the existing SQL writer. Uncached
history preloads asynchronously rather than querying JDBC on a tick thread.

Configured withdrawals have an additive asynchronous API; built-in GUI buttons
use it and wait for the configured hook acknowledgement before settlement.
Configured hook `{1}` denotes the reserved withdrawal amount. On Folia the old
synchronous configured-hook method rejects an operation it cannot safely
complete synchronously. External economy providers and hooks must satisfy their
own ownership and success contracts. An exception after a possible external
payout pauses the bank: the fork does not guess whether it should retry or issue
a second payment. Review the API migration guide before updating bank addons.

Bank deletion first closes admission and waits asynchronously for in-flight
settlements and physical SQL completion. Uncertain settlement blocks deletion;
it must not be refunded again or silently converted into a recoverable world
deletion intent. No lock is held while waiting for a provider or storage.

Finite source tests and two-client native checks demonstrate their recorded
scenarios. They are not a throughput benchmark or qualification of every
external provider, addon or future Minecraft ABI. See the release ledger for the
exact exercised paths and remaining integration limits.

## Review closure and focused evidence

The review identifiers below name distinct contracts. A matrix PASS qualifies
only its declared observer cases; source-only checks and unit interleavings are
identified separately. All original 149 command classes, aliases and permission
nodes are retained against upstream `868bebf` by a source inventory comparison.

| Review | Maintained correction | Focused evidence |
| --- | --- | --- |
| R01 | Public `counts`, `values`, `team` selection without weakening member-only commands | `PublicIslandSelectorsTest`, `PublicReportingIslandTest`; native foreign-island GUIs/report |
| R02 | Discord and warp payload boundaries | `IslandSelectorParserTest`; native literal links/warp names |
| R03 | Captured `executeCommand` / `cmdall`, explicit target precedence | Native same-handler bonus mutations and explicit foreign destination; captured-dispatch unit tests |
| R04 | Own hooks in menu, bank and placeholder paths | Native configured menu mutation; configured-upgrade tests; async bank completion contract |
| R05 | Stale-menu validation before hooks, paged super guard and post-hook revalidation | `MenuClickValidationTest`, `IslandActionGuardTest`; native stale hook rejection |
| R06 | Ordinary confirmations include membership epoch | `IslandActionGuardTest`; native remove/rejoin invalidation |
| R07 | Ban confirmation rechecks current hierarchy | Guard unit tests; native current-role rejection |
| R08 | Console expulsion honors explicit destination | Native mismatched/matching target with cancelled observer teleport |
| R09 | Console team report avoids a Player cast | Native console team report; `PublicReportingIslandTest` |
| R10 | Wildcard generator percentage argument isolation | Native exact 25%/35% on three islands with nonzero baseline weights |
| R11 | Rating event uses actual addressed author | Native event observer compares auxiliary UUID |
| R12 | Personal/global menus unbound; mixed missions retain dependency | `MissionMenuContextTest`; native language/global list and visitor destinations |
| S1 | Atomic KeyMap/lazy publication, block price/count barrier, versioned chunk/entity reconciliation and entity retirement after limit changes | `ConcurrentKeyMapTest`, `ChunkCountReconciliationTest`, `EntityCountReconciliationTest`, `EntityLimitChangeRetirementTest`; fixed native metadata receipts |
| S2 | Bank reservations, ordered SQL ACK/log cursor, async hooks and deletion quiescence/refund holds | `BankBalanceLedgerTest`, `BankQuiescenceTest`, `DisbandRefundPolicyTest`, `CacheBankLogsConcurrencyTest`, real SQLite `DatabaseBankLogsSqlContractTest`; native uncached SQL cursor/history readback |
| S2 formatting | Independent cloned mutable formatters | `FormatterConcurrencyTest` |
| S3 | Atomic dirty-marker drain | `IslandDirtySavesConcurrencyTest` |
| S4 | Bounded expiring gameplay/message attribution and UUID entity-slot admission | `WorldBlockKeyTest`, `MessageDelayCacheTest`, `EntitySlotReservationsTest`; native actor/clone receipt and fixed entity metadata checks |
| S5 | Atomic aggregate snapshot, one sort admission and current-list reconciliation | `IslandListReconcilerTest`; aggregate/admission source-flow review |
| S6 | Two remaining administrative notifications follow entity owner | Maintained `AdminPlayersListener` source-flow review; adapter lifecycle gates |
| O1 | Skip unchanged radius while keeping geometry marker/setters consistent | Native core/End legacy-clear bounds regression; entitlement policy tests |
| O2 | One coordinate snapshot per world/batch; retain callback admission through actual completion and shutdown | `CropRefreshCompletionTest`; maintained batch/lifecycle source-flow review |
| O3 | Entity recalculation chains futures without joining region work | Entity reconciliation tests; asynchronous calculation source-flow review |
| O4 | Detached block normalization/prices, short version-checked publication and bounded retries | Chunk reconciliation tests and source-flow review |
| O5 | Short chunk-local mission snapshot locks | `TrackerSnapshotConcurrencyTest`; native mission profile |

Uncached bank history avoids tick-thread JDBC and whole-history copies on each
write. It still retains detached history plus pending local entries until SQL
readback; this is not a promise of unchanged memory use. Refund intent states
`DELETE_REFUND_PENDING` and `DELETE_REFUND_HOLD` require operator reconciliation
and never trigger an automatic duplicate payout or world deletion. These fixes
need no additional configuration keys or SQL columns.
