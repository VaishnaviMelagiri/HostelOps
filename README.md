# HostelOps — Room Allocation & Approval System

A student requests a bed, an admin approves or rejects it, or an admin blocks a bed for maintenance.
One workflow, done correctly under concurrency, on the real floor plan of an actual hostel:
**404 rooms / 576 beds across 6 wings**.

The architecture contract this is built against — schema, API, WebSocket events, and the
concurrency proofs — is [`HOSTELOPS_PHASE0_ARCHITECTURE.md`](HOSTELOPS_PHASE0_ARCHITECTURE.md).

> **Current status: Phase 9 (testing) complete.** 121 tests, including proof under real concurrency
> that two students cannot take the same bed and two admins cannot allocate the same one twice.
> Redis and k6 are optional stretch work from here.

## Demo accounts

Sign in at <http://localhost:5173> — one-click buttons for all three, no typing required.

| role | email | password | can do |
|---|---|---|---|
| Student | `student@hostelops.demo` | `Student@123` | browse the map, request a bed, cancel own request |
| Admin | `admin@hostelops.demo` | `Admin@123` | approve/reject requests, block beds, see occupancy |
| Guest | `guest@hostelops.demo` | `Guest@123` | read-only — browse only, changes nothing |

All three go through the **same** login endpoint with a real BCrypt password. There is no guest
mode, no anonymous bypass, and no separate admin login. Sign in as each and watch the permission
list on the home page change — no code anywhere branches on the role name.

---

## Stack

| | |
|---|---|
| Frontend | React 18 · Vite 6 · TypeScript 5 · Tailwind CSS 3 · React Router 6 |
| Backend | Spring Boot 3.5 · Java 17 · Spring Security + JWT (jjwt) · Maven (wrapper) |
| Database | PostgreSQL 16 (Docker) · Flyway migrations |
| Real-time | Spring WebSocket + STOMP · @stomp/stompjs |
| Later | Redis, k6 (stretch) |

---

## Prerequisites

- **JDK 17+** — `java -version`
- **Node 18+** — `node -v`
- **Docker + Compose v2** — `docker compose version`

Maven is *not* required: `./mvnw` downloads the right version on first use.

---

## Run it locally

Three terminals. Run them in this order.

### 1 — Database

```bash
cd HostelOps
docker compose up -d
docker compose ps          # wait until STATUS shows (healthy), not just Up
```

`Up` and `healthy` are not the same thing. Postgres starts, stops and restarts once during
first-time initialisation, so starting the backend against a merely-`Up` container is the classic
first-run failure.

### 2 — Backend

```bash
cd HostelOps/backend
./mvnw spring-boot:run
```

Serves <http://localhost:8080>. First run downloads Maven and the dependencies (a few minutes);
after that it starts in about 3 seconds.

On first startup against an empty database it applies `V1__core_tables.sql` and seeds the building
and the demo accounts:

```
Migrating schema "public" to version "1 - core tables"
Seeded 404 rooms and 576 beds (404 single-occupancy + 172 second beds in doubles)
Seeded demo STUDENT/ADMIN/GUEST accounts
```

Seeding is idempotent — later startups find the data already there and do nothing.

