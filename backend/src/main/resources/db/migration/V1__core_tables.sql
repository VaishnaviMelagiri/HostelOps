-- =====================================================================================
-- V1 - core tables
--
-- The five tables of the Phase 0 contract, created together. Table definitions only:
-- the two partial unique indexes that make this project's concurrency guarantees real
-- arrive in V2 (Phase 4), and the derived bed_status view in V3 (Phase 3).
--
-- Note what is NOT here: no bed status column anywhere. A bed's status is derived from
-- its live claim in bed_claims, so there is no second copy of the truth to get stuck.
-- =====================================================================================


-- ─────────────────────────── rooms (404 rows) ───────────────────────────
CREATE TABLE rooms (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_number       INTEGER      NOT NULL,
    wing              CHAR(1)      NOT NULL,
    block             TEXT         NOT NULL,
    floor             VARCHAR(4)   NOT NULL,       -- BAS | GF | FF | SF | TF
    floor_level       SMALLINT     NOT NULL,       -- ordering WITHIN a wing, not absolute
    position_in_floor SMALLINT     NOT NULL,
    room_type         VARCHAR(8)   NOT NULL,
    bathroom_type     VARCHAR(8)   NOT NULL,
    capacity          SMALLINT     NOT NULL,

    -- Globally unique because the wing ranges are disjoint (1-64, 101-148, 201-264,
    -- 301-388, 401-480, 501-560). Asserting it means the seed loader fails loudly if the
    -- source data ever gains an overlap, instead of silently creating a duplicate room.
    CONSTRAINT uq_rooms_number   UNIQUE (room_number),
    CONSTRAINT uq_rooms_position UNIQUE (wing, floor, position_in_floor),

    CONSTRAINT ck_rooms_type     CHECK (room_type     IN ('Single','Double')),
    CONSTRAINT ck_rooms_bath     CHECK (bathroom_type IN ('Attached','Common')),
    CONSTRAINT ck_rooms_capacity CHECK (
        (room_type = 'Single' AND capacity = 1) OR
        (room_type = 'Double' AND capacity = 2))
);

CREATE INDEX idx_rooms_wing_floor ON rooms (wing, floor_level, position_in_floor);

COMMENT ON COLUMN rooms.floor_level IS
    'Ordering within a wing only. Wings A-D start at GF=0; wings E-F have a basement, so '
    'BAS=0 and GF=1. Never compare floor_level across wings.';


-- ─────────────────────────── beds (576 rows) ───────────────────────────
-- A Single room gets one bed ('A'); a Double gets two ('A','B'). Every rule in the system
-- talks about beds, so Single and Double rooms run through identical code - a Single is
-- just a room that happens to have one bed. No special case anywhere.
CREATE TABLE beds (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id   BIGINT  NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    bed_label CHAR(1) NOT NULL,

    CONSTRAINT uq_beds_room_label UNIQUE (room_id, bed_label),
    CONSTRAINT ck_beds_label      CHECK (bed_label IN ('A','B'))
);

CREATE INDEX idx_beds_room ON beds (room_id);


-- ─────────────────────────── users ───────────────────────────
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,            -- BCrypt
    role          VARCHAR(8)  NOT NULL,
    full_name     TEXT        NOT NULL,
    student_code  VARCHAR(20),                     -- e.g. '1MS22CS001'
    course        VARCHAR(80),                     -- shown to a confirmed roommate only
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT','ADMIN','GUEST')),

    -- Only STUDENT carries a student_code and course. Phrased as "not a student, or has
    -- both", so ADMIN and GUEST fall out of the rule automatically - which is why adding
    -- the GUEST role needed no change here.
    CONSTRAINT ck_users_student_fields CHECK (
        role <> 'STUDENT' OR (student_code IS NOT NULL AND course IS NOT NULL))
);

