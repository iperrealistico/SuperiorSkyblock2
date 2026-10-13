# SuperiorSkyblock2 — PVC maintenance fork

This GPL-3.0 maintained source fork preserves upstream identity and adds
multiple owned islands, highest-grant owner quotas/radii, durable operations
and region-aware Folia execution. The exact `2026.3-pvc.3` artifact passed
242 automated tests, 95 native phase runs and
two identical full clean main-JAR builds on the fixed Leaf/Folia 1.21.11 and
26.2 builds in [VERIFICATION.md](VERIFICATION.md). The previous release's
sanitized qualification ledger remains unchanged as historical evidence.
These are isolated declared scenarios, not a population/TPS benchmark or
authorization to install in production.

Operators should read [installation/rollback](INSTALLATION.md),
[all command contracts](COMMANDS.md) and [owner policy](ENTITLEMENTS.md).
Maintainers should read [Folia architecture](FOLIA.md),
[upstream integration](UPSTREAM.md), [patch families](PATCHES.md) and
[API/addon migration](API-MIGRATION.md). The command and shared-state follow-up
is summarized in [PVC3 corrections](CORRECTIONS-PVC3.md).

An existing configuration receives only missing PVC/language keys and retains
its existing values. Missing size mode defaults to `LEGACY`; automatic sizing
requires a deliberate `PERMISSION_AUTOMATIC` opt-in, outside-building disabled
and incompatible border-size upgrades removed. Rank and donor radii remain an
operator decision. Disabled gameplay modules stay disabled. The fork does not
assign LuckPerms ranks/nodes, import permissions or install itself on a backend.

Ownership defaults to one. `superior.island.ownership.limit.N` resolves to the
highest true numeric grant; the distinct `unlimited` node removes that numeric
quota. `superior.island.size.radius.N` uses the highest radius without addition.
Policy namespaces default to false; OP alone does not grant extra property or
radius. Automatic dimensions remain inside the saved reserved-cell ceiling.

Ordinary single-island syntax stays intact. Personal list/select, temporary
selectors and ID presentation require **verified quota above one and more than
one actual owned/team membership**. A larger quota with only one membership
keeps ordinary presentation while permitting another creation when all checks
pass. List/select nodes default to true but remain gated and respect explicit
denies. Quota revocation hides tools without deleting islands/UUIDs/short IDs;
limited commands prefer the selected owned or oldest owned property. A sole
owned island takes priority over other team memberships.

Permitted administrative/member selectors remain precise. Free-form payloads
preserve nested selectors and support `--` escaping. `CapturedCommandDispatch`
passes configured Superior hooks through the same registered command handlers
with captured fallback context, recognizes actual aliases and own namespace,
and respects explicit configured destinations. A recognized upgrade mutation
must reach its actual expected level before cost/success. Deferred mission
rewards retain their completed island on the entity owner. Optional approvals
freeze owner/author/player UUID intent and island membership epochs; role or
remove/rejoin changes invalidate stale requests.

In automatic mode, `setsize`/`addsize` write durable overrides for the owner's
current and future islands; `clearsizeoverride` removes that owner override,
with an optional deliberate `legacy` argument for old fixed exceptions.
`sizepolicy` explains the effective policy. The old per-island sizing API retains
its island-specific behavior.

Folia requires island dimensions and spawn to be loaded at startup with their
qualified generator/environment/loading arrangements. Unknown future native
versions, per-island-world providers and other Cielo plugins/addons require
their own maintained-source compatibility and qualification. Custom database
bridges remain rejected in every mode.

Probe/seed helpers, fault injection and private fixtures are excluded from the
main plugin. A rollout requires a separately authorized normal stop, consistent
full plugin/SQL/world/configuration backup and JAR replacement. After multi-island
state exists, upstream rollback requires the complete consistent preceding
snapshot; JAR-only rollback is unsafe.