Also expect this warning until you set a signing key. It is correct locally and explained under
[Configuration](#configuration):

```
WARN c.hostelops.security.JwtService : JWT_SECRET is not set - falling back to the built-in
DEVELOPMENT signing key.
```

### 3 — Frontend

```bash
cd HostelOps/frontend
npm install
npm run dev
```

Open <http://localhost:5173>. You land on the login page — use a demo button.

---

## Verify without a browser

```bash
curl -s http://localhost:8080/api/health
# {"status":"UP","database":"UP","detail":"connection verified"}
```

Prove it is a real check rather than a hardcoded `200`:

```bash
docker compose stop db
curl -s -w '\nHTTP %{http_code}\n' http://localhost:8080/api/health
# {"status":"DOWN","database":"DOWN","detail":"CannotGetJdbcConnectionException: ..."}
# HTTP 503        ... in about 5 seconds

docker compose start db
curl -s http://localhost:8080/api/health     # UP again, no backend restart needed
```

The 5-second bound is deliberate. Hikari's default `connection-timeout` is 30 s, which makes the
endpoint hang for half a minute instead of reporting DOWN — long enough for a deployment platform's
health check to time out. `application.yml` lowers it, and `HealthService` additionally caps the
query itself at 3 s. Both clocks have to be bounded; setting only one still hangs.

### Auth, without a browser

```bash
B=http://localhost:8080

# Sign in. The token is a JWT: base64, readable by anyone, signed so nobody can alter it.
TOKEN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"guest@hostelops.demo","password":"Guest@123"}' | jq -r .accessToken)

curl -s $B/api/auth/me -H "Authorization: Bearer $TOKEN" | jq
# GUEST holds exactly one permission: ROOM_READ

# Prove logout is real, not just a client-side gesture:
curl -s -o /dev/null -w '%{http_code}\n' -X POST $B/api/auth/logout -H "Authorization: Bearer $TOKEN"  # 204
curl -s -o /dev/null -w '%{http_code}\n' $B/api/auth/me -H "Authorization: Bearer $TOKEN"              # 401
```

That last pair is the point of the revocation list. The token is still well-formed and still
minutes from expiry — it is refused because its `jti` is on the denylist. Without that, "log out"
would only delete the token from this browser and it would stay valid everywhere else.

An unknown email and a wrong password return the identical error, and take the same amount of time,
so login cannot be used to discover which addresses have accounts.

### Browsing the building

```bash
TOKEN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"guest@hostelops.demo","password":"Guest@123"}' | jq -r .accessToken)

curl -s $B/api/wings -H "Authorization: Bearer $TOKEN" | jq
# 6 wings, 404 rooms, 576 beds - all derived from the rooms table, not from wings-summary.json

curl -s "$B/api/wings/B/floors/FF/rooms" -H "Authorization: Bearer $TOKEN" | jq
# grid + one entry per room, each carrying its beds and their status

curl -s "$B/api/wings/A/floors/BAS/rooms" -H "Authorization: Bearer $TOKEN"
# 404 - wing A has no basement. Only wings E and F do.
```

Every bed reads `AVAILABLE` on a fresh database, because no requests exist yet. To see the other
colours before Phase 4 exists, insert a claim by hand:

```bash
docker exec hostelops-db psql -U hostelops -d hostelops -c "
INSERT INTO bed_claims (bed_id, student_id, status, created_by, expires_at)
SELECT b.id, 1, 'PENDING', 1, now() + interval '48 hours'
FROM beds b JOIN rooms r ON r.id = b.room_id
WHERE r.room_number = 113 AND b.bed_label = 'A';"

# remove all hand-inserted test claims again:
docker exec hostelops-db psql -U hostelops -d hostelops -c "DELETE FROM bed_claims;"
```

Nothing is cached: the next map request recomputes every status from `bed_claims` via the
`bed_status` view. That is the point of deriving status rather than storing it.

### Requesting a bed, and the two indexes

```bash
STUDENT=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"student@hostelops.demo","password":"Student@123"}' | jq -r .accessToken)

BED=$(docker exec hostelops-db psql -U hostelops -d hostelops -tAc \
  "SELECT b.id FROM beds b JOIN rooms r ON r.id=b.room_id WHERE r.room_number=113 AND b.bed_label='A';")

curl -s -X POST $B/api/requests -H "Authorization: Bearer $STUDENT" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $(uuidgen)" \
  -d "{\"bedId\":$BED}" | jq          # 201, status PENDING

curl -s $B/api/me/allocation -H "Authorization: Bearer $STUDENT" | jq
```

**Index 2 — one live claim per student.** Ask for a second, different bed:

```
409  {"code":"STUDENT_ALREADY_HAS_CLAIM", ...}
```

**Index 1 — one live claim per bed.** A different student asking for the same bed:

```
409  {"code":"BED_NOT_AVAILABLE","message":"That bed was just taken. Pick another one."}
```

Neither rejection comes from an `if` in the service. There is no "is this bed free?" check anywhere
in the request path, deliberately: between such a check and the insert, another transaction can slip
in, so the check would pass and the insert would fail anyway — it would only move the failure
somewhere less obvious while creating the illusion of safety. The unique index is evaluated at the
moment of writing, which is the only moment that can be authoritative.

**Idempotency.** The same `Idempotency-Key` twice returns `201` then `200`, and writes one row. A
double-clicked button is not two requests.

**Cancelling** frees both the bed and the student, and keeps the row as history:

```bash
curl -s -X DELETE $B/api/requests/<id> -H "Authorization: Bearer $STUDENT"   # 200, alreadyHandled:false
curl -s -X DELETE $B/api/requests/<id> -H "Authorization: Bearer $STUDENT"   # 200, alreadyHandled:true
```

Nothing has to "unstick" the bed. The cancelled row simply drops out of the index's `WHERE` clause,
and the status is derived fresh from whatever rows remain.

### Admin: approving, rejecting, blocking

```bash
ADMIN=$(curl -s -X POST $B/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"admin@hostelops.demo","password":"Admin@123"}' | jq -r .accessToken)

# The queue, oldest first - the only endpoint in the API that shows one person's identity
# to another, because an admin cannot approve a blank request.
curl -s $B/api/admin/requests -H "Authorization: Bearer $ADMIN" | jq

curl -s -X POST $B/api/admin/requests/<id>/approve -H "Authorization: Bearer $ADMIN"
# {"requestId":17,"status":"ALLOCATED","alreadyHandled":false}
```

**Idempotency.** Click approve again:

```
{"requestId":17,"status":"ALLOCATED","alreadyHandled":true}      200, still one row
```

Not an error — the outcome the caller wanted has happened. But a *different* terminal state is a
real conflict, and says so:

```
POST .../reject on an already-allocated request
409  {"code":"REQUEST_ALREADY_RESOLVED","details":{"actualStatus":"ALLOCATED"}}
```

This is what answers *"how do you stop two admins allocating the same room?"* — and note it is **not**
the partial unique index. That stops two claims existing on one bed; it says nothing about one claim
being resolved twice, because approval is an `UPDATE`, not an `INSERT`. What makes approval safe is
the conditional update `WHERE id = ? AND status = 'PENDING'` plus a branch on the affected-row
count. Both mechanisms exist and they answer different questions.

**Blocking a bed for maintenance:**

```bash
curl -s -X POST $B/api/admin/beds/<bedId>/block -H "Authorization: Bearer $ADMIN" \
  -H 'Content-Type: application/json' -d '{"reason":"Ceiling leak"}'
```

If the bed had a pending request, it is auto-rejected in the same transaction and the student is
told why, rather than left waiting on a bed that will never be approved:

```
{"status":"BLOCKED","autoRejectedRequestId":18,...}
# that request -> REJECTED, reason "Bed taken out of circulation for maintenance"
# the student is immediately free to request elsewhere
```

Blocking an **allocated** bed is refused, with the reason stated rather than failing silently:

```
409  {"code":"BED_ALLOCATED_CANNOT_BLOCK","message":"Bed 113-A is allocated to a student.
      Removing an occupant is a separate eviction workflow and is deliberately out of scope..."}
```

`unblock` returns the bed to `AVAILABLE`. The block period stays in the table, attributable at both
ends: `created_by` names the admin who blocked it, `decided_by` the one who lifted it.

### Auto-expiry

A `PENDING` request holds a bed — the partial unique index makes it unavailable to everyone else for
as long as the row is live. Without expiry, one admin going on holiday would take beds out of
circulation indefinitely, and the mechanism that prevents double-booking would become the thing
freezing the building. So a request that nobody answers lapses on its own.

Default TTL is 48 hours. To watch it happen instead of waiting two days:

```bash
HOSTELOPS_REQUEST_TTL=PT20S HOSTELOPS_EXPIRY_SWEEP_INTERVAL=PT5S ./mvnw spring-boot:run
```

Request a bed, wait half a minute, and the log shows:

```
Expired 1 stale request(s) after PT20S without a decision
```

The bed reads `AVAILABLE` again, the student is free to request elsewhere, and the row records what
happened:

```
status=EXPIRED  decided_at=<time>  decided_by=NULL  reason="No response from the hostel office in time"
```

`decided_by` is deliberately `NULL`. Expiry is the one transition no human performs, and
`ck_claims_decided` encodes exactly that: `EXPIRED` requires a `decided_at` and *forbids* a
`decided_by`. Inventing a "system user" to fill the column would put a fake row in the users table
forever.

Two properties worth knowing:

- **Safe on several instances at once.** The sweep reads candidate ids, then expires each one with
  `WHERE status = 'PENDING'`. If another instance's sweep — or an admin approving — got there first,
  the update changes nothing. No leader election, no distributed lock: the same conditional-update
  idea that makes approve and cancel safe, applied to a background job.
- **The interval affects promptness, not correctness.** Each request carries its own `expires_at`,
  so a sweep that runs late expires exactly the same rows, just later.

The admin queue shows each request's age and time remaining, turning amber within six hours of the
deadline and red once past it — so a request about to be swept away is visible rather than vanishing
mid-read.

### Dashboards

Signing in lands each role on a different home, chosen by **what the account may do**, not what it
is called — so a future warden role that can read the queue would land on the admin dashboard with
no code change.

**Student:** the bed they hold, or the request they are waiting on (with its expiry countdown and a
cancel button), or a prompt to go and find one. Plus the roommate card.

**Admin:** the live pending queue, occupancy across all six wings, and the beds out of service with
a way to return them. Blocking happens on the map — the map already answers "which beds are free"
far better than a room-number text box, and duplicating it here would mean a second, worse bed
picker to keep in step.

### Roommate visibility

The most privacy-sensitive rule in the project. A name and course appear only once **both** beds in
a Double are `ALLOCATED`:

| the other bed | what you are told | name shown |
|---|---|---|
| free | "Free — nobody has requested it yet" | no |
| **requested, awaiting approval** | "Someone has requested it" | **no** |
| out of service | "Out of service for maintenance" | no |
| allocated | name + course | **yes** |

The middle row is the reason the rule exists. If a name showed there, any student could request the
free bed in a room, read the occupant's name, and cancel — turning the map into a directory of who
lives where. Requiring `ALLOCATED` means an admin has affirmatively confirmed both people first.

Verified end to end: with the neighbour merely `PENDING`, the response contains no name, no course,
no student code, no email. Once approved, both students see each other — simultaneously, because
the rule is recomputed from the same data for each of them rather than stored as a "revealed" flag
that could be set for one and not the other.

Two fields reach the browser, and the query is physically incapable of returning more: it selects
`full_name, course` only, so the `User` entity never reaches that layer and no later refactor can
widen it to an email address by accident.

### Real-time updates

**The two-tab demo.** Sign in as the student in one browser tab and the admin in another (use a
private window for the second, since the token is per-tab). Put the student on the floor map and
the admin on the pending queue:

1. Student requests a bed → the bed turns amber **on both tabs at once**.
2. Admin approves → the bed turns blue on both, and a banner appears on the student's tab:
   *"Approved — bed 113-A is yours."*
3. Admin blocks a bed → it turns red everywhere instantly.

Nobody refreshes anything. There is a small `live` dot in the map header showing the connection
state; if the socket drops it reads `reconnecting…`, and the page refetches when it comes back.

**Two channels, two audiences:**

| destination | who receives it | carries |
|---|---|---|
| `/topic/floors/{wing}-{floor}` | anyone browsing that floor | bed status changes |
| `/user/queue/requests` | one student, their own requests only | approved / rejected / expired |
| `/topic/admin/queue` | ADMIN only | a signal that the queue moved — nothing else |

The public message has **no student field of any kind** — not omitted at send time, but absent from
the type, so there is nothing capable of carrying an identity to a broadcast audience. The private
message carries no roommate name either; when Phase 8 adds roommate visibility it will send a flag
telling the client to refetch over authenticated REST, because names should not travel on a
transport whose job is broadcasting.

**Per-user isolation is structural, and was verified rather than assumed.** Two students both
subscribed to the identical string `/user/queue/requests`; Spring rewrites it per session before it
reaches the broker. The bystander received both public messages and **zero** private ones. There is
no destination another student could guess.

**Every message is published after the database commit**, via
`@TransactionalEventListener(phase = AFTER_COMMIT)`. Publishing inside the transaction would mean a
later rollback leaves every connected browser showing a bed status that does not exist — with
nothing to ever correct it, because a rollback is silent. `RealtimeEventTest` proves it: a request
that loses the unique-index race announces nothing at all.

WebSocket is an optimisation for liveness, never the source of truth. Every action still refetches,
a reconnect refetches, and the REST API works unchanged if the socket never connects at all.

**The third channel** closes the gap where the "no refreshing" claim did not hold for an admin
sitting on the queue page rather than the map. `/topic/admin/queue` is **signal-only**: the payload
is an event name, a cause and a timestamp. No student, no request id, not even a bed. The page
reacts by refetching `GET /api/admin/requests`, which is permission-checked and is the single place
identities are disclosed — so a broadcast topic never carries a name even to admins. Same pattern as
the roommate signal: the socket carries the nudge, the authenticated request carries the data.

It is the only destination gated by role, checked at SUBSCRIBE in the STOMP interceptor. Verified
live:

```
STUDENT  -> REFUSED
GUEST    -> REFUSED
ADMIN    -> subscribed
```

The floor topics need no gating (identity-free by construction) and `/user/queue/**` needs none
either (Spring rewrites it per session, so it cannot be addressed to anyone else).

---

## Tests

```bash
cd backend && ./mvnw test        # 121 tests, ~40s once the container is up
```

| area | tests | what is proven |
|---|---|---|
| Concurrency | 20 | the two required proofs, plus six more races |
| Auth & permissions | 35 | sign-in rules, token lifecycle, the permission matrix, subscribe gating |
| Request / cancel | 23 | the happy path, and every authorization and error branch |
| Approve / reject | 22 | idempotent repeats vs genuine conflicts, audit trail integrity |
| Expiry | 9 | overdue freed, allocations never touched, safe on several instances |
| Roommate privacy | 7 | a name appears only when both beds are confirmed |
| Map & real-time | 23 | grid layout, identity-free payloads, after-commit publication |

**The two required concurrency proofs:**

```
12 students request the same bed simultaneously -> exactly one PENDING row
 8 admins approve the same request simultaneously -> exactly one ALLOCATED row
```

Both assert the database state directly, not just the API responses — so even a bug in the service
layer could not make them pass. Six further races are covered: one student firing eight requests at
eight beds, approve versus reject, cancel versus approve, approve versus the expiry sweep, two
expiry sweeps at once, and a repeated Idempotency-Key.

**Everything DB-backed runs against real PostgreSQL** via Testcontainers, never H2. H2 does not
implement partial unique indexes, so it would create the tables, silently skip the index, pass
every test and prove nothing — false confidence being worse than no test.

---

## Troubleshooting

### `npm run dev` fails with `ENOSPC: System limit for number of file watchers reached`

Not a disk-space problem despite the name, and not a bug in this project. Vite watches your source
files for hot reload using Linux **inotify**, and the system-wide watcher budget is already spent —
on this machine VS Code alone holds ~65,000 of the 65,536 available.

Check who is using them:

```bash
cat /proc/sys/fs/inotify/max_user_watches      # the budget, usually 65536
```

Pick one fix:

**1. Raise the limit (best; needs sudo, survives reboot)**

```bash
echo 'fs.inotify.max_user_watches=524288' | sudo tee /etc/sysctl.d/60-inotify.conf
sudo sysctl --system
```

**2. Stop VS Code from watching junk** — add to VS Code `settings.json`:

```json
"files.watcherExclude": {
  "**/node_modules/**": true,
  "**/target/**": true,
  "**/.git/objects/**": true
}
```

**3. Fall back to polling** — no root needed, works immediately, costs some CPU (noticeable on a
low-powered laptop, so prefer 1 or 2):

```bash
CHOKIDAR_USEPOLLING=true npm run dev
```

### Backend starts but `/api/health` reports DOWN

Postgres is not reachable. `docker compose ps` — the container must say `(healthy)`, not just `Up`.

### `./mvnw spring-boot:run` exits 1 with "Port 8080 was already in use"

Almost always an earlier backend still running — often one you thought you had stopped. Note that
`spring-boot:run` **forks a second JVM**: killing the Maven process leaves the actual application
running and still holding the port, which is why this comes back after a Ctrl-C that looked like it
worked.

Find and stop whatever holds the port:

```bash
ss -ltnp | grep ':8080'                 # shows pid=NNNN
kill <pid>
```

Or in one line:

```bash
kill $(ss -ltnp | grep ':8080' | grep -oP 'pid=\K[0-9]+' | head -1)
```

Then start again. The app itself is fine — the failure happens at the very end of startup, when
Tomcat tries to bind the port, so everything before it (Flyway, seeding) has already succeeded.

### Port already in use — frontend

`8080` (backend) and `5173` (frontend) are both pinned deliberately: Vite is set to `strictPort`, so
it fails loudly rather than silently moving to `5174`, which would break the backend's CORS
allow-list and produce a confusing browser-only error. Free the port, or change it in
`vite.config.ts` **and** `CORS_ALLOWED_ORIGINS`.

---

## Other commands

```bash
# Backend
cd backend
./mvnw test                  # 121 tests. Needs Docker: the concurrency tests run against a
                             # real Postgres via Testcontainers, because H2 has no partial indexes
./mvnw clean package         # build the executable jar into target/

# Frontend
cd frontend
npm run build                # type-check + production build
npm run typecheck            # types only

# Database
docker compose logs -f db
docker compose down          # stop, keep data
docker compose down -v       # stop and DELETE data (full reset)
psql postgresql://hostelops:hostelops@localhost:5432/hostelops   # if psql is installed
```

---

## Configuration

Both sides read environment variables and fall back to defaults that match `docker-compose.yml`, so
**local development needs no `.env` file at all**. The `.env.example` files document what production
will need in Phase 12.

| | file | notes |
|---|---|---|
| Backend | [`backend/.env.example`](backend/.env.example) | Spring does not read `.env` automatically — either `set -a; source .env; set +a` or rely on the defaults |
| Frontend | [`frontend/.env.example`](frontend/.env.example) | copy to `.env.local`; only `VITE_`-prefixed variables reach browser code, and everything there ships to the browser, so **never put a secret in it** |

### The JWT signing key

Left unset, the backend falls back to a signing key hardcoded in `JwtService.java` and warns loudly
on every startup. That key is in the repository, so it is public, so tokens signed with it can be
forged by anyone — fine on a laptop, never in a deployment. The alternative (a random key per
startup) would sign you out on every restart, which during development is pure friction.

```bash
JWT_SECRET=$(openssl rand -base64 48)     # at least 32 bytes; shorter fails at startup
```

Tokens live 15 minutes by default (`JWT_ACCESS_TOKEN_TTL`). Short lifetime bounds the damage from a
leaked token; the revocation list closes the window immediately on logout. Both are needed —
neither alone is enough.

### Where the token lives in the browser

`sessionStorage`, so a page refresh does not sign you out.

The stricter-sounding alternative — keeping it only in a JavaScript variable — does not actually
buy security. A script running in the page can reach the token either way: from `sessionStorage`
it is one synchronous read, from a module variable it means patching `fetch` and waiting for the
next request. An extra step, not a barrier. What limits the damage is the 15-minute lifetime and
server-side revocation, both of which are built.

`sessionStorage` rather than `localStorage` is a real distinction: it is scoped to one tab and
cleared when that tab closes, so a token cannot outlive the session on a shared machine.

The genuine upgrade is an httpOnly refresh cookie, which no script can read at all. Out of scope
here — it needs CSRF protection and refresh-token rotation.

### A note on JDK versions on this machine

`java` on the `PATH` is JDK 17, but `JAVA_HOME` points at SDKMAN's JDK 21, so `./mvnw` builds with
21. That is harmless — `<java.version>17</java.version>` in `pom.xml` sets the compiler *release*
level, so the output is genuine Java 17 bytecode (verified: class file major version 61) and will
run on a Java 17 runtime. For exact parity with the deploy target:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./mvnw spring-boot:run
```

---

## Layout

```
HostelOps/
├── docker-compose.yml                  Postgres 16 only; app + frontend run on the host
├── HOSTELOPS_PHASE0_ARCHITECTURE.md    the contract every phase builds against
├── backend/
│   ├── mvnw, .mvn/                     Maven wrapper — no local Maven needed
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/hostelops/         packaged by feature, not by layer
│       │   ├── auth/                   login, logout, /me + DTOs
│       │   ├── common/                 ErrorCode, ApiError, GlobalExceptionHandler
│       │   ├── config/                 SecurityConfig, CorsConfig, SchedulingConfig
│       │   ├── health/                 GET /api/health
│       │   ├── security/               JWT, permissions, revocation list
│       │   ├── seed/                   SeedRunner — building + demo accounts
│       │   ├── user/                   User entity, Role, repository
│       │   ├── room/ claim/ occupancy/ realtime/ expiry/   (empty, Phases 3-7)
│       │   └── HostelOpsApplication.java
│       └── resources/
│           ├── application.yml
│           ├── db/migration/           V1 tables · V2 bed_status view · V3 the two indexes
│           └── seed/                   rooms.json (404) + wings-summary.json (6 wings)
└── frontend/
    └── src/
        ├── api/                        client.ts, auth.ts, health.ts, types.ts
        ├── auth/                       AuthContext, LoginPage, DemoAccountButtons,
        │                               ProtectedRoute, useAuth, usePermission
        ├── features/home/              signed-in landing page
        ├── features/map/               SVG floor map, wing/floor picker, legend
        ├── features/student|admin/     (empty, Phases 4-8)
        ├── realtime/                   (empty, Phase 7)
        ├── routes.tsx  App.tsx  main.tsx
```

Empty directories are intentional — they are the Phase 0 folder contract, so each later phase adds
files rather than inventing a structure on the spot.

---

## Seed data

`backend/src/main/resources/seed/rooms.json` holds all 404 rooms and `wings-summary.json` the 6 wing
definitions. `SeedRunner` loads them on first startup, deriving beds from room capacity: every room
gets bed `A`, and every Double additionally gets `B`.

| wing | rooms | type | bathroom | floors | beds |
|---|---|---|---|---|---|
| A | 64 | Single | Attached | GF FF SF TF | 64 |
| B | 48 | Double | Attached | GF FF SF TF | 96 |
| C | 64 | Double | Common | GF FF SF TF | 128 |
| D | 88 | Single | Common | GF FF SF TF | 88 |
| E | 80 | Single | Attached | BAS GF FF SF TF | 80 |
| F | 60 | Double | Attached | BAS GF FF SF TF | 120 |
| | **404** | | | | **576** |

One quirk of the real building that the code has to respect: `floor_level` orders floors *within a
wing*, and wings do not agree on what level 0 means. Wings A–D start at `GF = 0`; wings E–F have a
basement, so `BAS = 0` and `GF = 1`. Never compare `floor_level` across wings.

---

## Phase plan

| | phase | status |
|---|---|---|
| 0 | Architecture contract | done |
| 1 | Bootstrap — scaffolds, Postgres, health check | done |
| 2 | Auth + roles (Student, Admin, Guest) | done |
| 3 | Floor map (read-only) | done |
| 4 | Request flow + the two partial unique indexes | done |
| 5 | Admin approve / reject / block | done |
| 6 | Auto-expiry of stale requests | done |
| 7 | WebSocket real-time updates | done |
| 8 | Dashboards | done |
| 9 | Testing, incl. the two concurrency proofs | **done** |
| 10–11 | Stretch: Redis cache-aside, k6 load test | optional |
| 12–13 | Deployment, resume & interview prep | |
