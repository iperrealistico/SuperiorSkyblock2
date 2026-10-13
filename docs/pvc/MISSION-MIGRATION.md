# Built-in mission state and coordinate migration

Built-in island mission progress is keyed by the island UUID. A player with
several islands can contribute to separate subjects; ownership transfer does
not move or merge the subject. Events capture their actual island, and delayed
work retains that target instead of consulting a later selection.

Counters use per-subject metadata monitors and detached save snapshots.
Block bitsets and planted-block maps retain independent updates from different
regions. Items missions cache immutable counts per actor and definition, with
at most 128 actors per subject. They never retain another member's live item
references. Consumption recounts the acting player's inventory on its entity
owner immediately before removing items from that inventory.

Coordinate format 2 preserves signed X and Z independently. In the old encoded
format, a negative Z sign-extended through the high 32 bits and destroyed X.
Positive Z old keys remain exact and migrate directly. Old records containing
full block coordinates can also be converted exactly. An old encoded negative Z
key has insufficient information to recover X and is never guessed.

When ambiguous encoded data is found, the affected Blocks or Farming tracker
quarantines that world and logs the reason. Automatic completion for its
affected subjects is denied. Its mission files are retained without overwrite,
deletion or counter reset; other mission types and the backend remain usable.
The native loader keeps an original `.pvc-subject-migration.bak` copy for each
built-in progress file before loading it. A quarantined tracker refuses direct
serialization as well. Resolve the original records from trustworthy coordinate
evidence and restart before permitting that tracker again. Do not relabel
ambiguous keys as format 2: that cannot restore the missing coordinates.

The Blocks files use `tracker-data-version: 2`; Farming uses `data-version: 2`.
Quarantine is conservative for the affected tracker/world because legacy
Blocks records do not identify a player or island. New progress for unrelated
worlds can remain available in memory, but files sharing the quarantined tracker
are preserved until the ambiguity is resolved.

External economy providers and arbitrary reward commands do not expose a
general idempotency contract. Their retained upstream effects cannot be claimed
exactly once across an abrupt process loss. Cold island recovery does not replay
those effects. Native qualification records identify the functions actually
exercised; these source contracts alone do not constitute a runtime pass.
