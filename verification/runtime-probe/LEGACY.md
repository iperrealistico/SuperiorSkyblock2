# Exact configuration LEGACY observer

The root-only probe adapter constructs `SuperiorLegacyProbe(this, superior)`
and routes `/pvcskyprobe legacy-<action>` to `execute(Player, action)`. The helper
and client refuse every path except an owned `local-servers/superior-runtime-*`
fixture with `.superior-owned`, a loopback listener on port 29010 through 29029,
and the actual fixed account `PVCSkyProbe`. Neither file belongs in the release.

Stage the existing Cielo plugin configuration without any PVC section, and save
its exact pre-start bytes as `staged-before-config.yml` at the fixture root.
Retain the actual Classic schematic and the existing creation menu; its single
grass-block button occupies slot 4. The helper requires configured default
radius 100 and migrated `pvc.size-mode: LEGACY`. Test permissions grant only the
fixed account's ordinary island/create/teleport/list/select/Classic access; no
ownership or radius entitlement, wildcard, OP, group or external grant is made.

Run the maintained Node client `verification/scripts/legacy-probe.mjs` with the
existing `SUPERIOR_TEST_PORT`, `SUPERIOR_TEST_VERSION`, `SUPERIOR_MINEFLAYER` and
`SUPERIOR_PHASE` environment, plus `SUPERIOR_PROFILE=legacy` and absolute
`SUPERIOR_TEST_ROOT`. The client independently validates the listener/path and
maintained client module location before connecting. Versions are restricted to
1.21.11 and 26.2. Initial and restart invocations use the same retained fixture.

The initial phase creates one actual island through `/is create` and the real
creation path, verifies radius 100/default quota 1/durable UUID and short ID/sole selected
island, moves the actual player away, and executes argument-free `/is home`.
The exact Cielo configuration enables `skip-one-item-menus`, so the native
provider directly selects the sole Classic item; no client menu is expected.
When this existing setting is false, the driver instead uses the real slot-4
menu and checks the observed click count. It never changes the setting.
It attempts a second actual creation, waits for the configured native failure
response and terminal creation state, then confirms quota 1 and unchanged island
identity. `/is list` also runs through the ordinary player command path. The
checkpoint records identity, radius, selection and config hashes only.

Both phases parse every original YAML key and compare its value with the live
file; new keys are permitted. No config value is logged. The migration backup
must equal the original staged bytes exactly. The restart phase repeats native
home/list usage and requires the UUID, short ID, radius, selected island,
original-source hash, backup hash and migrated-config hash to remain identical.
This verifies migration idempotence, rather than only successful startup.

Success signals are `SUPERIOR_LEGACY_PROBE_PASS` and
`SUPERIOR_LEGACY_RESTART_PASS`, followed by `SUPERIOR_LEGACY_CLIENT_PASS` from the
separate real client. Their presence is a result only after root runs the native
fixtures. Added source or a compiled observer is not a passed acceptance gate.
