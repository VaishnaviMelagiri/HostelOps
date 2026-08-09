# HostelOps — Phase 0: Architecture

Design only. No implementation code. Everything here is the contract that Phases 1–13 build against.

Ground truth: 404 rooms / 576 beds (verified against `rooms.json`):
A 64×1 + B 48×2 + C 64×2 + D 88×1 + E 80×1 + F 60×2 = 64+96+128+88+80+120 = **576 beds**, 404 rooms.

---

## 0. Two corrections to the plan, up front

**(a) Blocking cannot live in a separate table if you want the two indexes to be the whole story.**
The plan says *"TWO partial unique indexes prevent every race condition — no other mechanism needed."*
That is true for student-vs-student. It is **not** true for admin-blocks-a-bed vs student-requests-the-same-bed,
if blocks are stored anywhere other than the same table the requests live in — two different tables cannot
share a unique index, so that race would need a row lock (`SELECT ... FOR UPDATE`) instead.

Fix: store blocks as rows in the **same table**, with `student_id IS NULL`. Then index 1 covers the
block-vs-request race too, and the plan's claim becomes literally true. The table is therefore named
`bed_claims`, not `allocation_requests` — a claim on a bed is either a student's (PENDING/ALLOCATED)
or the admin's (BLOCKED).

**(b) The indexes do not make approve/reject idempotent.**
Index 1 stops *two claims existing on one bed*. It does nothing about *one claim being resolved twice*,
because approval is an `UPDATE` of an existing row, not an `INSERT`. Idempotency comes from a
**conditional update** (`WHERE id = :id AND status = 'PENDING'`) and inspecting the affected-row count.
Both mechanisms are needed; they answer different questions. Interviews will notice if these are conflated.

---

## 1. System architecture

### Boxes and arrows (turn this into a diagram)

```
┌─────────────────────────── BROWSER ───────────────────────────┐
│  React + Vite + TS + Tailwind                                 │
│                                                               │
│  ┌────────────┐  ┌───────────────┐  ┌──────────────────────┐  │
│  │ Auth ctx   │  │ Floor map     │  │ Dashboards           │  │
│  │ (JWT in    │  │ (SVG grid,    │  │ student / admin      │  │
│  │ sessionSt.)│  │  popover)     │  │                      │  │
│  └─────┬──────┘  └───────┬───────┘  └──────────┬───────────┘  │
│        │  REST (fetch, Bearer token)           │              │
│        │                 │  STOMP over WebSocket              │
└────────┼─────────────────┼────────────────────┼───────────────┘
         │                 │                    │
         v                 v                    v
┌──────────────────────── SPRING BOOT 3 ────────────────────────┐
│                                                               │
│  SecurityFilterChain                                          │
│    JwtAuthFilter ──> TokenRevocationService (jti denylist)    │
│    ↓ sets Authentication in SecurityContext                   │
│                                                               │
│  CONTROLLERS (thin, DTO in / DTO out, no business logic)      │
│    RoomController · RequestController · AdminController       │
│    AuthController · OccupancyController                       │
│                       │                                       │
│  SERVICES (@Transactional — the only place rules live)        │
│    RoomQueryService · ClaimService · OccupancyService          │
│    ClaimExpiryScheduler (@Scheduled)                          │
│                       │                                       │
│  REPOSITORIES (Spring Data JPA + a few native queries)        │
│                       │                                       │
│  EVENT PUBLISHER — fires only AFTER transaction commit        │
│    @TransactionalEventListener(phase = AFTER_COMMIT)          │
│         │                          │                          │
│         v                          v                          │
│  SimpMessagingTemplate      SimpMessagingTemplate             │
│  .convertAndSend(           .convertAndSendToUser(            │
│    "/topic/floors/B-FF")      studentId, "/queue/requests")   │
└────────┬──────────────────────────────────────────────────────┘
         │ JDBC
         v
┌──────────────── POSTGRESQL ────────────────┐
│ rooms · beds · users · bed_claims          │
│ revoked_tokens                             │
│                                            │
│ uq_claim_live_per_bed      (partial unique)│  <-- the whole concurrency story
│ uq_claim_live_per_student  (partial unique)│
└────────────────────────────────────────────┘
         ▲
         │ (Phase 10, stretch, optional)
┌────────┴────────┐
│ Redis           │  cache-aside for occupancy counts only
└─────────────────┘
```

### The one arrow that matters

`Service commits → THEN publish`. Never the reverse. If the WebSocket event fires inside the
transaction and the transaction then rolls back, every connected browser is now showing a bed status
that does not exist in the database, and nothing will ever correct it. Spring's
`@TransactionalEventListener(phase = AFTER_COMMIT)` is exactly this guard.

---

## 2. Folder structure

### backend/

