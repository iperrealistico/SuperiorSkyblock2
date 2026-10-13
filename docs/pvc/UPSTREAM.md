# Upstream provenance and integration

Canonical source: https://github.com/BG-Software-LLC/SuperiorSkyblock2.git

Operator origin: https://github.com/iperrealistico/SuperiorSkyblock2.git

The fork starts at full-history upstream commit
`868bebf41df19615621828619f59e2d8386f7ace` (`2026.3`, 2026-10-05).
The clone is not shallow. Original tags, commit ancestry, package names, API
descriptors, GPL-3.0 license and upstream attribution are retained.

`pvc/main` is the integration branch; implementation branches use `codex/`.
`upstream` is fetch-only, with its push URL disabled locally. `origin` is the
operator's existing fork. Never mirror-push, force-push published history,
replace original tags, or push private runtime evidence and production config.

## Updating from upstream

1. Start from a clean `pvc/main` checkout and preserve a backup ref of its exact
   commit. Fetch upstream branches and tags without silently replacing changed
   tags. Select and record an exact upstream release or commit.
2. Inspect the upstream range, especially NMS mappings, Java baselines, database
   migrations, command dispatch, permissions, world creation and asynchronous
   callbacks. Follow `PATCHES.md` to decide whether each PVC patch is retained,
   adapted, or superseded; remove duplicate fixes.
3. Create `codex/superior-update-<version>` from `pvc/main` and merge the selected
   upstream commit using `git merge --no-ff --no-commit <verified-commit>`.
   Resolve conflicts by behavior, preserving ancestry. Use `git merge --abort`
   to abandon an integration rather than rewriting release history.
4. Update exact development-bundle coordinates and qualification inputs.
   Keep core/API on Java 8 and preserve legacy platform adapters. Folia adapters
   remain separately compiled and lazily loaded. Unknown NMS versions require
   a maintained-source adapter and native qualification, not a version bypass.
5. Run unit, configuration-upgrade, API compatibility and native acceptance
   checks. Minecraft 1.21.11 and 26.2 are both mandatory; include initial start,
   restart, actual client commands, multiple regions, permission revocation,
   transactional failure and interrupted-operation recovery.
6. Build two identical clean release artifacts, inspect their contents and
   Java class versions, compare SHA-256 hashes, and update the portable
   verification record. Submit a reviewed origin PR before integration.
   Preserve the preceding JAR plus a complete data/config/world backup for
   rollout. A source merge never authorizes deployment or a production restart.

## Source dependencies

The maintained common DatabaseBridge `b2` source archive exposed two contracts
that cannot be used as durable acknowledgments: SQL failures are logged without
failing its executor future, and collections of transactions are not one JDBC
transaction. The fork implements its own source-level bounded JDBC writer in
`core/database/sql/PvcSqlTransactions.java`; no dependency bytecode is patched.

bStats uses official `3.1.0`, which provides a Folia-aware scheduling path while
retaining its Java 8 baseline. Folia's regional block trackers compile against
official development bundles `1.21.11-R0.1-20260222.203011-14` and
`26.2.build.7-beta`. Release qualification records must identify their matching
unmodified runtime artifacts separately from compile dependencies.
