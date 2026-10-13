# Maintained patch families

Keep behavioral changes in these families when integrating future upstream
updates. Record exact release commits in the verification record once qualified.

| Family | Purpose | Principal source surfaces | Required regressions |
| --- | --- | --- | --- |
| PVC-EXECUTION | Entity, region, global metadata and asynchronous execution; startup world adoption | `core/threads`, `Hooks/Folia`, worlds, listeners, tasks, NMS templates and Folia adapters | Folia 1.21.11/26.2 ownership, split/merge, player retirement, Paper/Leaf behavior, Java 8 lazy linkage |
| PVC-ISLANDS | Multiple owned islands, per-island roles, stable IDs, selected context, quota reservations | API, player/island/grid/container, commands, menus, missions, `pvc/island` | Default quota 1, highest grant, concurrent create/transfer, command selectors, mission isolation, restart |
| PVC-DURABILITY | Real SQL commit barriers and operation recovery | `core/database/sql`, database bridges, creation/reset/delete/transfer journals | SQL failure/rollback, admission saturation, crash boundaries, no cell overlap, never reuse IDs |
| PVC-ENTITLEMENTS | Owner permission radius, persistent administrative overrides and bounded reconciliation | `pvc/entitlement`, LuckPerms hook, sizing commands, geometry | Donor maximum without sums, grant/revoke/offline/restart, unknown provider, bounded memory, all current/future islands |
| PVC-CONFIG | Add missing keys while preserving current operator configuration | `pvc/PvcConfig`, atomic files, settings, bundled config, reload preflight | Exact existing values/comments retained, idempotence, invalid input retained, current Cielo fixture |
| PVC-QUALIFICATION | Portable probes, provenance and release checks | `verification`, build files, `docs/pvc` | Same JAR on all mandatory native lanes, two clean identical builds, no production writes |

Do not solve merge conflicts by wholesale replacing upstream classes. Retain
small adapter boundaries and additive API contracts. Third-party mission
providers must explicitly support island subjects before allowing additional
owned islands; an owner UUID is not a unique island identifier in this fork.

## Generic fixes and PVC policy

Generic correctness/platform work includes entity/region ownership, immutable
paste receipts, counter/bitset concurrency, lossless signed-coordinate encoding,
real SQL failure/commit handling and physical storage acknowledgment. These
fixes address failures independently of the operator's preferred gameplay.
Upstream may supersede them once its implementation meets the same contract.

PVC policy includes multiple ownership, separate per-island membership roles,
persistent command selection, numeric highest-grant quotas/radii, donor maximum
without addition, owner-wide administrative overrides and conservative provider
capability admission. Keep these choices separate in review and release notes.
An upstream generic fix is not a reason to remove a PVC policy or its retained
data. Mixed classes contain both categories: resolve conflicts by behavior,
not by discarding the entire upstream or fork version of a file.

## Retirement conditions

The generic cleanup pipeline belongs to PVC-EXECUTION/PVC-DURABILITY. Large
existing End footprints exposed the cumulative latency of three serial
owner-callback passes. Independent reset events, native clearing and modern
storage now share a lazy bounded round-robin window, with physical storage
acknowledgements occupying their slots. Retire it only when upstream provides
equivalent global bounds, fairness, failure draining, epoch protection and
populated native reset acceptance; increasing a timeout is not a replacement.

The execution family also retains Folia-specific async player arrival handling.
The exact official source bundles omit the normal Bukkit teleport/changed-world
events on their async path; destination entity tracking provides the real
arrival event instead. Plugin teleports use normal protection preflight and
owner-following physical completion; external arrivals use the tracking event.
Do not retire these adapters merely because teleport futures return success:
the replacement must preserve cancellation/protection before plugin-owned
teleports, physical membership and snapshot reconciliation, generation fences,
retirement failures and automatic regional End activation. See
[FOLIA.md](FOLIA.md#native-asynchronous-player-arrival).

Retirement means removing a now-redundant patch after source comparison and the
family's regression gates pass on the resulting artifact. It never means
deleting retained data, silently removing an additive API or assuming a newer
upstream version covers the behavior. Record the upstream replacement commit,
removed fork changes and any data/API migration in the integration record.

| Family | Rationale for retention | Retire or narrow only when |
| --- | --- | --- |
| PVC-EXECUTION | Upstream callbacks and world work need correct owning schedulers, bounded load and retirement/shutdown fences | Upstream provides equivalent ownership and lifecycle guarantees on all retained platforms; remove duplicate generic adapters only after both mandatory Folia versions and Paper/Leaf pass, preserving additive execution API compatibility |
| PVC-ISLANDS | PVC permits several owned/team islands and independent roles, selection and mission subjects | Upstream supplies the same ownership/context semantics and a tested migration for native/PVC state and stable IDs; generic counter/receipt/coordinate fixes may retire independently when exact upstream replacements preserve progress and cleanup |
| PVC-DURABILITY | Queued writes and success-shaped SQL failures cannot acknowledge durable world/database operations | The replacement supplies real commit/failure outcomes, ordered physical storage acknowledgments, collision-free reservations and idempotent recovery for every retained operation; migrate or finish existing journals before retiring their reader |
| PVC-ENTITLEMENTS | Operator policy requires owner maximum grants, override precedence, conservative unknown-provider behavior and bounded reconciliation | A replacement implements the same current/future-island and offline/revoke behavior, and explicitly migrates persisted overrides/legacy exceptions without freezing derived permission snapshots into sizes |
| PVC-CONFIG | Existing operator values, comments, private entries and backups must survive missing-key upgrades | The replacement preserves the same idempotent, validated, backup-producing migration and understands all retained PVC keys; keep readers/migrations while old supported data formats remain in use |
| PVC-QUALIFICATION | Compile success and support declarations cannot establish runtime compatibility or artifact provenance | Equivalent reproducible builds and exact native migration/failure/recovery gates replace individual helpers; retain portable release identities, prior evidence and acceptance requirements even when scripts are superseded |

Do not retire a declared capability check simply because an addon opts in. Its
qualification remains the addon's responsibility. Any incompatible API removal
requires a separately versioned migration and explicit dependent-addon review.