```
backend/
├── pom.xml
├── Dockerfile
├── src/main/java/com/hostelops/
│   ├── HostelOpsApplication.java
│   ├── config/
│   │   ├── SecurityConfig.java          # filter chain, CORS, password encoder
│   │   ├── WebSocketConfig.java         # STOMP broker, /ws endpoint, auth interceptor
│   │   ├── SchedulingConfig.java        # @EnableScheduling
│   │   └── JacksonConfig.java           # ISO-8601 instants, no nulls in JSON
│   ├── security/
│   │   ├── JwtService.java              # sign / parse / jti
│   │   ├── JwtAuthFilter.java           # OncePerRequestFilter
│   │   ├── TokenRevocationService.java  # logout denylist
│   │   ├── RevokedToken.java  RevokedTokenRepository.java
│   │   ├── AppUserDetailsService.java  AppUserPrincipal.java
│   │   ├── Permission.java              # named authorities
│   │   └── RolePermissions.java         # the ONLY place a Role appears in an authz context
│   ├── user/                            # domain identity, separate from the security machinery
│   │   ├── User.java  Role.java         # claim/ needs User for student identity from Phase 4
│   │   └── UserRepository.java
│   ├── auth/
│   │   ├── AuthController.java  AuthService.java
│   │   └── dto/  (LoginRequest, LoginResponse, UserDto)
│   ├── common/
│   │   ├── ErrorCode.java               # enum — the API's stable error vocabulary
│   │   ├── ApiError.java                # {code, message, details}
│   │   ├── DomainException.java
│   │   └── GlobalExceptionHandler.java  # @RestControllerAdvice
│   ├── room/
│   │   ├── Room.java  Bed.java          # entities
│   │   ├── RoomRepository.java  BedRepository.java
│   │   ├── RoomQueryService.java
│   │   ├── RoomController.java
│   │   └── dto/  (WingSummaryDto, FloorRoomsDto, RoomCellDto, BedStateDto)
│   ├── claim/
│   │   ├── BedClaim.java  ClaimStatus.java
│   │   ├── BedClaimRepository.java      # conditional updates live here
│   │   ├── ClaimService.java            # request / cancel / approve / reject / block / unblock
│   │   ├── StudentRequestController.java
│   │   ├── AdminClaimController.java
│   │   └── dto/  (CreateRequestDto, ClaimDto, PendingQueueRowDto, MyAllocationDto, RoommateDto)
│   ├── occupancy/
│   │   ├── OccupancyService.java
│   │   └── OccupancyController.java
│   ├── realtime/
│   │   ├── BedStatusChangedEvent.java   # internal Spring event
│   │   ├── RequestResolvedEvent.java
│   │   ├── RealtimePublisher.java       # AFTER_COMMIT listener -> STOMP
│   │   └── payload/  (BedStatusChangedPayload, RequestResolvedPayload)
│   ├── expiry/
│   │   └── ClaimExpiryScheduler.java
│   └── seed/
│       └── SeedRunner.java              # idempotent: 404 rooms, 576 beds, 3 demo users
├── src/main/resources/
│   ├── application.yml                  # + application-local.yml, application-prod.yml
│   ├── db/migration/                    # Flyway
│   │   ├── V1__core_tables.sql
│   │   ├── V2__partial_unique_indexes.sql
│   │   └── V3__bed_status_view.sql
│   └── seed/
│       ├── rooms.json
│       └── wings-summary.json
└── src/test/java/com/hostelops/
    ├── auth/            AuthFlowTest
    ├── claim/           RequestFlowTest, ApprovalIdempotencyTest, BlockRulesTest
    ├── concurrency/     SameBedRaceTest, DoubleApprovalRaceTest   # Testcontainers, real Postgres
    └── expiry/          ExpirySweepTest
```

### frontend/

```
frontend/
├── index.html  vite.config.ts  tailwind.config.js  tsconfig.json  .env.example
└── src/
    ├── main.tsx  App.tsx  routes.tsx
    ├── api/
    │   ├── client.ts        # fetch wrapper: base URL, Bearer header, ApiError parsing
    │   ├── auth.ts  rooms.ts  requests.ts  admin.ts
    │   └── types.ts         # hand-written mirror of every backend DTO
    ├── auth/
    │   ├── AuthContext.tsx  useAuth.ts  ProtectedRoute.tsx  usePermission.ts
    │   ├── LoginPage.tsx          # one email/password form — the only way in, for all 3 roles
    │   └── DemoAccountButtons.tsx # 3 one-click buttons; each just calls the same login()
    ├── realtime/
    │   ├── stompClient.ts   # single shared STOMP connection
    │   ├── useFloorTopic.ts # subscribe /topic/floors/{wing}-{floor}
    │   └── useMyRequests.ts # subscribe /user/queue/requests
    ├── features/
    │   ├── map/     FloorMap.tsx RoomCell.tsx RoomPopover.tsx WingFloorPicker.tsx Legend.tsx
    │   ├── student/ StudentDashboard.tsx MyAllocationCard.tsx RoommateCard.tsx
    │   └── admin/   AdminDashboard.tsx PendingQueue.tsx BlockPanel.tsx OccupancyOverview.tsx
    ├── components/  Button.tsx Badge.tsx Modal.tsx Toast.tsx Spinner.tsx
    └── lib/         status.ts (status -> colour + label) format.ts (relative time)
```

---

## 3. PostgreSQL schema

Four tables plus a token denylist. Managed by Flyway.

### Design decision: bed status is **derived**, never stored

`beds` has no `status` column. A bed's status is a function of its live claim:

| live claim in `bed_claims` | bed status |
|---|---|
| none | `AVAILABLE` |
| `PENDING` | `PENDING` |
| `ALLOCATED` | `ALLOCATED` |
| `BLOCKED` | `BLOCKED` |

Why: a stored status is a second copy of the truth, and any bug that resolves a request without also
updating the bed leaves a bed permanently stuck in a state nobody can clear. With a derived status
there is no second copy, so there is nothing to get out of sync. The partial unique index guarantees
"at most one live claim per bed", which is what makes the derivation single-valued.

### V1 — core tables

```sql
-- ─────────────── rooms (404 rows, from rooms.json) ───────────────
CREATE TABLE rooms (
    id                BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_number       INTEGER      NOT NULL,
    wing              CHAR(1)      NOT NULL,
    block             TEXT         NOT NULL,
    floor             VARCHAR(4)   NOT NULL,       -- BAS | GF | FF | SF | TF
    floor_level       SMALLINT     NOT NULL,       -- ordering WITHIN a wing (see note)
    position_in_floor SMALLINT     NOT NULL,
    room_type         VARCHAR(8)   NOT NULL,
    bathroom_type     VARCHAR(8)   NOT NULL,
    capacity          SMALLINT     NOT NULL,

    CONSTRAINT uq_rooms_number   UNIQUE (room_number),
    CONSTRAINT uq_rooms_position UNIQUE (wing, floor, position_in_floor),
    CONSTRAINT ck_rooms_type     CHECK (room_type     IN ('Single','Double')),
    CONSTRAINT ck_rooms_bath     CHECK (bathroom_type IN ('Attached','Common')),
    CONSTRAINT ck_rooms_capacity CHECK (
        (room_type = 'Single' AND capacity = 1) OR
        (room_type = 'Double' AND capacity = 2))
);
CREATE INDEX idx_rooms_wing_floor ON rooms (wing, floor_level, position_in_floor);
```

Notes on the real data:
- `room_number` is globally unique because the wing ranges are disjoint (1–64, 101–148, 201–264,
  301–388, 401–480, 501–560). Asserting that as a constraint means the seed loader fails loudly if
  the source data ever gains an overlap, instead of silently creating a duplicate room.
- **`floor_level` is per-wing ordering, not a building-absolute level.** In wings A–D, `GF = 0`.
  In wings E–F, `BAS = 0` and `GF = 1`. So never compare `floor_level` across wings — sort within a
  wing only. This is a real quirk of the source data and it will bite the map renderer if ignored.
- The `status` field present in `rooms.json` is **dropped on import**. Status is bed-level and derived.

