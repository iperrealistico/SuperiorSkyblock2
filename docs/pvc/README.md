# SuperiorSkyblock2 — PVC maintenance fork

This GPL-3.0 source fork adds multiple owned islands, permission-based owner
limits and sizes, durable operation handling, and region-aware Folia execution.
Upstream package/API identity and original platform adapters are preserved.

Operators should read [installation and rollback](INSTALLATION.md). Maintainers
should also read [Folia architecture](FOLIA.md), [upstream integration](UPSTREAM.md), [patch families](PATCHES.md),
and [owner entitlement behavior](ENTITLEMENTS.md) before making source changes.
Addon authors should also read [API migration](API-MIGRATION.md), including
explicit island context, asynchronous completion and provider capability rules.
The completed [verification record](VERIFICATION.md) identifies the exact
`2026.3-pvc.1` artifact: 110 automated tests and 87 native phases passed on the
recorded Leaf/Folia 1.21.11 and 26.2 builds, including retained restarts. This
qualifies the documented shared-world scenarios; it does not authorize a
production change or establish a population/load benchmark. Later versions and
unqualified addons require separate maintained-source work and qualification.

An existing configuration without PVC settings receives `pvc.size-mode: LEGACY`
through missing-only migration; existing settings and configured PVC values are
retained. Enable `PERMISSION_AUTOMATIC` deliberately after choosing rank radii,
keeping `build-outside-island: false` and removing incompatible border-size
upgrades. Official playtime/donor rank radii remain an operator decision. The
fork does not assign LuckPerms permissions, change ranks or install itself on
any backend.

Default ownership quota is one. Additional quotas use
`superior.island.ownership.limit.<count>`; the highest effective numeric grant
wins. `superior.island.ownership.limit.unlimited` removes that quota. Radius
grants use `superior.island.size.radius.<radius>` and are not added together.
Policy nodes default to false; OP alone does not authorize extra ownership or
radius. Existing OP/wildcard access to ordinary commands is preserved. Automatic
sizes remain bounded by the world's existing reserved cell ceiling. LEGACY mode
retains upstream's separate `build-outside-island` behavior.

New player commands are `/is list [page]` (alias `/is islands`) and
`/is select <id|uuid|name>` (alias `/is use`), requiring `superior.island.list`
and `superior.island.select` respectively.
`--island <id|uuid|name>` selects the target of one command without changing the
persistent selection. Existing single-island usage remains valid. Stable short
IDs are never reused, including after deletion or an abandoned creation.

Sizing administration retains `/is admin setsize <owner> <radius>` and
`/is admin addsize <owner> <amount>`; in automatic mode these are persistent
owner-wide overrides. In automatic mode,
`/is admin clearsizeoverride <owner>` removes the owner override; any legacy
per-island exception still takes priority over permissions. The extra `legacy`
argument intentionally also removes those old exceptions.
`/is admin sizepolicy <owner>` explains the policy.

Folia requires island worlds and spawn to be loaded at server startup. Island
dimensions require the correct void generator/environment; a separate spawn
requires its qualified loading arrangement and safe platform. The fork does not create
worlds dynamically on Folia or qualify Cielo's other plugins/addons. Follow the
platform-specific prerequisites in the installation guide.

The test observer JAR and synthetic clients under `verification/` are excluded
from the plugin release. Their path, port and marker guards reject production
roots. Deployment requires a separate operator decision, normal stopped JAR
replacement and a complete consistent backup of configuration, plugin data,
SQL and island worlds. Once multi-island state exists, an upstream-JAR rollback
requires restoring the complete pre-upgrade snapshot; replacing only the JAR is
unsafe.
