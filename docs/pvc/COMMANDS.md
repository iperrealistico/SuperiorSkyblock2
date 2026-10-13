# Commands and island targeting

This document describes the maintained command changes for `2026.3-pvc.3`.
The exact artifact, completed tests and supported native builds belong to the
[verification record](VERIFICATION.md). Qualification of `2026.3-pvc.1` is
historical evidence for that artifact, not qualification of a later JAR.

The original command classes, aliases and ordinary permissions remain. Disabled
modules stay disabled; installing the fork does not enable bank, missions,
upgrades, menus or other gameplay features in the operator's configuration.

A command from a caller with several memberships may outlive a cached policy.
The dispatcher performs at most one bounded authoritative refresh and resumes
on that player's owning scheduler. It preserves arguments and captured island
intent, then rechecks current permissions and policy. A healthy cache expiry
does not silently turn an admitted selector into an unknown command. A failed
or stale resumed query cannot grant new ownership or loop indefinitely.
Unrelated commands do not acquire a dependency on an incidental selected island.

## Ordinary play and conditional multiple-island tools

Players continue to use commands such as `/is`, `/is create`, `/is home`,
`/is team`, `/is invite <player>` and `/is accept` with their existing permissions
and configuration. A player with one island needs no additional island argument.
Existing positional arguments remain required where upstream required them:
for example, `/is visit <player-or-island>` still needs its destination.

Multiple-island presentation is enabled only when **both** conditions hold:

1. The caller's verified effective ownership quota is greater than one,
   including an explicitly granted unlimited quota.
2. The caller currently has more than one published owned/team membership.

| Caller state | Presentation and command behavior |
| --- | --- |
| Default quota one, one island | Ordinary syntax; no personal list/select tools or ID suggestions |
| Default quota one, one owned island and other team memberships | Ordinary presentation; the owned island remains the implicit home/management target |
| Quota above one, zero or one membership | Ordinary presentation; `/is create` can acquire another island when ordinary creation checks and quota allow it |
| Quota above one, several memberships | Personal list/select tools and temporary selectors become available, subject to command permissions |
| Quota later reduced to one | Multiple-island tools disappear; retained islands, UUIDs, short IDs, terrain and memberships are preserved |
| Quota cannot currently be verified | Presentation remains conservative; an unavailable permission source does not authorize new ownership |

For a limited caller, the implicit target prefers an owned island. If several
owned islands remain after a quota reduction, a still-valid selected owned
island wins; otherwise the oldest owned short ID is used. With no owned island,
a valid selected team membership wins, then the oldest team membership. This
fallback does not delete data, rewrite a permission or merge memberships.

For an unlocked caller, the persistent selection remains the implicit target.
These personal tools are available:

| Command | Alias | Permission | Effect |
| --- | --- | --- | --- |
| `/is list [page]` | `/is islands` | `superior.island.list` | List owned/team islands, durable short IDs and the role on each island |
| `/is select <selector>` | `/is use` | `superior.island.select` | Change persistent selection to an available membership |
| `/is home --island <selector>` | Ordinary home aliases also work | Existing home permission | Target one invocation without changing persistent selection |

The list/select nodes have plugin `default: true`, but their handlers, help and
completion remain gated by the two conditions above. An explicit permission
deny still takes precedence. The fork performs **no LuckPerms group, user,
permission or metadata writes**. A quota grant does not grant ordinary create,
home, invite or administrative permissions. A plugin metadata default is not a
stored rank assignment.

Administrative island selectors remain available to permitted administrators
even when their personal ownership quota is one. Identifiers are presentation
and addressing tools, not a permission to mutate another player's island.

## Selector forms and target precedence

A selector can be a durable server-local short ID, an island UUID or an island
name. Typed forms remove ambiguity:

```text
id:2
#2
uuid:00000000-0000-0000-0000-000000000002
name:Garden
```

The UUID above is a syntax example, not an existing island. A numeric island
name can be addressed as `name:123`; ordinary numeric selectors identify short
IDs. Names can change, while island UUIDs and assigned short IDs remain stable;
short IDs are never reused after deletion or abandoned creation.

For a positional player nickname, one owned island has priority over other team
memberships. If the player owns none, a sole membership remains unambiguous.
Administrative callers and unlocked multiple-island callers must choose a
destination when several owned candidates remain. Limited ordinary callers use
the deterministic owned/primary fallback instead of receiving ID-management
instructions.