```sql
-- ─────────────── beds (576 rows, generated from rooms) ───────────────
CREATE TABLE beds (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id   BIGINT  NOT NULL REFERENCES rooms(id) ON DELETE CASCADE,
    bed_label CHAR(1) NOT NULL,                    -- 'A' always; 'B' only in Double rooms

    CONSTRAINT uq_beds_room_label UNIQUE (room_id, bed_label),
    CONSTRAINT ck_beds_label      CHECK (bed_label IN ('A','B'))
);
CREATE INDEX idx_beds_room ON beds (room_id);
```

A Single room gets one bed (`A`); a Double gets two (`A`, `B`). This is the "no special case" point
from the brief made physical: every rule in the system talks about beds, so Single and Double rooms
run through exactly the same code path — a Single is just a room that happens to have one bed.

```sql
-- ─────────────── users ───────────────
CREATE TABLE users (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,            -- BCrypt
    role          VARCHAR(8)  NOT NULL,
    full_name     TEXT        NOT NULL,
    student_code  VARCHAR(20),                     -- e.g. "1MS22CS001"
    course        VARCHAR(80),                     -- shown to a confirmed roommate
    active        BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_users_role CHECK (role IN ('STUDENT','ADMIN','GUEST')),

    -- Only STUDENT carries a student_code and course. ADMIN and GUEST are both exempt, and the
    -- constraint expresses that as "not a student, or has both" — so adding GUEST needed no change
    -- here. A role that is not STUDENT simply falls out of the rule.
    CONSTRAINT ck_users_student_fields CHECK (
        role <> 'STUDENT' OR (student_code IS NOT NULL AND course IS NOT NULL))
);
CREATE UNIQUE INDEX uq_users_email        ON users (lower(email));
CREATE UNIQUE INDEX uq_users_student_code ON users (student_code) WHERE student_code IS NOT NULL;
```

`lower(email)` as a **functional unique index** means `Student@Hostelops.Demo` and
`student@hostelops.demo` cannot both exist — cheaper and more reliable than remembering to lowercase
in every code path.

```sql
-- ─────────────── bed_claims — the heart of the system ───────────────
CREATE TABLE bed_claims (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
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

    -- a claim belongs to a student, OR it is a maintenance block. Never both, never neither.
    CONSTRAINT ck_claims_owner CHECK (
        (status IN ('BLOCKED','UNBLOCKED') AND student_id IS NULL) OR
        (status NOT IN ('BLOCKED','UNBLOCKED') AND student_id IS NOT NULL)),

    -- only a PENDING claim has a deadline
    CONSTRAINT ck_claims_ttl CHECK (
        (status = 'PENDING') = (expires_at IS NOT NULL)),

    -- WHO acted is recorded in two different places, depending on when they acted:
    --   created_by  — the actor for a row BORN in its state (student -> PENDING, admin -> BLOCKED)
    --   decided_by  — the actor who moved a row OUT of a live state
    -- Unblocking is a real admin decision (often: correcting a mistaken block), so UNBLOCKED is
    -- audited exactly like ALLOCATED/REJECTED/CANCELLED — no exemption.
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
```

Every rule the API can state in an error message is also stated as a `CHECK` here. If the service
layer ever has a bug, the database refuses the write rather than storing a nonsense row.

`status` is `VARCHAR + CHECK` rather than a Postgres `ENUM` type deliberately: adding a value to a
PG enum is DDL (awkward inside a Flyway migration set and, pre-12, impossible in a transaction),
and JPA's enum mapping to a native PG enum needs a custom type handler. A `VARCHAR` with a `CHECK`
gives the same guarantee with none of that friction.

### V2 — the two partial unique indexes (the point of the project)

```sql
-- INDEX 1 — at most ONE live claim per BED, ever.
-- Answers: two students requesting the same bed; two admins allocating the same bed;
--          an admin blocking a bed a student is simultaneously requesting.
CREATE UNIQUE INDEX uq_claim_live_per_bed
    ON bed_claims (bed_id)
    WHERE status IN ('PENDING','ALLOCATED','BLOCKED');

-- INDEX 2 — at most ONE live claim per STUDENT, ever.
-- Answers: a student spamming requests across many beds;
--          a student who already holds an allocation requesting a second bed.
CREATE UNIQUE INDEX uq_claim_live_per_student
    ON bed_claims (student_id)
    WHERE status IN ('PENDING','ALLOCATED');

-- Idempotency for the student's own double-click (same key => same row).
CREATE UNIQUE INDEX uq_claim_request_key
    ON bed_claims (request_key) WHERE request_key IS NOT NULL;

-- Supporting (non-unique) indexes.
CREATE INDEX idx_claims_pending_expiry ON bed_claims (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_claims_bed_history    ON bed_claims (bed_id, created_at DESC);
CREATE INDEX idx_claims_student_hist   ON bed_claims (student_id, created_at DESC);
```

**Read `WHERE` as "this index only contains rows matching this condition."**
Terminal rows (`REJECTED`, `CANCELLED`, `EXPIRED`, `UNBLOCKED`) are simply not in the index, so a bed
can be requested, rejected, and requested again a hundred times — but never held twice at once. The
full history stays in the table for audit; only the *live* row is constrained.

**Why Postgres over MySQL — the honest version.**
Postgres expresses both rules as one line of DDL each. MySQL has no `WHERE` on indexes; the closest
equivalent is a generated column that is `NULL` for terminal rows plus a unique index on it (MySQL
treats NULLs as distinct), i.e. a workaround that encodes the predicate in a column definition
instead of the index. So it is *possible* in MySQL, not impossible — the honest answer is that
Postgres states the intent directly and readably, and additionally gives transactional DDL and
`INSERT ... ON CONFLICT` that can target a partial index. Claiming MySQL "can't do this" is the kind
of overstatement an interviewer will unpick; the readability + transactional-DDL argument holds up.

### V3 — derived bed status as a view

```sql
CREATE VIEW bed_status AS
SELECT b.id                             AS bed_id,
       b.room_id,
       b.bed_label,
       COALESCE(c.status, 'AVAILABLE')  AS status,
       c.id                             AS claim_id,
       c.student_id,
       c.expires_at
FROM beds b
LEFT JOIN bed_claims c
       ON c.bed_id = b.id
      AND c.status IN ('PENDING','ALLOCATED','BLOCKED');
```

This `LEFT JOIN` produces exactly one row per bed **only because index 1 exists**. Without it the
view could fan out and every bed count in the app would silently be wrong. The index isn't just a
safety net; the read model depends on it.

