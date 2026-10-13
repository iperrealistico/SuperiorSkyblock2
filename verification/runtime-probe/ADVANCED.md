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
   and create the two native dormant fights. The private shared void-world
   fixture receives a real 3x3 bedrock floor at the unchanged configured common
   spawn Y-1, after validating its preloaded normal world, safe height and absence
   of an occupied player cell. Each actual floor chunk is written on its owner
   and native storage-acknowledged; no player is frozen or kept artificially
   flying, and the occupied-reset spawn position assertion remains unchanged.
5. `endenter`, `endactive`: teleport the two actual clients onto real, native
   storage-acknowledged 3x3 bedrock waiting platforms, 12 blocks horizontally
   away from each island's exit podium. This keeps actual visitors outside the
   legitimate death-created exit portal. Teleport the primary back onto its
   platform before starting death; no dummy visitor, forced wake or paused
   arena is used. In separate arenas,
   wait for two 289-chunk owned arenas and distinct dragons/boss IDs.
6. `killfirst`, `deathwait`: set the owned first dragon's health to zero and wait
   for the real native death animation and exit portal.
7. `respawnfirst`, `respawnwait`: create four actual End crystals around the
   native exit portal, invoke native respawn and wait for a new live dragon UUID.
   Require an actual End visitor and owned arena, and record the real native
   respawn stage before/after crystal addition and the explicit call. A false
   return is acceptable only when crystal addition already produced a genuine
   non-null native stage from the previous null stage; four actual live owned
   crystals and the subsequent new live dragon UUID remain mandatory.
8. `endleave`, `released`: move both actual clients out of the End and verify
   zero arena reservations/tickets after removal acknowledgements and zero
   actual native per-coordinate plugin tickets, independently of arena counters.
9. `bossshow`, `bosshide`, `bossshow`: register each real viewer on its actual
   inactive boss event, hide/remove/re-add, then show again. Fight state changes
   run on its center owner; recipient packets cross to each actual player owner.
   The external driver requires real register/remove/re-add packets with the
   original native boss UUID and no foreign arena packets. A live native fight
   intentionally restores its visibility every tick, so it cannot qualify a
   persistent explicit hide; no native tick or state is artificially suspended.
10. `endreactivate`: re-enter both arenas and verify native ownership/ticket
    reacquisition. Then run `native-topology` while both actual clients remain
    in the End. The bounded [topology helper](TOPOLOGY.md) uses four real tickets
    in the reserved normal-world cell, actual owner identities and genuine
    merge/split callbacks, and releases its own tickets before completing.
    `status` records the monotonic native counters, fight identities and ticket
    counts. This proves normal-world topology changes while both End battles
    stay active; it does not claim the independent End arenas merged.
11. `occupiedendreset`: require the primary physically in End A and auxiliary
    in End B, persist distinct bedrock sentinels, then call the real
    `((SIsland) A).resetWorldAsync(End)` path. Require its completed future, actual
    `RESET_DONE` SQL acknowledgement, available source cell, increased generation,
    unchanged UUID/short ID, ungenerated End and an AIR source sentinel. Verify
    actual primary evacuation to the configured common spawn, unchanged auxiliary
    position/island, same live B dragon UUID and captured actual B terrain,
    including all nine previously storage-acknowledged waiting-platform blocks.
    The main implementation must remove the A fight and release all its actual
    tickets; the observer never repairs it. Initial sentinel writes are native
    storage-acknowledged, and reset completion includes native storage ACKs.
12. `finish`: require the complete sequence, both native lifecycle callback
    increments and zero regional tracking failures before emitting
    `SUPERIOR_ADVANCED_PROBE_PASS`.
13. `endleave`, `released`: leave the surviving B arena and require all remaining
    reservations and physical tickets to reach zero before the client exits.

The helper cannot establish client-visible packet correctness by inspecting
server membership alone. Keep both client packet traces and native server
logs with the qualification evidence. A successful compile is not native
qualification; none of these checks is recorded as passed until run.
