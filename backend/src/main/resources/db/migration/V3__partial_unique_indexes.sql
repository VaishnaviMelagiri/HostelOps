-- =====================================================================================
-- V3 - the two partial unique indexes
--
-- This is the point of the whole project. Every race condition in HostelOps is answered
-- by these two lines of DDL, enforced by Postgres itself rather than by application code
-- that a race could slip past.
--
-- Read "WHERE" as: this index only contains rows matching this condition. Terminal rows
-- (REJECTED, CANCELLED, EXPIRED, UNBLOCKED) are simply not in the index, so a bed can be
-- requested, rejected and requested again a hundred times - but never held twice at once.
-- The full history stays in the table for audit; only the LIVE row is constrained.
-- =====================================================================================


-- INDEX 1 - at most ONE live claim per BED, ever.
--
-- Answers three separate questions with one mechanism:
--   * two students requesting the same bed at the same instant
--   * two admins allocating the same bed
--   * an admin blocking a bed a student is simultaneously requesting
--
-- The third only works because blocks live in this same table (student_id IS NULL).
-- Two different tables cannot share a unique index, so storing blocks separately would
-- need a row lock instead.
CREATE UNIQUE INDEX uq_claim_live_per_bed
    ON bed_claims (bed_id)
    WHERE status IN ('PENDING', 'ALLOCATED', 'BLOCKED');


-- INDEX 2 - at most ONE live claim per STUDENT, ever.
--
-- The same mechanism reused for a completely different rule: it stops a student spamming
-- requests across many beds, and stops a student who already holds an allocation from
-- requesting a second one.
--
-- BLOCKED rows have student_id IS NULL. Postgres treats NULLs as distinct in a unique
-- index, so they could never collide anyway - but they are excluded explicitly so the
-- index says what it means rather than relying on NULL semantics to be remembered.
CREATE UNIQUE INDEX uq_claim_live_per_student
    ON bed_claims (student_id)
    WHERE status IN ('PENDING', 'ALLOCATED');


-- Idempotency for a double-clicked request button. The client sends an Idempotency-Key
-- header; the same key can only ever produce one row, so the same click twice is one
-- request, not two.
CREATE UNIQUE INDEX uq_claim_request_key
    ON bed_claims (request_key)
    WHERE request_key IS NOT NULL;


-- Supporting (non-unique) indexes.

-- The expiry sweep (Phase 6) scans only pending rows, so this index holds only pending
-- rows. However large the history grows, the sweep touches a handful of entries.
CREATE INDEX idx_claims_pending_expiry
    ON bed_claims (expires_at)
    WHERE status = 'PENDING';

-- The admin pending queue (Phase 5), oldest request first.
CREATE INDEX idx_claims_pending_queue
    ON bed_claims (created_at)
    WHERE status = 'PENDING';


COMMENT ON INDEX uq_claim_live_per_bed IS
    'At most one PENDING/ALLOCATED/BLOCKED claim per bed. Named explicitly because the '
    'service layer maps this constraint name to the BED_NOT_AVAILABLE error code - '
    'renaming it silently degrades a clear 409 into a generic 500.';

COMMENT ON INDEX uq_claim_live_per_student IS
    'At most one PENDING/ALLOCATED claim per student. Mapped to STUDENT_ALREADY_HAS_CLAIM.';