### Token denylist (Phase 2)

```sql
CREATE TABLE revoked_tokens (
    jti        UUID        PRIMARY KEY,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_revoked_expiry ON revoked_tokens (expires_at);
```

Short-lived access tokens (15 min) plus a denylist keyed on the token's `jti` claim: logout inserts a
row, `JwtAuthFilter` rejects any token whose `jti` is present, and a nightly job deletes rows past
`expires_at` so the table stays tiny.

---

## 4. REST API contract

Base: `/api`. All responses JSON. All errors share one envelope:

```json
{ "code": "BED_NOT_AVAILABLE",
  "message": "That bed was just taken by another student.",
  "details": { "bedId": 214 } }
```

`code` is a stable enum the frontend switches on. `message` is human text that may be reworded freely.

### Error codes

| code | HTTP | meaning |
|---|---|---|
| `VALIDATION_FAILED` | 400 | malformed body / missing field |
| `UNAUTHENTICATED` | 401 | missing, expired, or revoked token |
| `FORBIDDEN` | 403 | authenticated but lacks the permission |
| `NOT_FOUND` | 404 | bed / room / request id doesn't exist |
| `BED_NOT_AVAILABLE` | 409 | bed already has a live claim (index 1) |
| `STUDENT_ALREADY_HAS_CLAIM` | 409 | student already has a live claim (index 2) |
| `REQUEST_ALREADY_RESOLVED` | 409 | approve/reject/cancel on a non-PENDING request |
| `BED_ALLOCATED_CANNOT_BLOCK` | 409 | blocking an allocated bed — eviction is out of scope |
| `BED_NOT_BLOCKED` | 409 | unblocking a bed that isn't blocked |

### Roles and permissions

Three roles, **one authentication path**. `POST /api/auth/login` does not know or care what role the
user has — it verifies the password, loads the user, and mints a JWT. The role only determines which
authorities are stamped into that token. There is no "guest mode", no anonymous bypass, no second
login endpoint: a guest is a real row in `users` with a real BCrypt password, and every request they
make carries a real Bearer token through the same filter chain as everyone else.

| permission | STUDENT | ADMIN | GUEST |
|---|:--:|:--:|:--:|
| `ROOM_READ` — wings, floor map, room popover | ✓ | ✓ | ✓ |
| `REQUEST_CREATE` — request an available bed | ✓ | — | — |
| `REQUEST_CANCEL_OWN` — cancel own pending request | ✓ | — | — |
| `ALLOCATION_READ_OWN` — `GET /api/me/allocation` | ✓ | — | — |
| `REQUEST_QUEUE_READ` — pending queue with identities | — | ✓ | — |
| `REQUEST_APPROVE` / `REQUEST_REJECT` | — | ✓ | — |
| `BED_BLOCK` / `BED_UNBLOCK` | — | ✓ | — |
| `OCCUPANCY_READ` — aggregate counts | — | ✓ | — |

**GUEST holds exactly one permission: `ROOM_READ`.** It can browse every wing and floor, open a room
popover, and see per-bed status — the same identity-free payload students and admins get. Any other
endpoint returns `403 FORBIDDEN` from the permission check, before the service layer or the database
is touched. Concretely: `POST /api/requests` → 403, `DELETE /api/requests/{id}` → 403,
`GET /api/me/allocation` → 403 (a guest has no allocation to read — 403 rather than an empty 200,
because the honest answer is "you can't ask this", not "the answer is nothing"), and every
`/api/admin/**` route → 403.

This is exactly why the checks are `@PreAuthorize("hasAuthority('REQUEST_CREATE')")` and never
`if (role.equals("ADMIN"))`. Adding GUEST changed **zero** authorization call sites — it is a new row
in this table and a new set of granted authorities, nothing else. That's the payoff for the
permissions-not-roles rule, and it's a concrete thing to point at in an interview rather than a claim.

**ADMIN is deliberately NOT a superset of STUDENT — settled, not provisional.** Read the table
again: an admin has no `REQUEST_CREATE`, no `REQUEST_CANCEL_OWN`, no `ALLOCATION_READ_OWN`. An admin
has no allocation of their own and does not occupy student beds, so those permissions would describe
an ability nobody intends them to have. The tempting mental model — "admin can do everything a
student can, plus more" — is the one that quietly grants exactly those. Two tests pin this
(`RolePermissionsTest`, `SecurityRulesTest`), because it is the kind of thing a later refactor
"tidies up" into a hierarchy without noticing what it hands out. If a real staff member ever needs
their own bed, they get a second STUDENT account; the role describes a job, not a rank.

**Why GUEST exists:** a reviewer or interviewer can open the deployed app and explore the real
576-bed building immediately, without creating state — no pending request left behind on a bed, no
cleanup between demos. It is a read-only lens on the same system, not a fourth code path.

**Real-time:** GUEST subscribes to the public per-floor topic like anyone else (that payload carries
no identities, so there is nothing to withhold). GUEST has **no** private `/user/queue/requests` — it
can never own a request, so nothing would ever be published there.

**The resolved permission list is part of the API.** Both `POST /api/auth/login` and
`GET /api/auth/me` return `permissions[]` alongside the user. The frontend needs it to decide what
to *render* — a GUEST must not be shown a "Request this bed" button that can only ever return 403.
Sending the server's own resolved list means the UI applies the same rules from the same source
(`RolePermissions`) instead of reimplementing "what can a guest do" in TypeScript, where it would
silently drift the first time the matrix changed. It is a rendering hint and nothing more: the
server re-authorises every request regardless, so editing the list in the browser buys an attacker
a visible button and a 403.

### Where the access token lives (browser)

`sessionStorage`, keyed `hostelops.accessToken`.

An earlier draft of this contract said "JWT in memory", which sounds stricter but does not survive
examination. Any script running in the page can reach the token either way: from `sessionStorage`
it is one synchronous read, from a module-scoped variable it means patching `fetch` and waiting for
the next request. That is an extra step for an attacker, not a barrier. Choosing in-memory would
therefore give up refresh-survival — every page reload signing the user out, which in a demo reads
as a broken app — in exchange for protection the wording never actually provided.

What genuinely limits the damage is elsewhere, and is built: a 15-minute token lifetime, and
server-side revocation that takes effect the instant logout is called.