```text
/is admin close Alice
/is admin close id:2
/is admin close Alice --island id:2
/is admin kick Bob --island id:2
/is admin setleader Alice Bob --island id:2
```

`kick`, `promote`, `demote` and `setleader` still name the affected person. The
selected island must contain that person and satisfy the command's normal role,
availability and permission checks. To administer a team membership distinct
from that player's owned island, specify the team island explicitly.

Temporary selectors do not remove original positional parameters. For example,
`/is admin close --island 2` omits the original destination argument and is not
the replacement for `/is admin close 2`.

## Free-form text and nested commands

Put the outer `--island <selector>` before message text, configured data values
or a nested command. Once a free-form payload has started, its later tokens are
opaque to the outer selector parser. The rule applies to local/team chat,
administrative messages, `cmdall`, titles and `data set` values. Custom aliases
resolve to the same command family before determining the payload boundary.

Use the `--` separator before a payload whose first token must be interpreted
literally rather than as an outer selector:

```text
/is admin msgall 1 --island 1 Hello; use --island 2 for the next command
/is admin msgall 1 -- --island 2 is literal message text
/is admin cmdall 1 true is admin setbonus {player-name} 10 --island 2
```

The last example addresses island 1's members while retaining the nested
command's explicit destination 2. It does not retarget the outer `cmdall` to 2.
The escaped separator is consumed by the outer parser; the following payload
remains intact. At most one outer selector is allowed.

Configured console chains inherit the captured originating island only as a
fallback for legacy nickname targeting. An explicit island ID/name/UUID or a
nested `--island` continues to override that fallback. The inherited context
must not turn every configured command into a command against the origin.

## Command-specific behavior

`/is accept` preserves the upstream first valid invitation order for limited
callers. Accepting a nickname deterministically prefers an eligible owned
primary invitation, then the oldest owned invitation, then the eligible team
primary or first invitation. Several distinct eligible invitations require
destination advice only when the recipient's multiple-island presentation is
unlocked. Duplicate invitation records do not create false ambiguity. Original
invitation, team-capacity and join-event checks still apply.

`/is warp <warp>` retains the sender-island shorthand. With an explicit selector,
the warp is resolved on the chosen island, including public warps on another
player's island. `/is warp <destination> <warp>` accepts the same destination
forms as execution; completion resolves that exact destination and hides private
warps from non-members. Selecting an island does not grant access to a private
warp.

`/is localchat` uses the actual sender as its actor even with a target selector.
`/is team` sorts and displays roles belonging to the listed island rather than
roles from members' later selections. A one-shot team-chat selector affects that
message; subsequent ordinary chat still follows persistent selection.

Administrative `removeratings` intentionally has two different operations:

| Form | Operation |
| --- | --- |
| `/is admin removeratings <nickname>` | Remove that author's ratings across the addressed grid snapshot; ownership count is irrelevant |
| `/is admin removeratings <island-name-or-ID>` | Clear all ratings of that island |
| `/is admin removeratings <destination> --island <selector>` | Clear the explicitly selected island's ratings |
| `/is admin removeratings *` | Clear ratings on all addressed islands |

An explicit island must never be reinterpreted as its owner's global rating
history. A name that matches a player nickname retains the author operation;
use `name:` for an island whose name collides with a nickname.

Administrative member completion inspects each candidate's actual membership
roles, independently of the administrator's own selection. `setleader`
completes the new-leader argument with members of the target island, including
members who also have other islands. Ownership admission remains checked by the
asynchronous transfer operation.

`/is admin purge <time>` and `/is admin purge cancel` remain global without an
explicit selector. Merely selecting a personal island does not narrow a global
operation. With an explicit selector, both scheduling and cancellation are
limited to that target.

## Configured upgrades and mission rewards

Upgrade hooks retain configured order. A chain containing a recognized Superior
upgrade mutation must actually reach its expected upgrade level on the captured
island before the corresponding cost is withdrawn or success is reported.
A rejected dispatch, changed target or unchanged expected level is a failure.
Configured explicit destinations remain respected.

