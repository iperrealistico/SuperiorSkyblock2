# Installation and operation

This guide describes the PVC source fork. It does not authorize installation,
permission changes or a production restart. Use the exact JAR, compiled source
commit, hash and native build scope in the completed release
[verification record](VERIFICATION.md). This guide describes `2026.3-pvc.4`;
the completed qualification of `2026.3-pvc.1` remains historical evidence for
that exact older artifact and does not qualify a replacement. Rehearse your actual
backend/plugin stack before rollout. Later Minecraft/Folia versions need their
own maintained-source adapter and qualification before deployment.

## Upgrade the existing Leaf backend

Keep Cielo's present server/world/spawn configuration and local LuckPerms setup.
The fork does not import Main's ranks, assign any permission, create a donor
group, change another backend, or install additional plugins. Its initial
configuration migration adds only absent PVC keys and defaults to:

```yaml
pvc:
  size-mode: LEGACY
  force-default-language: true
```

Existing settings, comments, private values and newline style are retained.
`config.yml.pvc-backup` preserves the pre-migration config bytes; an already
complete config is not rewritten. Missing PVC runtime/cache settings receive
bounded defaults. Invalid existing PVC values or unsupported inline mappings
cause a reported failure rather than silently replacing the original file.
Missing new language entries are also added with backups, preserving existing
custom translations. The fork does not enable previously disabled modules.

`2026.3-pvc.4` defaults to the server's configured language for presentation.
Keep `default-language: en-US` for English; the migration preserves an existing
operator-selected default rather than overwriting it. With
`pvc.force-default-language: true`, client languages and stored preferences do
not change messages/help, first-join language detection is bypassed and the
language selector reports the server language. Raw player preferences and
upstream translations are retained. Set the policy to `false` to restore
per-player presentation and language selection; `auto-language-detection`
continues to control eligible first joins independently.

The bundled theme uses `&8` frames/separators, `&6` labels/commands, `&e` values
and `&7` explanations with the generic `[Skyblock]` prefix. Existing generated
locale/menu/module files are not overwritten with those defaults. To recolor
them, apply an explicit reviewed presentation-only update with per-file backups;
keep custom text, explicit empty messages, disabled features and functional
settings. Prepared theme files are not proof of application or activation.
See [English presentation and branding](BRANDING.md) for policy, examples and
the distinction between service replies and player-authored payloads.

Perform a planned upgrade in this order:

1. Rehearse on a private copy of the exact backend with the proposed qualified
   JAR, existing data, configuration and required plugins. Test ordinary island
   creation/home, spawn/respawn, permissions, selectors and a retained restart.
2. After separate operator authorization, stop only the intended backend
   normally and wait for completed world saves and process exit. Do not use
   PlugMan, hot replacement, `/reload` or a forced stop for installation.
3. Take a consistent full backup while stopped: the old JAR, complete
   `plugins/SuperiorSkyblock2/`, all island and spawn world folders, and relevant
   server configuration. Include the SQL database and its schema/table prefix;
   SQLite backups must include the consistent database/WAL state, and external
   SQL requires a consistent dump/snapshot. Retain local LuckPerms data/config
   and the other backend plugins/configuration needed to restore the snapshot.
4. Replace only the SuperiorSkyblock2 JAR, retaining its existing data folder.
   If a separate theme update is authorized, apply its reviewed presentation
   changes to the existing files while stopped, preserving their individual
   rollback backups. Remove the old duplicate JAR from the active plugins
   directory. Start the same backend normally with its existing supported
   Java/JVM configuration.
5. Check successful plugin enablement, database/migration/recovery completion,
   worlds and spawn, then test the same player flows and restart persistence.
   Check English presentation for a client/stored preference in another
   language, ordinary help and the service palette without changing that
   player's stored preference. Check custom messages and functional settings
   against their pre-update backups.
   Investigate any error before opening access. Retain the backup and exact
   release identity alongside the change record.

Current Leaf behavior remains `LEGACY`: existing default radius, compatible
border-size upgrades and per-island fixed sizes retain their upstream meaning.
Additional ownership quotas are available independently of automatic sizing;
owners without an effective quota grant can acquire at most one island.
Previously retained properties are preserved if a larger quota is later revoked.

## Opt in to automatic owner sizing

Choose the rank radii separately; this fork ships no official playtime or donor
rank values. While the backend is stopped, set
`pvc.size-mode: PERMISSION_AUTOMATIC`, retain `build-outside-island: false`, and
remove active border-size upgrade levels from the enabled upgrades module.
Other upgrade types can remain enabled. Startup rejects an incompatible
automatic-size configuration. Restart normally after changing the mode or
runtime/cache allocation settings. Full `/is admin reload` is rejected when
either the running or proposed mode is automatic.

Use explicit LuckPerms grants in Cielo's own permission database. These are
symbolic console examples: replace every bracketed value with the chosen group
or a positive decimal integer; do not paste the brackets literally.