`sessionStorage` over `localStorage` **is** a real distinction, and the reason to prefer it: it is
scoped to a single tab and cleared when that tab closes, so a token cannot outlive the session on a
shared machine.

The actual upgrade is an httpOnly refresh cookie, which no script can read at all. That stays out of
scope: it requires CSRF protection and a refresh-token rotation flow, which is a larger design than
this project sets out to defend — and saying so plainly is better than implying the in-memory
variant was equivalent to it.

**One honest boundary:** nothing in the *database* stops a `bed_claims` row from pointing at a GUEST
user — `student_id` is just an FK to `users`. That rule lives only in the permission layer, because a
DB-level guarantee would need either a trigger or the role denormalised into `bed_claims`, and
neither is worth it for a rule no code path can reach. Unlike the two partial unique indexes, this
one is enforced by code, and it's worth saying so plainly rather than implying the schema covers it.

### Demo accounts (seeded, Phase 2)

| email | password | role | full_name |
|---|---|---|---|
| `student@hostelops.demo` | `Student@123` | STUDENT | Demo Student |
| `admin@hostelops.demo` | `Admin@123` | ADMIN | Demo Admin |
| `guest@hostelops.demo` | `Guest@123` | GUEST | Guest Reviewer |

`Guest Reviewer` has `student_code = NULL` and `course = NULL`, permitted by `ck_users_student_fields`
for the same reason ADMIN is. All three are created by the same idempotent `SeedRunner` with the same
BCrypt hashing — no special-cased demo user.

**One-click demo login (Phase 2 UI).** `LoginPage` renders the normal email/password form plus three
buttons — *Sign in as Student · Sign in as Admin · Sign in as Guest* — so an interviewer never has to
type credentials or find them in a README. Each button calls the *same* `login(email, password)` the
form calls, with the pair prefilled; there is no token shortcut, no bypass route, no client-side role
assignment. The credentials are visible in the frontend bundle, which is correct: these are seeded
demo accounts on a demo database, and the whole point is that anyone can use them.

The buttons render only when `VITE_SHOW_DEMO_LOGINS=true` (on in `.env.example`, i.e. local and the
Vercel demo deploy) so the flag exists to turn them off, without that ever changing how login works.

### Auth

```
POST /api/auth/login
  → { "email": "...", "password": "..." }
  ← 200 { "accessToken": "eyJ...", "expiresAt": "2026-08-09T12:45:00Z",
          "user": { "id": 7, "email": "asha@hostelops.demo", "fullName": "Asha R",
                    "role": "STUDENT", "studentCode": "1MS22CS001", "course": "B.E. CSE",
                    "permissions": ["ALLOCATION_READ_OWN","REQUEST_CANCEL_OWN",
                                    "REQUEST_CREATE","ROOM_READ"] } }
  ← 401 UNAUTHENTICATED

POST /api/auth/logout        (Bearer)   ← 204   # inserts jti into revoked_tokens
GET  /api/auth/me            (Bearer)   ← 200  the SAME user shape as login returns
       { id, email, fullName, role, studentCode?, course?, permissions[] }
```

### Browsing (STUDENT + ADMIN + GUEST — one endpoint, byte-identical payload for all three)

```
GET /api/wings
← 200 [ { "wing":"A", "block":"M.S. Ramaiah Hostels - Triveni", "roomType":"Single",
          "bathroomType":"Attached", "floors":["GF","FF","SF","TF"],
          "roomsPerFloor":16, "totalRooms":64, "totalBeds":64 }, ... ]

GET /api/wings/{wing}/floors/{floor}/rooms          # e.g. /api/wings/B/floors/FF/rooms
← 200 {
  "wing": "B", "floor": "FF", "floorLevel": 1,
  "grid": { "columns": 12, "rows": 1 },             # renderer-agnostic layout hint
  "rooms": [
    { "roomId": 113, "roomNumber": 113, "roomType": "Double", "bathroomType": "Attached",
      "capacity": 2, "cell": { "col": 1, "row": 1 },     # cell position, NOT pixels
      "roomStatus": "PARTIAL",                            # AVAILABLE|PARTIAL|FULL|BLOCKED
      "beds": [
        { "bedId": 4001, "bedLabel": "A", "status": "ALLOCATED" },
        { "bedId": 4002, "bedLabel": "B", "status": "AVAILABLE" }
      ] }, ... ] }
```

`cell` carries grid coordinates, not pixels, so the SVG renderer can be swapped for canvas or a
CSS grid later without touching the backend — the brief's "renderer-agnostic" requirement.

`roomStatus` is a convenience roll-up for colouring the cell at a glance (`AVAILABLE` = no bed held,
`PARTIAL` = some beds held, `FULL` = all held, `BLOCKED` = all blocked); the authoritative per-bed
truth is always in `beds[]`.

**No student identity appears anywhere in this payload, for any of the three roles.** Admins get
identities from the queue endpoint, not the map — one fewer place to accidentally leak, and the
reason a GUEST can be handed this endpoint with no redaction logic at all.

### Student actions

```
POST /api/requests
Header: Idempotency-Key: 5f2c...  (UUID generated once per click by the frontend)
  → { "bedId": 4002 }
  ← 201 { "requestId": 91, "bedId": 4002, "roomNumber": 113, "bedLabel": "B",
          "status": "PENDING", "createdAt": "...", "expiresAt": "..." }
  ← 200 (same body) when the identical Idempotency-Key is replayed — a double-click is not an error
  ← 409 BED_NOT_AVAILABLE | STUDENT_ALREADY_HAS_CLAIM

DELETE /api/requests/{id}                            # student cancels their own PENDING request
  ← 200 { "requestId": 91, "status": "CANCELLED", "alreadyHandled": false }
  ← 200 { "requestId": 91, "status": "CANCELLED", "alreadyHandled": true }   # replay
  ← 409 REQUEST_ALREADY_RESOLVED { "actualStatus": "ALLOCATED" }
  ← 403 FORBIDDEN                                     # not the caller's request

GET /api/me/allocation                   # requires ALLOCATION_READ_OWN -> 403 for ADMIN and GUEST
← 200  one of:
  { "state": "NONE" }

  { "state": "PENDING",
    "request": { "requestId": 91, "roomNumber": 113, "bedLabel": "B",
                 "createdAt": "...", "expiresAt": "..." } }

  { "state": "ALLOCATED",
    "allocation": { "requestId": 91, "wing": "B", "floor": "FF", "roomNumber": 113,
                    "bedLabel": "B", "roomType": "Double", "bathroomType": "Attached",
                    "allocatedAt": "..." },
    "roommate": { "fullName": "Priya N", "course": "B.E. CSE" },   # ONLY when sibling bed ALLOCATED
    "roommateState": "ALLOCATED" }          # NONE | EMPTY | PENDING | BLOCKED | ALLOCATED
```

