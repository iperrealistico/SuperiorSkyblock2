# Populated unchanged-upstream upgrade fixture

`NativeUpstreamSeed` is a separate Java8 observer, built by `upstreamSeedJar` as
`build/libs/SuperiorSkyblockUpstreamSeed-2026.3-pvc.1.jar`. Its source set has only
the public Superior API and Paper Bukkit compile inputs; no fork implementation,
LuckPerms API, NMS, server manipulation or release classes are packaged. Every
Superior call is present in the unchanged baseline public API. Native execution
against the original JAR, rather than compilation alone, is the acceptance gate.

Use a new private Leaf fixture under `local-servers/superior-runtime-*`, bound
to 127.0.0.1 on a free port 29010 through 29029. Regular `.superior-owned` and
`.superior-upgrade-owned` markers are mandatory. The observer rejects Folia and
all other paths/platforms. Install the unchanged current original as
`plugins/SuperiorSkyblock2.jar`; the observer requires version `2026.3` and
SHA-256 `552a9ad27b2ccf0b6a8e124805220333c125e361a992a8e82deb62088ba831c1`.
Use Cielo's unchanged configuration and a fresh isolated LuckPerms instance.
Do not install the normal fork runtime observer during this original phase.

Connect the actual fixed client `PVCSkyProbe` and run:

1. `/pvcupstreamseed ready`: receive `SUPERIOR_UPSTREAM_SEED_READY`. Only this
   guarded fixture account temporarily receives Bukkit OP for ordinary upstream
   island commands; no LP API, rank, group or entitlement grant is made.
2. `/is create`: the actual client creates the island through the original
   plugin. The existing single-item-menu policy is respected; if a menu opens,
   the actual Classic button is in slot 4. The observer never creates an island
   or fabricates a creation event.
3. `/pvcupstreamseed status`: wait for count 1/radius 100/pending false and
   `postCreated=true` when creation teleports are enabled.
4. `/pvcupstreamseed record`: the observer loads the bounded real chunk volume,
   waits for creation effects to settle, records actual metadata/solid blocks,
   revokes the fixed account's temporary OP and writes the checkpoint. Both
   console and player receive `SUPERIOR_UPSTREAM_SEED_PASS` only after durable
   file creation. The console records the immutable checkpoint SHA.

The root runner normally stops the original server and preserves the populated
world, database and LP state. Copy its exact **stopped final** config bytes to
`staged-before-config.yml`. Swap only the private original plugin JAR for the
fork, remove the private seed observer JAR and install the normal fork observer.
The original checkpoint must remain untouched. Production is never involved.

On each fork phase, call `/pvcskyprobe legacy-ready` first, receiving
`SUPERIOR_LEGACY_READY` and ordinary fixed-account test permissions. Then run:

- Initial upgrade: `/pvcskyprobe legacy-upgrade-check` → console/player
  `SUPERIOR_UPGRADE_MIGRATION_PASS`.
- Retained fork restart: `/pvcskyprobe legacy-upgrade-restart-check` →
  console/player `SUPERIOR_UPGRADE_RESTART_PASS`.

The upgrade checks require LEGACY, the original owner/island UUID, effective and
raw radius, world UUID/name, center/home coordinates, sorted roster/per-island
role IDs/names and every captured solid/block-state sample to remain identical.
The fork must assign a positive short ID and singleton persistent selection;
the retained restart requires those new values to remain unchanged. Config
checks reuse exact original YAML preservation, byte-identical migration backup
and idempotent migrated-config hashes. Existing normal LEGACY probe actions and
markers are unaffected. To exercise home separately: `legacy-away`, actual
argument-free `/is home`, then `legacy-homecheck` → `SUPERIOR_LEGACY_HOME_PASS`.

## Immutable checkpoint format

`upstream-island-checkpoint.properties` is Java Properties format version 1.
It is created with `CREATE_NEW` and forced to disk; subsequent seed recording
refuses to overwrite it. Its companion
`upstream-island-checkpoint.properties.sha256` contains the lowercase digest of
the exact bytes. A partial/missing/mismatched file cannot pass the upgrade gate.

| Keys | Meaning |
| --- | --- |
| `format`, `source.plugin.version`, `source.jar.sha256`, `source.minecraft.version` | Exact original provenance and format |
| `owner.uuid`, `owner.name`, `island.uuid`, `island.radius`, `island.raw-size` | Actual singleton owner/island identity and sizing |
| `center.*`, `home.*` | World name/UUID, x/y/z and yaw/pitch |
| `members.count`, `member.N.uuid`, `member.N.role.id`, `member.N.role.name` | UUID-sorted actual roster and baseline per-player role |
| `blocks.count`, `block.N.x/y/z`, `block.N.material`, `block.N.data` | 16–512 real stable solid samples and native block-state strings |

The solid volume is limited to center ±8 horizontal blocks and ±12 vertical
blocks. Samples cover stable stone/dirt/grass/log/wood/chest/bedrock materials,
excluding natural leaf decay or mutable crop/ice states. This proves preservation
of actual populated island terrain, not every entity, inventory or changing
natural block. No block or inventory is added by the seed observer.

Initial fork upgrade creates `upstream-migration-state.properties` with
`CREATE_NEW`, recording the original checkpoint digest plus new short ID,
singleton selection, UUID/radius/solid count and source/backup/migrated config
hashes. The restart compares its values exactly and rechecks original physical
solids against the untouched upstream checkpoint.
