# HostelOps — Room Allocation & Approval System

A student requests a bed, an admin approves or rejects it, or an admin blocks a bed for maintenance.
One workflow, done correctly under concurrency, on the real floor plan of an actual hostel:
**404 rooms / 576 beds across 6 wings**.

The architecture contract this is built against — schema, API, WebSocket events, and the
concurrency proofs — is [`HOSTELOPS_PHASE0_ARCHITECTURE.md`](HOSTELOPS_PHASE0_ARCHITECTURE.md).

> **Current status: Phase 3 (floor map) complete.** Sign in, browse all 6 wings and 404 rooms,
> and see every bed's status colour-coded. Read-only — requesting a bed arrives in Phase 4.

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
| Later | WebSocket/STOMP (P7) · Redis, k6 (stretch) |

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

### Port already in use

`8080` (backend) and `5173` (frontend) are both pinned deliberately: Vite is set to `strictPort`, so
it fails loudly rather than silently moving to `5174`, which would break the backend's CORS
allow-list and produce a confusing browser-only error. Free the port, or change it in
`vite.config.ts` **and** `CORS_ALLOWED_ORIGINS`.

---

## Other commands

```bash
# Backend
cd backend
./mvnw test                  # unit + web-layer tests (no database required)
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
│           ├── db/migration/           V1__core_tables.sql, V2__bed_status_view.sql
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
| 3 | Floor map (read-only) | **done** |
| 4 | Request flow + the two partial unique indexes | next |
| 5 | Admin approve / reject / block | |
| 6 | Auto-expiry of stale requests | |
| 7 | WebSocket real-time updates | |
| 8 | Dashboards | |
| 9 | Testing, incl. the two concurrency proofs | |
| 10–11 | Stretch: Redis cache-aside, k6 load test | |
| 12–13 | Deployment, resume & interview prep | |