`roommate` is **absent** unless `roommateState == "ALLOCATED"`. `roommateState` exists so the UI can
say "the other bed is awaiting approval" without revealing who. For a Single room,
`roommateState: "NONE"`.

### Admin actions

```
GET /api/admin/requests?status=PENDING&page=0&size=25
← 200 { "total": 3, "rows": [
    { "requestId": 91, "student": { "id": 7, "fullName": "Asha R",
                                    "studentCode": "1MS22CS001", "course": "B.E. CSE" },
      "wing": "B", "floor": "FF", "roomNumber": 113, "bedLabel": "B",
      "createdAt": "...", "expiresAt": "...", "pendingAgeSeconds": 7412 } ] }

POST /api/admin/requests/{id}/approve
  ← 200 { "requestId": 91, "status": "ALLOCATED", "alreadyHandled": false }
  ← 200 { "requestId": 91, "status": "ALLOCATED", "alreadyHandled": true }    # second admin
  ← 409 REQUEST_ALREADY_RESOLVED { "actualStatus": "CANCELLED" }

POST /api/admin/requests/{id}/reject
  → { "reason": "Wing reserved for first-years" }
  ← 200 { "requestId": 91, "status": "REJECTED", "alreadyHandled": false }
  ← 200 { ... "alreadyHandled": true } | 409 REQUEST_ALREADY_RESOLVED

POST /api/admin/beds/{bedId}/block
  → { "reason": "Plumbing repair" }
  ← 200 { "bedId": 4002, "status": "BLOCKED",
          "autoRejectedRequestId": 91 }            # null when there was no pending request
  ← 409 BED_ALLOCATED_CANNOT_BLOCK
        { "message": "Bed 113-B is allocated. Removing an occupant is a separate eviction
                      workflow and is deliberately out of scope in this build." }

POST /api/admin/beds/{bedId}/unblock
  → { "reason": "Repair completed" }         # optional; body may be omitted entirely
  ← 200 { "bedId": 4002, "status": "AVAILABLE" }
  ← 409 BED_NOT_BLOCKED

GET /api/admin/occupancy
← 200 { "totals": { "AVAILABLE": 570, "PENDING": 3, "ALLOCATED": 2, "BLOCKED": 1 },
        "byWing": [ { "wing": "A", "totalBeds": 64,
                      "AVAILABLE": 64, "PENDING": 0, "ALLOCATED": 0, "BLOCKED": 0 }, ... ] }
```

`GET /api/health` (Phase 12) runs `SELECT 1` against the pool — a health check that can't pass while
the database is down.

### Two API conventions worth naming

1. **`alreadyHandled` instead of an error for a repeat of the same outcome.** Approving an
   already-`ALLOCATED` request is *not* a failure — the caller's intent is satisfied. Returning 200
   with `alreadyHandled: true` means a retrying client, a double-click, and a second admin all get a
   truthful answer. A *different* terminal state (approve on a `CANCELLED` request) is a genuine
   conflict and returns 409 with the actual status, so the admin sees what really happened.
2. **Permissions, not roles, in code.** `@PreAuthorize("hasAuthority('REQUEST_APPROVE')")`, never
   `if (user.getRole().equals("ADMIN"))`. GUEST is the proof: it was added to this contract as one
   row in the permission table and one authority set, touching **no** authorization call site. A
   warden role later is the same — a data change, not a grep.

---

## 5. WebSocket contract

Endpoint `/ws` (SockJS fallback enabled). STOMP with a simple in-memory broker:
`/topic` (broadcast) and `/queue` (per-user) destinations, `/app` for inbound — though this project
sends **nothing** inbound over STOMP. Every state change happens over REST; WebSocket is one-way,
server → client. Fewer authorization surfaces, and the REST call still works if the socket drops.

**Authentication:** the JWT travels in the STOMP `CONNECT` frame headers. A `ChannelInterceptor` on
the inbound channel validates it and attaches a `Principal` to the session. That `Principal` is what
makes `/user/...` destinations work at all.

### Channel 1 — public per-floor topic

**Destination:** `/topic/floors/{wing}-{floor}` — e.g. `/topic/floors/B-FF`, `/topic/floors/E-BAS`.
**Audience:** anyone (student or admin) currently viewing that floor. The client subscribes on
mount, unsubscribes when the wing/floor picker changes.

```json
{
  "event": "BED_STATUS_CHANGED",
  "bedId": 4002,
  "roomId": 113,
  "roomNumber": 113,
  "wing": "B",
  "floor": "FF",
  "bedLabel": "B",
  "status": "PENDING",
  "roomStatus": "PARTIAL",
  "cause": "REQUESTED",
  "at": "2026-08-09T12:41:07Z"
}
```

`cause` ∈ `REQUESTED | APPROVED | REJECTED | CANCELLED | EXPIRED | BLOCKED | UNBLOCKED` — enough for
the UI to animate or toast differently without a second fetch.

**This payload contains no `studentId`, no name, no request id.** That is the privacy rule enforced
at the schema of the message, not by remembering to redact: a student watching a floor learns that
bed 113-B became `PENDING`, never who made it pending. The DTO simply has no field for it.

### Channel 2 — private per-student queue

**Destination:** `/user/queue/requests`.

The `/user/` prefix is a Spring convention, and it's the part worth understanding: the server calls
`convertAndSendToUser(principalName, "/queue/requests", payload)`, and Spring rewrites the
destination to a session-specific one (`/queue/requests-user{sessionId}`) before it reaches the
broker. A client can only ever subscribe to *its own* `/user/queue/requests`. There is no destination
string a student could guess to eavesdrop on another student — the isolation is structural, the same
shape of argument as the partial unique index.

```json
{
  "event": "REQUEST_RESOLVED",
  "requestId": 91,
  "bedId": 4002,
  "wing": "B", "floor": "FF", "roomNumber": 113, "bedLabel": "B",
  "status": "ALLOCATED",
  "reason": null,
  "roommateVisible": true,
  "at": "2026-08-09T12:41:07Z"
}
```

