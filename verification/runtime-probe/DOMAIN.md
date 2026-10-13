# Native domain qualification helper

`SuperiorDomainProbe` is test source only and refuses Cielo/production paths,
unowned fixtures and grid caps above128. Root should instantiate it lazily only
for `domain-` commands in the existing `SuperiorProbe` adapter, so the real
Cielo1000 profile remains usable by the separate ordinary observer. Connect
both actual clients `PVCSkyProbe` and `PVCSkyProbeB` normally; never fabricate
Bukkit events. Build and launch only through the workspace resource gate.

In an empty, separate tiny-grid fixture, run `create-race` and await PASS. It
submits two simultaneous real creation requests against quota2, waits for both
ACTIVE acknowledgements, then rejects a third creation. Run `memberships` to
verify one member has independent roles on both islands. Run `select-first`,
execute `/is home --island <second ID>` from the actual client, and run
`selected-stable`. Run `transfer-two` for two simultaneous accepted incoming
transfers, then `transfer-denied` for one accepted and one rejected transfer
against quota1. Run `reset` to verify cleanup completes before re-paste and the
UUID/shortID survive. Both `reset` and `delete` first acknowledge chunk write
intent, then place real REDSTONE_BLOCK sentinels at center+10X/Y100 on each
island through its region scheduler. Success requires the operated sentinel to
be AIR after the operation ACK and the neighboring sentinel to remain intact.
`delete` also verifies membership removal and its durable shortID tombstone.

Run `checkpoint`, normally stop and restart the owned fixture, reconnect both
clients and run `restart-check`. This compares exact island identities, owners,
per-island role IDs and selected UUIDs. A success is reported only after actual
execution; this source does not establish qualification by itself.

Use separate checkpoint/restart cycles for `cold-reset-intent` and
`cold-transfer-intent`. These deliberately retain an acknowledged non-terminal
operation (with no thread blocked) to exercise native cold recovery. The latter
acknowledges the ownership before-image mutation without the terminal ACK;
restart must restore the original owner and both membership roles. This is a
synthetic interruption at a real durable boundary, not an actual power-loss test.
Cold reset additionally persists a physical expectation outside the server data
and `restart-check` must inspect the original sentinel as AIR on its region owner.
For `cold-delete-intent`, retain the checkpoint IDs as tombstone expectations;
after restart `status` must show no primary ownership and the old UUID must be
absent from the live grid. Run `cold-delete-check` to assert tombstones and the
old physical sentinels as AIR, create a real fresh island and verify its shortID
exceeds the greatest ID observed before earlier deletions. Do not use exact
`restart-check`, which intentionally expects unchanged live islands.

Keep native server logs, helper signals, client traces and independent database
inspection. This helper does not qualify every optional addon, mission/economy
reward command, GUI layout or a large production player load.

Separate cycles `storage-reset-failure` and `storage-delete-failure` wrap only
the private native storage adapter in this guarded test observer. Normal methods
delegate unchanged; one actual save of the target sentinel chunk fails with an
IOException. PASS requires the operation future to fail, the canonical island
and ownership/cell to remain reserved, the SQL journal to retain its nonterminal
intent and the neighbor sentinel to remain unchanged. The adapter is restored
before PASS. Normally stop/restart, reconnect both clients and run
`storage-restart-check`; it validates exact remaining metadata, physical AIR and
the neighbor REDSTONE_BLOCK. A real guardian island is created if none exists.

The optional `storage-reset-hold`/`storage-delete-hold` variants instead retain
one uncompleted storage acknowledgement after the real SQL intent and native
world mutation. No thread is blocked. On the `_WORLD_ACK_HELD` signal, root may
terminate only the owned fixture process and then restart/run
`storage-restart-check`. Do this before the normal storage deadline. This is an
abrupt process boundary, not a simulated Bukkit event or an operating-system
power-loss qualification. The helper never terminates a process itself and no
failure-injection method is present in the distributable plugin.
