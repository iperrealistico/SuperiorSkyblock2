# SuperiorSkyblock2 PVC maintenance fork

In the operator workspace read `../AGENTS.md`, the private
`../.ai-control/START-HERE.local.md`, and
`../docs/superiorskyblock2-fork-plan.md` before changes. The user authorized
implementation and custom JAR delivery on 2026-10-12; this supersedes the
earlier planning-only restriction. Production C:/MC remains read-only.

This checkout keeps full upstream history and independent remotes. Preserve
the original GPL license, package/API identity, Java8 core/API baseline and
1.21.11+26.2 acceptance requirement. Maintain modern platform adapters behind
conditional class loading. No bytecode/JAR patches, source-less version bypass,
published-history rewrite or upstream push. Generic fixes and PVC policy changes
must remain separately documented in docs/pvc/PATCHES.md.

Parallel agents have explicit file scopes assigned by root. Coordinate shared
entry-point changes instead of overwriting another agent's files. All heavy
builds and native runtimes are launched by root through the workspace exclusive
build.lock gate, only with at least8GiB free RAM. Do not start builds or servers
independently. No config/permission writes, reload, restart or install on C:/MC.

Future release documentation records exact source, JAR digest, tests, native
lanes, migration/rollback and remaining capability limits. A Folia support flag
is not evidence of region-safe behavior. New config keys must be added when
absent with atomic backup-preserving migration; existing settings stay intact.
No secrets, player data, production configuration or private evidence in Git.