- `status` ∈ `ALLOCATED | REJECTED | EXPIRED | CANCELLED`
- `reason` — the admin's rejection text, or the system reason on an auto-reject
  (`"Bed taken out of circulation for maintenance"`) or an expiry
  (`"No response within 48 hours"`). The student always learns *why*.
- `roommateVisible` — `true` only when this event is the moment both beds in a Double became
  `ALLOCATED`. The payload still carries **no roommate name**; it is a signal to refetch
  `GET /api/me/allocation`, which applies the visibility rule server-side. Names travel over the
  authenticated REST call, never over a broadcast-capable transport.

### Ordering with respect to the database

Both channels publish from a listener annotated
`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`. Services raise a plain Spring
application event inside the transaction; nothing reaches STOMP until the commit succeeds. On a
rollback the event is silently dropped — which is the correct behaviour, because nothing happened.

**Reconnect:** on `onConnect`, the client refetches the current floor and `/api/me/allocation`.
WebSocket is an optimisation for liveness, never the source of truth — a dropped socket costs you
freshness for a few seconds, never correctness.

---

## 6. Sequence descriptions

### 6.1 Happy path: request → approve

```
Student           API                    Postgres                     STOMP
  │ POST /requests  │                       │                            │
  │────────────────>│ BEGIN                 │                            │
  │                 │ INSERT bed_claims (PENDING, expires_at=now+48h)    │
  │                 │──────────────────────>│ index 1 slot taken         │
  │                 │                       │ index 2 slot taken         │
  │                 │ COMMIT ───────────────>│                           │
  │<─── 201 PENDING │                       │                            │
  │                 │ AFTER_COMMIT ─────────────────────────────────────>│ /topic/floors/B-FF
  │                 │                       │            {BED_STATUS_CHANGED, PENDING, REQUESTED}
Admin              │                       │                            │
  │ GET /admin/requests?status=PENDING      │                            │
  │<──── queue row incl. name, code, course, pendingAgeSeconds           │
  │ POST /approve   │                       │                            │
  │────────────────>│ BEGIN                 │                            │
  │                 │ UPDATE ... SET status='ALLOCATED'                  │
  │                 │   WHERE id=91 AND status='PENDING'   -> 1 row      │
  │                 │ COMMIT                │                            │
  │<─ 200 ALLOCATED │                       │                            │
  │                 │ AFTER_COMMIT ────────────────────────────────────> │ /topic/floors/B-FF
  │                 │                       │            {…, ALLOCATED, APPROVED}
  │                 │              ────────────────────────────────────> │ /user/queue/requests
  │                 │                       │            {REQUEST_RESOLVED, ALLOCATED}
```

The `expires_at` is set at insert time, not computed later, so the expiry sweep is a pure
`WHERE expires_at <= now()` scan against a partial index — no per-row arithmetic.

### 6.2 Case A — two students request the same bed at the same instant

1. Threads T1 (student S1) and T2 (student S2) both `POST /api/requests {bedId: 4002}`.
2. Both begin a transaction. Both read bed 4002's derived status and **both see `AVAILABLE`** — this
   is exactly the window a read-then-write check cannot close, no matter where the `if` is placed.
3. Both issue `INSERT INTO bed_claims (bed_id=4002, status='PENDING', ...)`.
4. Say T1's insert reaches the index first. It reserves the slot in `uq_claim_live_per_bed`.
5. T2's insert **blocks** — Postgres makes the second inserter wait on T1's transaction rather than
   failing immediately, because the outcome depends on whether T1 commits.
6. T1 commits. T2 wakes, discovers a committed conflicting row, and raises
   `SQLSTATE 23505 unique_violation`, naming the constraint `uq_claim_live_per_bed`.
7. Spring translates that to `DataIntegrityViolationException`. `ClaimService` inspects the
   **constraint name** — this is why the indexes have explicit names — and maps
   `uq_claim_live_per_bed → BED_NOT_AVAILABLE` and `uq_claim_live_per_student → STUDENT_ALREADY_HAS_CLAIM`.
   Two different rules, two different messages, one exception type.
8. S1 gets `201 PENDING`. S2 gets `409 BED_NOT_AVAILABLE`, "That bed was just taken."
9. Exactly one `AFTER_COMMIT` event fires, so the floor topic shows one transition, not two.

**Final state: exactly one PENDING row.** Not "usually one" — the guarantee is the index, and it holds
under any thread count, any number of backend instances, and default `READ COMMITTED` isolation.
No `SERIALIZABLE`, no advisory lock, no application mutex.

*Test (Phase 9):* `CountDownLatch` releasing N threads at once against a real Postgres
(Testcontainers, not H2 — H2 does not implement partial unique indexes, so testing this on H2 would
prove nothing). Assert exactly 1 `PENDING` row and N−1 conflict responses.

### 6.3 Case B — two admins approve the same request at the same instant

1. Admins A1 and A2 both `POST /api/admin/requests/91/approve`.
2. Neither service reads-then-writes. Each executes one conditional statement:
   ```sql
   UPDATE bed_claims
      SET status = 'ALLOCATED', decided_at = now(), decided_by = :adminId, expires_at = NULL
    WHERE id = :id AND status = 'PENDING';
   ```
3. A1 acquires the row lock and updates 1 row. A2 blocks on that same row lock.
4. A1 commits. Under `READ COMMITTED`, A2 re-evaluates its `WHERE` against the **new** row version,
   sees `status = 'ALLOCATED'`, and matches **0 rows**. No error, no exception — just zero.
5. Branch on the affected-row count:
   - `1` → `200 { status: "ALLOCATED", alreadyHandled: false }`, publish both events.
   - `0` → re-read the row:
     - now `ALLOCATED` → `200 { status: "ALLOCATED", alreadyHandled: true }`, **publish nothing**
       (the student must not be notified twice for one decision).
     - `REJECTED`/`CANCELLED`/`EXPIRED` → `409 REQUEST_ALREADY_RESOLVED` with `actualStatus`.
