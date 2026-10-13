# Native multi-island command regression profile

This test-only profile uses the same guarded private listener and two actual
offline protocol clients as the existing runtime qualification. It never runs
against C:/MC and is excluded from the released plugin. No Bukkit game event is
fabricated. The clients execute ordinary commands and click actual rating GUI
buttons; the observer reads the resulting island, member, mission and upgrade
state.

## Harness integration

Route `command-*` primary-account actions to
`new NativeCommandProbe(observer, superior).execute(player, actionSuffix)`.
Before the primary-player guard, route the fixed console capture through
`commands.handleConsole(sender, args)`. This capture accepts only
`command-capture <PVCSkyProbe|PVCSkyProbeB> --island id:0`; it cannot execute an
arbitrary console command.

The `commands` profile needs `.superior-owned` and `.superior-commands-owned`,
fresh SQLite data, `max-island-size: 96`, default radius 32, team limit 4 and warp
limit 4. Clear only the private fixture's disabled-command list. Configure
`dangerous-commands: [demote, kick, setsize, addsize, removeratings, adddisbands, setgenerator, addgenerator]`, a zero upgrade cooldown, player kick-confirm disabled for the direct command case,
the existing zero teleport warmups, and `pvc.size-mode: PERMISSION_AUTOMATIC`.
Keep the official `island-rate.yml` layout and handler, replacing only its
legacy `SKULL_ITEM` material with native `PLAYER_HEAD` for modern fixtures: the client clicks its real five-star button at slot 7.

Copy the three adjacent test fixture YAML files to:

| Test source | Private plugin destination |
|---|---|
| `native-commands-upgrades.yml` | `modules/upgrades/config.yml` |
| `native-commands-missions.yml` | `modules/missions/config.yml` |
| `native-commands-items.yml` | `modules/missions/categories/pvc_native/pvc_command_items.yml` |

Run `verification/scripts/command-probe.mjs` with `SUPERIOR_PROFILE=commands`
and phases `initial` and `restart`. Expected server markers are
`SUPERIOR_COMMAND_PROBE_PASS` and `SUPERIOR_COMMAND_RESTART_PASS`; the client
marker is `SUPERIOR_COMMAND_CLIENT_PASS`. The root retains the source/JAR hashes,
actual wrapper/JDK versions and private phase results.

## Assertions

- Actual help text and received TAB packets hide list/select/temporary selectors
  for quota 1, including preserved old islands after revocation, and for quota 3
  while only one island exists. The tools appear after a second real island is
  created. IDs survive revocation. The driver also records real command-tree
  packets; Bukkit's opaque greedy argument tree is not represented as fabricated
  literal subcommand nodes. A hidden selector's actual protocol request may be
  rejected without a TAB reply. Only for this negative case, the driver accepts
  the specific five-second tab-event timeout as no exposed ID response, records
  it explicitly, and then requires another real ordinary completion containing
  `help` to prove the listener is still responsive. Ordinary and positive
  selector completions always require actual reply packets.
- An actual external client reaches a public island warp.
- Actual rating GUI clicks populate both islands. Nickname author removal clears
  both after real console approval; an explicit island clear, also approved by
  the console, leaves the other island's rating intact. These assertions run
  before the auxiliary joins either island. Joining subsequently removes that
  member's vote under the original island membership semantics; the final
  checkpoint therefore requires both rating counts to be zero, and the empty
  rating state must survive the normal restart.
- The member's localchat event and toggle retain that member's UUID, while the
  owner's global chat state remains unchanged. Only after the member joins both
  real islands does its fixed `auxlimit3` action grant quota 3; its explicit
  selector is then available under the same presentation rule as the owner's.
- Player and admin rankup commands execute the unchanged `%player%`-based
  configured command chain against their actual target. A real Items mission
  consumes the acting client's inventory and its scheduled reward increments
  only the completed island's upgrade.
- Dangerous demote and kick approvals retain their targets across later
  selection changes. Actual intervening role changes and removed/rejoined
  memberships invalidate old approvals. Owner-wide setsize/addsize approvals
  use only the owner's nickname and update both islands consistently. Before
  any island exists, an approved nickname setsize persists an owner override
  that applies to subsequently created islands. Dangerous `adddisbands * 3`
  freezes player UUIDs and increments both real accounts exactly once despite
  their multiple memberships.
- Msgall and cmdall preserve a literal `--island id:0` inside their payloads. The
  observer captures the real console dispatch for both island members.
- After a normal restart, island UUIDs, IDs, selected target, member roles,
  ratings, upgrade levels, mission completion and persistent owner size override
  plus player disband counters match the recorded native checkpoint.

The fixture's upgrade price is zero. Real economy settlement, arbitrary
third-party hooks, network authentication and load capacity are not qualified by
this profile. The production rankup helper separately tests deferred charging,
business-level acknowledgement, cancellation, external-only ordering and honest
partial failures. This profile does not invoke purge, so it does not delete its
native evidence or claim native purge coverage.

## PVC3 extension

The maintained runtime driver also enables Generators in this command fixture,
seeds a nonzero COBBLESTONE weight of 195 and validates exact STONE percentage
approvals (25%, then 35%). Permission grants come from actual registered command
nodes; production permission files are never read or changed by this profile.
Modern inventory confirmation templates, valid retained global-warps materials
and public visitor warps are fixture prerequisites only.

The extension exercises public counts/values GUI and team reports on a foreign
island, literal Discord/warp names, captured and explicitly targeted cmdall,
configured menu hooks, stale hook rejection, membership-epoch invalidation,
current ban hierarchy, cancelled console expulsion and setrate event authors.
Global/personal menu behavior is checked independently of incidental selection.

`NativeSharedStateProbe` performs a fixed number of block/entity metadata
receipts through two real actor schedulers, validates actor/clone refund
attribution and waits for bank quiescence and physical SQL acknowledgements.
A newly created uncached history reader must observe persisted balance and
unique increasing log positions. Locations/counters are restored. This fixed
functional observer does not exercise a real Vault payout, a full native
breeding sequence or production load capacity. Focused source tests cover the
additional reservation, recalculation and uncertain-settlement contracts.
