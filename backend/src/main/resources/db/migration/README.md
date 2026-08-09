# Flyway migrations

Versioned SQL that defines the database schema. Flyway runs every file here, in version order, once
each, and records what it applied in a `flyway_schema_history` table it creates itself.

**Phase 1 is deliberately empty.** The pipeline is wired up and proven (Flyway starts, connects,
creates its history table, finds nothing to do) before any schema exists, so when the first
migration lands there is only one new thing to debug.

Naming: `V<version>__<description>.sql` — note the **double** underscore.

Planned, from the Phase 0 contract:

| file | phase | contents |
|---|---|---|
| `V1__core_tables.sql` | 2 | `rooms`, `beds`, `users`, `bed_claims`, `revoked_tokens` |
| `V2__partial_unique_indexes.sql` | 4 | `uq_claim_live_per_bed`, `uq_claim_live_per_student`, supporting indexes |
| `V3__bed_status_view.sql` | 3 | the derived `bed_status` view |

**Never edit a migration that has already run** on any database you care about — Flyway stores a
checksum of each applied file and refuses to start if one changes underneath it. Fix forward with a
new `V<n+1>__` file instead.