6. The same shape covers **student-cancels-while-admin-approves** (plan risk #3): cancel is
   `UPDATE ... SET status='CANCELLED', decided_at=now(), decided_by=:me, expires_at=NULL
   WHERE id=:id AND status='PENDING' AND student_id=:me`. Whichever transaction commits
   first wins; the other matches 0 rows and gets a truthful `409` naming the actual outcome. There is
   no ordering to get wrong, because the condition is evaluated by the database at commit time.
7. Index 1 remains the backstop: even a hypothetical bug that ran the approval twice could not create
   a second `ALLOCATED` row for that bed.

*Test (Phase 9):* two threads, one request id, latch-released. Assert exactly one response with
`alreadyHandled:false`, exactly one `ALLOCATED` row, and exactly one private WebSocket event.

### 6.4 Case C — the exact condition for roommate visibility

Room 113 is a Double: beds 113-A and 113-B. `GET /api/me/allocation` for the holder of 113-A:

1. Load the caller's live claim. Not `ALLOCATED` → return `NONE`/`PENDING`; the response DTO has no
   `roommate` field at all in those branches.
2. `ALLOCATED` → load the room. `capacity == 1` → `roommateState: "NONE"`. Done.
3. `capacity == 2` → find the sibling bed (same `room_id`, other `bed_label`) and its live claim:

   | sibling's live claim | `roommateState` | `roommate` |
   |---|---|---|
   | none | `EMPTY` | absent |
   | `PENDING` | `PENDING` | **absent** |
   | `BLOCKED` | `BLOCKED` | absent |
   | `ALLOCATED` | `ALLOCATED` | `{ fullName, course }` |

4. **`PENDING` is the case that matters.** A student could otherwise request the other bed purely to
   read a stranger's name, then cancel. Requiring `ALLOCATED` means an admin has affirmatively
   confirmed both people before either learns anything about the other.
5. The name and course come from a repository **projection** that selects only
   `full_name, course` — the `User` entity never reaches the DTO layer on this path, so no future
   refactor can accidentally widen it to email or student code. The privacy rule is enforced by what
   the query is capable of returning, not by remembering to strip fields.
6. **Symmetry and timing.** The rule is evaluated identically for both students, so both flip from
   hidden to visible at the same instant: the commit of the *second* approval. That approval's
   `AFTER_COMMIT` listener sends `REQUEST_RESOLVED{roommateVisible:true}` to the newly approved
   student and a `roommateVisible` nudge to the already-allocated one, and each client refetches
   `/api/me/allocation`. **The names themselves never travel over the socket** — only the signal to
   ask again over an authenticated REST call.
7. **Monotonic in this build.** An `ALLOCATED` bed cannot be cancelled, expired, or blocked (eviction
   is out of scope), so visibility, once granted, cannot flap. If eviction is added later, this is
   the sentence that has to be revisited — worth saying out loud rather than discovering it then.

### 6.5 Blocking a bed (with a pending request on it)

`POST /api/admin/beds/4002/block { "reason": "Plumbing repair" }`, one transaction:

1. Read the bed's derived status.
   - `ALLOCATED` → `409 BED_ALLOCATED_CANNOT_BLOCK`, explaining eviction is a separate workflow.
     (Explicit check purely so the message is good; index 1 is the race-proof backstop.)
   - `BLOCKED` → already blocked, `200 { alreadyHandled: true }`.
2. Auto-reject any pending request on that bed:
   ```sql
   UPDATE bed_claims
      SET status='REJECTED', decided_at=now(), decided_by=:admin,
          decision_reason='Bed taken out of circulation for maintenance', expires_at=NULL
    WHERE bed_id=:bedId AND status='PENDING'
   RETURNING id, student_id;
   ```
   This frees the slot in index 1 (and the student's slot in index 2, so they're immediately free to
   request elsewhere).
3. `INSERT INTO bed_claims (bed_id, student_id=NULL, status='BLOCKED', created_by=:admin,
   decision_reason=:reason)`. If a concurrent request slipped in between steps 2 and 3, this insert
   hits index 1 and raises `23505` → the whole transaction rolls back → `409 BED_NOT_AVAILABLE`,
   admin retries. Nothing half-applied, because steps 2 and 3 are one transaction.
4. `AFTER_COMMIT`: public `{BED_STATUS_CHANGED, BLOCKED, cause: BLOCKED}` on the floor topic; private
   `{REQUEST_RESOLVED, REJECTED, reason: "Bed taken out of circulation for maintenance"}` to the
   auto-rejected student, so they learn it from a notification rather than by noticing the map changed.

Unblock is the mirror image, and is audited the same way — an admin lifting a block (often because
the block was a mistake) is a decision someone has to own:

```sql
UPDATE bed_claims
   SET status='UNBLOCKED', decided_at=now(), decided_by=:adminId,
       decision_reason=:reason              -- optional, e.g. 'Repair completed'
 WHERE bed_id=:bedId AND status='BLOCKED';
```

0 rows → `409 BED_NOT_BLOCKED`. The `UNBLOCKED` row leaves the live set, the bed derives back to
`AVAILABLE`, and the block period stays in the table as history — with both endpoints of that
period attributable: `created_by`/`created_at` names the admin who blocked it, `decided_by`/
`decided_at` names the admin who lifted it. `ck_claims_decided` rejects the write if either is
missing, so an unaudited unblock is not something the service layer can produce even by accident.

### 6.6 Auto-expiry sweep

`@Scheduled(fixedDelayString = "${hostelops.expiry.sweep-interval:PT5M}")`, TTL from
`hostelops.request.ttl` (default `PT48H`), one statement:

```sql
UPDATE bed_claims
   SET status='EXPIRED', decided_at=now(),
       decision_reason='No response within 48 hours', expires_at=NULL
 WHERE status='PENDING' AND expires_at <= now()
RETURNING id, bed_id, student_id;
```

- Uses `idx_claims_pending_expiry`, which contains only pending rows — the sweep touches a handful of
  index entries, not 576 beds, however large the history grows.
- Safe with multiple backend instances for the same reason approval is: the `WHERE status='PENDING'`
  predicate is re-evaluated under row locks, so each row is claimed by exactly one transaction. No
  leader election, no distributed lock.
- `RETURNING` hands back exactly the rows this transaction expired, which is precisely the event set
  to publish after commit — public per floor, private per student.
- Nothing "unfreezes" a bed as a separate step: the row leaving the live set *is* the bed becoming
  `AVAILABLE`, because status is derived.
- `pendingAgeSeconds` on the admin queue makes the countdown visible, so an admin can see a request
  approaching expiry rather than being surprised by it.

---

## 7. What Phase 0 deliberately does not decide

- Visual design (palette, typography, logo) — after the workflow is correct.
- Redis (Phase 10) and k6 (Phase 11) are stretch. Core must be demoable first.
- Eviction of an `ALLOCATED` bed — out of scope, stated in an error message rather than hidden.
- Room *swap* / mutual-consent roommate pairing — out of scope; it is a different state machine.
