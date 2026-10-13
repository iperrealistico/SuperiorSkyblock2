# Advanced native observer

This source is a guarded fixture helper and is excluded from the release. The
root `SuperiorProbe` command adapter instantiates
`SuperiorAdvancedProbe(this, superior)` and passes the action suffix for
`/pvcskyprobe advanced-<action>`. Commands are accepted only from `PVCSkyProbe`;
the second End arena requires the real authenticated fixture client
`PVCSkyProbeB`. Both clients connect normally to the owned private fixture. No
join, movement, damage, crystal or region lifecycle events are fabricated.

Prepare a Folia profile with the configured End dimension enabled and dragon
fights enabled, the actual `<island schematic>_<end dimension>` schematic (or
`classic_the_end`), and two persisted islands at least 1024 blocks apart in the
same End world. End dimension names are discovered from the environment.
Clear the test account's administrative owner radius override first. Test
permissions and OP state belong only to the fixed account in this fixture.

Before clearing an existing basic-probe override of 120, the optional
`addsizepair` action dispatches two real fixed console commands with increments
4 and 6. It requires the durable owner override and every owned island to reach
130, then emits `SUPERIOR_ADV_ADDSIZE_PAIR_PASS`. The driver restores 120 before
the basic probe's persistence/restart comparison.

Run the actions in this order, waiting for each matching observer signal:

1. `opbaseline`: remove explicit fixture entitlement/parent wildcard grants,
   enable OP and verify authoritative quota 1/default radius.
2. `wildcard`: grant explicit LuckPerms `superior.*`; verify unlimited ownership
   and grid-cap radius.
3. `revokewildcard`: revoke the explicit parent grant while OP remains enabled;
   verify quota 1/default radius, then remove OP.
4. `endprepare`: paste real End schematics when necessary, acknowledge metadata
   and create the two native dormant fights.
5. `endenter`, `endactive`: teleport the two actual clients into separate arenas,
   wait for two 289-chunk owned arenas and distinct dragons/boss IDs.
6. `bosshide`, `bossshow`: hide, remove/re-add the real viewer and show the boss
   bars again. The external client driver must independently verify remove/add
   packets and that each client receives only its arena's boss UUID.
7. `killfirst`, `deathwait`: set the owned first dragon's health to zero and wait
   for the real native death animation and exit portal.
8. `respawnfirst`, `respawnwait`: create four actual End crystals around the
   native exit portal, invoke native respawn and wait for a new live dragon UUID.
9. `endleave`, `released`: move both actual clients out of the End and verify
   zero arena reservations/tickets after removal acknowledgements and zero
   actual native per-coordinate plugin tickets, independently of arena counters.
10. `endreactivate`: re-enter both arenas and verify native ownership/ticket
    reacquisition. Wait for actual Folia merge/split callbacks; `status` records
    the monotonic native counters, fight identities and ticket counts.
11. `finish`: require the complete sequence, both native lifecycle callback
    increments and zero regional tracking failures before emitting
    `SUPERIOR_ADVANCED_PROBE_PASS`.

The helper cannot establish client-visible packet correctness by inspecting
server membership alone. Keep both client packet traces and native server
logs with the qualification evidence. A successful compile is not native
qualification; none of these checks is recorded as passed until run.
