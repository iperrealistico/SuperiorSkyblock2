# PVC4 release verification

**Status: qualified on 2026-10-13 for the exact inputs and scenarios below.**
All eight selected initial/restart phases passed with the final main artifact.

The `2026.3-pvc.4` artifact introduces English server presentation and PVC
branding. Qualification status and the eight exact-artifact native results are
recorded in the [portable ledger](../../verification/qualification-2026.3-pvc.4.json).
The preceding artifact's [95-phase PVC3 qualification](VERIFICATION-PVC3.md)
is retained unchanged as historical evidence; it was not rerun in full for PVC4.

## Artifact and source provenance

| Property | Recorded value |
| --- | --- |
| Plugin version | `2026.3-pvc.4` |
| Compiled main source | `b110eca0f392e288462dc5cee6e9edc78d5b245c` |
| Final native observer source | `b51a7edfe7416521e08b8da4959dc856d213c184` |
| Release source reference | `pvc/2026.3-pvc.4` |
| Upstream baseline | `868bebf41df19615621828619f59e2d8386f7ace`, version `2026.3` |
| Main SHA-256 | `64EEDC40E3A137229CF251B17D7C7FDC13D23FDED7FE9CF447FDF4C52DF574BB` |
| Main size | 8,948,634 bytes |
| Final observer SHA-256 | `804659F6BA3732A709716158376D7DD4E63669F8B3A5C5CB7F62C805FEFED3E2` |
| License | Original GPL-3.0 and public API/package identity retained |

Two full clean builds produced identical main JAR bytes. The final observer was
subsequently strengthened to check a legitimate existing Italian translation;
the main JAR remained byte identical. Later commits contain observer diagnostics,
documentation and sanitized evidence only. The observer is a private test input
and is not distributed as a production plugin.

## Automated and presentation contracts

248 JUnit tests across 61 reports passed with zero failures/errors/skips.
Strict dependency provenance passed for 695 artifact identities and 5,731 uses;
API inspection retained 363 exported classes. Artifact inspection preserves
the Java 8 core/API baseline and original supported adapter baselines.

New tests cover forced server presentation, raw stored-locale retention,
multilingual opt-out, missing-only language/configuration migration, service
prefix/palette and parsed default resource contracts. Actual YAML comparisons
preserve placeholders, material identifiers, command targets, permissions,
non-presentation settings, comments and explicit empty messages. The presentation
audit covers 147 bundled resource files plus the fork's Java service replies.

The configured server default remains authoritative: `force-default-language`
does not replace an operator-selected `default-language`. See
[BRANDING.md](BRANDING.md) for the exact scope and opt-out.

## Native scope

The focused profile comprises an initial run and retained-state restart on each
of Leaf 1.21.11 build 174, Leaf 26.2 build 123, Folia 1.21.11 build 14 and Folia
26.2 build 7. Unmodified runtime wrappers and their digests are recorded in the
portable ledger. Every selected phase uses the same main and final observer
digests above; qualification requires all eight phases to pass.

The legacy fixture retains Cielo's Classic menu/preset, existing radius/quota
configuration and disabled modules. It starts without the new PVC configuration
section, exercising automatic addition and restart persistence. A real client
checks menu/create/home/denial behavior, and the observer saves an Italian
preference, verifies both existing and fork-added messages render in English,
confirms the raw stored preference remains intact, exercises the blocked language
selector and verifies the actual English service reply received by the client.
Existing functional selectors, modules, sizing and permissions are unchanged.

Three preliminary fixture failures are retained privately and excluded from
qualification: an accidentally preseeded PVC section, a missing registered
Italian translation and a missing Classic schematic. Their fixture inputs were
corrected without relaxing assertions or changing the compiled main artifact.

These are finite functional checks, not a load/TPS benchmark or a new full
qualification of every PVC3 scenario, economy provider, addon or future version.

## Existing Cielo configuration

The operator authorized a narrow explicit theme update to generated Superior
configuration files on Cielo. The private staged YAML validation checked 43 files
and preserved functional settings, private credentials and command behavior;
39 files were updated with presentation changes. Applied file hashes and original backup
location are recorded locally, without publishing private configuration content.
This does not install a JAR, reload/restart production, change permissions or
modify another backend. Prepared files take effect through the normal stopped
rollout in [INSTALLATION.md](INSTALLATION.md).
