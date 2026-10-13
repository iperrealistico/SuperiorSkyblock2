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
`dangerous-commands: [demote, kick, setsize, addsize, removeratings, adddisbands]`, a zero upgrade cooldown,
the existing zero teleport warmups, and `pvc.size-mode: PERMISSION_AUTOMATIC`.
Copy the official `island-rate.yml` menu to the private fixture without editing
it: the client clicks its real five-star button at slot 7.

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
  the console, leaves the other island's rating intact.
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
