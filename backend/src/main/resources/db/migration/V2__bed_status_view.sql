-- =====================================================================================
-- V2 - the derived bed status
--
-- A bed has no status column anywhere. Its status is a FUNCTION of whether a live claim
-- exists on it, and this view is that function written down once so every query in the
-- application agrees on the answer.
--
--   no live claim   -> AVAILABLE
--   PENDING claim   -> PENDING
--   ALLOCATED claim -> ALLOCATED
--   BLOCKED claim   -> BLOCKED
--
-- Why not just store a status on the bed? Because that would be a second copy of the truth.
-- Any bug that resolved a request without also updating the bed would leave that bed stuck
-- in a state nobody could clear, needing a repair script. With no second copy there is
-- nothing to get out of sync.
-- =====================================================================================

CREATE VIEW bed_status AS
SELECT b.id                            AS bed_id,
       b.room_id,
       b.bed_label,
       COALESCE(c.status, 'AVAILABLE') AS status,
       c.id                            AS claim_id,
       c.student_id,
       c.expires_at
FROM beds b
LEFT JOIN bed_claims c
       ON c.bed_id = b.id
      -- "Live" means the claim currently occupies the bed. Terminal rows (REJECTED,
      -- CANCELLED, EXPIRED, UNBLOCKED) stay in the table as history but are invisible here.
      AND c.status IN ('PENDING', 'ALLOCATED', 'BLOCKED');

COMMENT ON VIEW bed_status IS
    'Derived bed status. Returns exactly one row per bed only because uq_claim_live_per_bed '
    '(V3) guarantees at most one live claim per bed - without that index this LEFT JOIN could '
    'fan out and every occupancy count in the application would silently be wrong.';