```text
lp group <rank> permission set superior.island.ownership.limit.<COUNT> true
lp group <rank> permission set superior.island.size.radius.<RANK_RADIUS> true
lp group donor permission set superior.island.size.radius.<DONOR_RADIUS> true
```

The highest true numeric grant wins; grants never add together. Set the donor
radius above every rank radius if donor should always provide the largest
island. Removing donor restores the highest remaining grant, subject to any
administrative override or explicit legacy exception. A radius is in blocks
from the island center, not the full side length. Numeric suffixes use ordinary
positive decimals without signs or leading zeros. False/malformed nodes do not
grant advantages. Ownership defaults to one; an explicit
`superior.island.ownership.limit.unlimited` removes the numeric quota while
infrastructure limits still apply.

New entitlement namespaces default to false. OP status alone does not grant
extra ownership or radius. LuckPerms resolves inherited grants, negations,
contexts and explicit wildcards; a deliberately granted broad wildcard may
therefore grant unlimited ownership and the evaluated radius ceiling. Existing
OP/wildcard access to ordinary administrative commands is preserved. No
LuckPerms group, node or player assignment is made automatically by the fork.

Automatic policy belongs to the **owner**, applies to all present/future owned
islands, and ignores members' ranks. LuckPerms user/group invalidation plus
bounded periodic reconciliation repair missed events; creation and transfer
also require a fresh authoritative quota. Offline owners use the supported
LuckPerms provider's fixed backend/island-world context. Provider failure retains
last-known dimensions and denies unverified new advantages rather than treating
unavailable permissions as a revocation. See [policy details](ENTITLEMENTS.md).

## Physical grid and commands

`max-island-size` is the saved physical cell ceiling. Existing Cielo's value is
1000; ordinary initial radius remains its configured 100 in LEGACY mode with
`build-outside-island: false`. Retained LEGACY `build-outside-island: true`
uses upstream's rounded ceiling multiplied by 1.5; automatic mode rejects it.
Changing an automatic entitlement/override can grow an island only within its reserved
cell. It does not enlarge island spacing or generate terrain. The database
retains the grid ceiling, and changing the YAML alone does not expand an
existing grid. A larger physical ceiling needs a separately planned world/data
migration or a new grid; never delete the database while retaining occupied
world cells. Reduced radii preserve blocks, entities and islands outside the new
usable boundary; quota reduction preserves already owned islands and blocks
further acquisition until the owner is within the limit.

For one island, existing commands such as `/is home` remain argument-free.
Personal multiple-island presentation becomes available only with both a
verified ownership quota above one and several actual owned/team memberships.
A higher quota with only one membership keeps ordinary presentation while
permitting another creation when all normal checks succeed. Once unlocked,
`/is list` shows durable short IDs and team islands,
`/is select <id|uuid|name>` (alias `/is use`) sets persistent selection, and
`--island <id|uuid|name>` targets one command without changing that selection.
Use short IDs when names are absent or change. IDs are not reused after deletion.
Selection, ownership and each island's member role are separate state. Ordinary
commands still require their normal permissions; list/select require
`superior.island.list` and `superior.island.select`, whose plugin defaults are
true while their handlers/help/completion remain conditionally gated. Explicit
permission denies remain effective; the fork writes no LuckPerms permission
data. A quota/radius grant does not
grant create, home, membership management or any other command.

Reducing the quota hides the extra personal tools without deleting retained
islands or identifiers. Limited implicit targeting prefers an owned property,
then a still-valid selected owned property or the oldest retained owned ID;
with no property it falls back to the selected/oldest team membership. A sole
owned island has priority over other team memberships. Staff targeting remains
explicit where needed and independent of the staff member's personal quota.
Selectors must precede free-form text; nested command payloads retain their own
selectors, and `--` starts an escaped literal payload. Required upstream
positional arguments remain required. See [all command contracts](COMMANDS.md).

In automatic mode, the size precedence is: persistent owner override, explicit
legacy fixed-size exception on that island, highest owner radius grant, then
configured default. Every result is capped by the saved grid ceiling.

| Administration | Effect in automatic mode | Permission |
| --- | --- | --- |
| `/is admin setsize <owner> <radius>` | Durable override for all current/future owned islands | `superior.admin.setsize` |
| `/is admin addsize <owner> <amount>` | Durable additive owner override; uses current owner policy when no override exists | `superior.admin.addsize` |
| `/is admin clearsizeoverride <owner>` | Clear the owner override; retain old per-island fixed exceptions | `superior.admin.clearsizeoverride` |
| `/is admin clearsizeoverride <owner> legacy` | Also intentionally clear those fixed exceptions | `superior.admin.clearsizeoverride` |
| `/is admin sizepolicy <owner>` | Refresh/explain quota, radius, overrides, exceptions and source freshness | `superior.admin.sizepolicy` |

In LEGACY mode, existing `setsize`/`addsize` retain per-island upstream behavior.
Legacy `Island#setIslandSize` API calls remain per-island in either mode; they do
not silently change another island belonging to the same owner. Clearing a
legacy exception is deliberate administration, not a permission revoke.