-- Functional index: uniqueness applies to the LOWERCASED email, so 'Student@Hostelops.Demo'
-- and 'student@hostelops.demo' cannot both exist. More reliable than remembering to
-- lowercase in every code path.
CREATE UNIQUE INDEX uq_users_email        ON users (lower(email));
CREATE UNIQUE INDEX uq_users_student_code ON users (student_code) WHERE student_code IS NOT NULL;


-- ─────────────────────────── bed_claims - the heart of the system ───────────────────────────
-- One table holds both a student's claim on a bed (PENDING/ALLOCATED) and an admin's
-- maintenance block (BLOCKED, student_id NULL). They live together specifically so that ONE
-- unique index can cover the student-vs-student race AND the block-vs-request race - two
-- separate tables cannot share an index.
CREATE TABLE bed_claims (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    bed_id          BIGINT      NOT NULL REFERENCES beds(id),
    student_id      BIGINT               REFERENCES users(id),   -- NULL iff this is a block
    status          VARCHAR(12) NOT NULL,
    created_by      BIGINT      NOT NULL REFERENCES users(id),   -- student, or admin for a block
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ,                                 -- set only while PENDING
    decided_at      TIMESTAMPTZ,
    decided_by      BIGINT               REFERENCES users(id),
    decision_reason TEXT,
    request_key     UUID,                                        -- client Idempotency-Key
    version         INTEGER     NOT NULL DEFAULT 0,              -- JPA @Version

    CONSTRAINT ck_claims_status CHECK (status IN
        ('PENDING','ALLOCATED','REJECTED','CANCELLED','EXPIRED','BLOCKED','UNBLOCKED')),

    -- A claim belongs to a student, OR it is a maintenance block. Never both, never neither.
    CONSTRAINT ck_claims_owner CHECK (
        (status IN ('BLOCKED','UNBLOCKED') AND student_id IS NULL) OR
        (status NOT IN ('BLOCKED','UNBLOCKED') AND student_id IS NOT NULL)),

    -- Only a PENDING claim has a deadline.
    CONSTRAINT ck_claims_ttl CHECK (
        (status = 'PENDING') = (expires_at IS NOT NULL)),

    -- WHO acted is recorded in two places depending on WHEN they acted:
    --   created_by - the actor for a row BORN in its state (student -> PENDING, admin -> BLOCKED)
    --   decided_by - the actor who moved a row OUT of a live state
    -- Unblocking is a real admin decision (often correcting a mistaken block), so UNBLOCKED is
    -- audited exactly like ALLOCATED/REJECTED/CANCELLED - no exemption.
    -- EXPIRED is the single system-driven transition: the scheduler resolved it, no human did,
    -- so it has a decided_at but deliberately no decided_by.
    CONSTRAINT ck_claims_decided CHECK (
        (status IN ('PENDING','BLOCKED')
             AND decided_at IS NULL     AND decided_by IS NULL) OR
        (status = 'EXPIRED'
             AND decided_at IS NOT NULL AND decided_by IS NULL) OR
        (status IN ('ALLOCATED','REJECTED','CANCELLED','UNBLOCKED')
             AND decided_at IS NOT NULL AND decided_by IS NOT NULL))
);

CREATE INDEX idx_claims_bed_history  ON bed_claims (bed_id, created_at DESC);
CREATE INDEX idx_claims_student_hist ON bed_claims (student_id, created_at DESC);


-- ─────────────────────────── revoked_tokens ───────────────────────────
-- A JWT is normally valid until it expires, which makes "log out now" impossible: the token
-- is already in the user's hands and the server keeps no session. Two things fix that
-- together - keep access tokens short-lived (15 minutes), and keep a small denylist of the
-- jti (JWT ID) claim of tokens revoked before their natural expiry.
--
-- The table stays tiny because a row is only useful until the token would have expired anyway,
-- at which point a scheduled cleanup deletes it.
CREATE TABLE revoked_tokens (
    jti        UUID        PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,      -- the token's own exp; safe to delete the row after this
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_revoked_expiry ON revoked_tokens (expires_at);
