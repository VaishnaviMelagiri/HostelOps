# Flyway migrations

Versioned SQL that defines the database schema. Flyway runs every file here, in version order, once
each, and records what it applied in a `flyway_schema_history` table it creates itself.

**Phase 1 is deliberately empty.** The pipeline is wired up and proven (Flyway starts, connects,
creates its history table, finds nothing to do) before any schema exists, so when the first
migration lands there is only one new thing to debug.

Naming: `V<version>__<description>.sql` — note the **double** underscore.

From the Phase 0 contract:

| file | phase | contents |
|---|---|---|
| `V1__core_tables.sql` | 2 | `rooms`, `beds`, `users`, `bed_claims`, `revoked_tokens` |
| `V2__bed_status_view.sql` | 3 | the derived `bed_status` view |
| `V3__partial_unique_indexes.sql` | 4 | `uq_claim_live_per_bed`, `uq_claim_live_per_student`, supporting indexes |

**V2 and V3 are swapped from the original Phase 0 plan, and had to be.** That plan put the view at
V3 (Phase 3) and the indexes at V2 (Phase 4) — but Flyway applies migrations in version order and
refuses to start if a *lower* version shows up after a higher one has already run. Adding V2 in
Phase 4, after V3 had been applied in Phase 3, would have failed with
`Detected resolved migration not applied to database: 2`. Version numbers have to follow the order
things are actually built, not the order they were designed.

**Never edit a migration that has already run** on any database you care about — Flyway stores a
checksum of each applied file and refuses to start if one changes underneath it. Fix forward with a
new `V<n+1>__` file instead.