The optional upstream `dangerous-commands` list remains empty by default.
Configured approvals freeze island/member generations or owner UUID intent,
including owners with zero or several properties; they do not require a
nickname to identify one arbitrary island for an owner-wide override. Role and
remove/rejoin changes invalidate pending member operations. Nickname
`removeratings` remains author-wide, while an explicit island clear remains an
island clear at approval. Consult [COMMANDS.md](COMMANDS.md) before configuring
approval rules or nested upgrade/reward commands.

## Additional Folia prerequisites

The supported topology is the default shared-world provider/creation algorithm:
all islands share the configured dimension worlds. Per-island-world/slime-world
addons and custom creation/mission providers need their own compatible
durable/Folia contracts; installing this fork does not qualify them. A custom
mission provider must explicitly support island UUID subjects before allowing
more than one owned island. See [mission migration](MISSION-MIGRATION.md).

This release rejects custom database bridges in every mode, including LEGACY
single-island operation. Only the built-in SQL bridge supplies the required
transaction/recovery contracts; there is no custom-database capability opt-in.

`WorldsProvider` now has three additive, default-false capability contracts:
`supportsFoliaExecution()`, `supportsMultipleOwnedIslands()` and
`supportsAutomaticIslandSizePolicy()`. The default shared-world provider enables
all three. Unqualified custom providers are rejected before Folia startup work,
before creation with an ownership limit above one, and before automatic size
policy use. A legacy addon can still run on Paper/Leaf with `LEGACY` size mode
and an ownership limit of one. Opting into a contract requires the addon author
to implement and test the corresponding world identity, durable cleanup,
reserved-cell and scheduler rules; returning `true` alone proves nothing.
Per-island-world/slime-world addons are not included or qualified by this release.
Do not replace a worlds provider while island operations are pending.

Folia must load every enabled configured dimension **at server startup** with
the correct environment and SuperiorSkyblock2 void generator. Runtime
`WorldCreator` creation/loading is unsupported. For a shared normal island
world named `SuperiorWorld`, the minimal stopped-server setup includes:

```properties
# server.properties
level-name=SuperiorWorld
```

```yaml
# bukkit.yml
worlds:
  SuperiorWorld:
    generator: SuperiorSkyblock2
```

Match SuperiorSkyblock2's configured default world name to that preloaded world.
For enabled Nether/End dimensions, preload the exact configured names at startup
with their corresponding environments and void generator entries; align server
dimension-enable settings and actual world folders. Do not enable a dimension
whose world is absent. When relying on normal server dimension startup, its
`<level-name>_nether` / `<level-name>_the_end` names must match the plugin config.
The provider refuses missing worlds, environment mismatches and a wrong
generator instead of creating worlds dynamically. Existing terrain is not
converted to void by changing a generator entry.

The configured spawn world/location must also already exist and contain a safe
spawn platform. The minimal Folia layout uses a common spawn in the preloaded
shared normal world; a separate spawn world requires a separately qualified
startup loading arrangement. Cielo's current separate Leaf spawn world is not
automatically converted or dynamically loaded on Folia. Preserve real world
folders/data and test first join, home, void/death respawn and EssentialsSpawn
against the proposed Folia layout before rollout. These Folia `level-name`
instructions are not an instruction to change the current Leaf backend: its
default provider loads the island world separately and rejects an already
preloaded island world.

Only this source fork's documented native lanes can qualify SuperiorSkyblock2.
The rest of Cielo's plugin stack, including installed Skript/RediSkript, Jets,
Essentials components and addons, needs independent Folia compatibility review;
the fork does not make those plugins Folia-safe. Keep the current Leaf Java and
startup flags until a separately planned, qualified platform migration.

## Data retention and rollback

Retain native database/world data plus the fork's prefixed PVC metadata,
ownership/membership/selection state, never-reused IDs, operation journals,
`pvc/owner-policy.properties` and its backup, and module data under `datastore/`.
Do not remove journals or reserved cells to bypass an incomplete operation;
restart recovery and diagnostics must establish its durable outcome first.
Never import a derived permission snapshot as a permanent fixed radius.

Before multi-island/PVC schema state exists, any rollback still requires checking
the exact preceding release/data compatibility. After multiple islands or
fork-specific ownership/mission state exists, replacing the JAR with upstream
is unsafe: upstream's one-island assumptions cannot interpret the state. The
supported rollback is a normal stop followed by restoration of the **complete
consistent pre-upgrade plugin/database/world/config backup** and old JAR. This
also rolls back subsequent gameplay, so plan the recovery window explicitly.
A config backup alone is not a database or world rollback.

For maintainers, preserve upstream provenance and patch boundaries described in
[upstream integration](UPSTREAM.md) and [patch families](PATCHES.md). Fetch/merge
future upstream changes on an integration branch, then repeat compatibility,
migration, failure/recovery and native qualification before publishing a new
release. An upstream merge or test pass does not authorize production changes.
Addon migration contracts and compatibility boundaries are documented in
[API migration](API-MIGRATION.md).