External-only upgrade hooks retain upstream payment-before-hooks behavior.
Economy plugins and arbitrary external commands do not supply a general commit,
compensation or exactly-once protocol; earlier external actions may already have
taken effect when a later action fails. Do not interpret a Bukkit dispatch
boolean as proof of a business mutation.

Own configured chains pass their actual island as a formal dispatcher argument.
Registered root aliases and the Superior namespace use the same core command
validation path; an inherited fallback cannot replace an explicit destination.
External commands retain the ordinary Bukkit boundary and its provider limits.
This also avoids relying on ambient server-dispatch context for business-level
upgrade acknowledgement. No direct field mutation replaces a command handler.

Island-mission reward commands capture the actual island and restore its context
on the player's owning scheduler. A change of selection while work is pending
must not redirect rewards to another island. The retained generation,
availability and membership checks apply before delivery; personal missions
remain keyed to the player. See [API migration](API-MIGRATION.md) and
[mission migration](MISSION-MIGRATION.md) for provider boundaries.

## Optional dangerous-command approval

The upstream `dangerous-commands` list is empty by default. These protections
apply when the operator deliberately adds commands to it; the fork does not
enable an additional approval requirement automatically.

Requests are consumed once, expire after one minute, and recheck the requesting
player's current command permission and online state. Island operations capture
island UUID, owner, operation revision and membership revision. Ownership
changes, role changes and remove/rejoin cycles invalidate a pending request,
including cycles that restore the same final role. `kick` and `demote` declare
their island dependency so a later sole membership cannot replace the original
target. Request identity follows player UUID rather than a reused nickname.

In `PERMISSION_AUTOMATIC`, `setsize`/`addsize`, and owner-wide
`clearsizeoverride`, capture **owner UUIDs** instead of requiring an arbitrary
single island. They work for a known owner with zero, one or several islands and
preserve the future-island override policy. Wildcard operations freeze the
addressed owner set; a later acquisition does not add another owner to the
approved request. See [entitlements](ENTITLEMENTS.md).

A nickname `removeratings` request freezes its author UUID and addressed island
snapshot. An explicit island-clear request remains an island clear when
approved. Approval must not change the operation's meaning by resolving the
island's owner or a different player nickname later.

## Corrections from the command audit

| Audit finding | Maintained-source correction |
| --- | --- |
| F01: local-chat actor | Keep the sender identity while selecting the destination |
| F02: rating-author/island confusion | Separate author-wide removal from explicit island clearing |
| F03: nested upgrade target and false success | Inherit only fallback context, preserve explicit destinations and require recognized level advancement before cost/success |
| F04: deferred mission reward target | Capture and restore the island on the entity scheduler |
| F05: external warp selector | Resolve the requested warp on the explicit island and retain privacy checks |
| F06: payload selectors | Keep free-form and nested payload tokens intact; support an explicit `--` boundary |
| F07: owner-override confirmation | Capture owner UUID intent independently of current island count |
| F08: member-command confirmation | Freeze the island and membership revision for kick/demote |
| F09: warp completion | Resolve the same identifier forms and filter private warps |
| F10: team role ordering | Sort with roles belonging to the listed island |
| F11: administrative member completion | Use candidate/target memberships and the correct new-leader argument |
| F12: implicit global purge narrowing | Restrict purge only for an explicit target |

These changes define the command contract and regression targets. They do not
claim that every arbitrary custom command, third-party menu, economy hook or
addon is qualified. Use the completed exact-artifact verification record before
deployment and repeat the command gates after future upstream merges.

## PVC3 follow-up corrections

The original 149 command classes, aliases and permission nodes remain present.
The subsequent R01–R12 review adds public `counts`/`values`/`team` selectors,
literal Discord/warp payload boundaries, captured `cmdall` and configured
menu/bank/placeholder dispatch, pre-hook stale-menu rejection, membership-epoch
confirmations and current ban hierarchy checks. Console expulsion/team handling,
generator approval argument isolation and rating-event authors are corrected.
Personal/global menus have no incidental island binding; mixed island/personal
mission menus keep their actual island dependency. Public reports use a specific
report resolver rather than weakening membership checks for stateful commands.

See [PVC3 corrections](CORRECTIONS-PVC3.md) for the closure matrix and focused
verification boundaries. Administrative permissions are still required; the
fork makes no LuckPerms assignments and does not expose multi-island management
to an ordinary quota-one player.
