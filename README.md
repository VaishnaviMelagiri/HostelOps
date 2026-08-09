# HostelOps — Room Allocation & Approval System

A student requests a bed, an admin approves or rejects it, or an admin blocks a bed for maintenance.
One workflow, done correctly under concurrency, on the real floor plan of an actual hostel:
**404 rooms / 576 beds across 6 wings**.

The architecture contract this is built against — schema, API, WebSocket events, and the
concurrency proofs — is [`HOSTELOPS_PHASE0_ARCHITECTURE.md`](HOSTELOPS_PHASE0_ARCHITECTURE.md).

> **Current status: Phase 1 (bootstrap) complete.** No features yet. What works today is the
> skeleton: a React app that talks to a Spring Boot API that talks to Postgres, and a health check
> that proves it. Authentication arrives in Phase 2.

---

## Stack

| | |
|---|---|
| Frontend | React 18 · Vite 5 · TypeScript 5 · Tailwind CSS 3 |
| Backend | Spring Boot 3.5 · Java 17 · Maven (wrapper) |
| Database | PostgreSQL 16 (Docker) · Flyway migrations |
| Later | Spring Security + JWT (P2) · WebSocket/STOMP (P7) · Redis, k6 (stretch) |

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

Expect this warning on startup — it is correct for Phase 1:

```
WARN o.f.core.internal.command.DbValidate : No migrations found. Are your locations set up correctly?
```

Flyway is wired up and connected, and there is simply no schema yet. `V1__core_tables.sql` lands in
Phase 2. See [`backend/src/main/resources/db/migration/README.md`](backend/src/main/resources/db/migration/README.md).

### 3 — Frontend

```bash
cd HostelOps/frontend
npm install
npm run dev
```

Open <http://localhost:5173>. You should see three green dots: frontend, backend, database.

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
│       │   ├── config/                 CORS now; security + WebSocket later
│       │   ├── health/                 the only working endpoint in Phase 1
│       │   ├── room/ claim/ occupancy/ realtime/ expiry/ seed/   (empty, Phases 2-7)
│       │   ├── security/ common/       (empty, Phase 2)
│       │   └── HostelOpsApplication.java
│       └── resources/
│           ├── application.yml
│           ├── db/migration/           Flyway — empty until Phase 2
│           └── seed/                   rooms.json (404) + wings-summary.json (6 wings)
└── frontend/
    └── src/
        ├── api/                        client.ts, health.ts, types.ts
        ├── auth/ realtime/ features/   (empty, Phases 2-8)
        ├── App.tsx                     the connectivity screen
        └── main.tsx
```

Empty directories are intentional — they are the Phase 0 folder contract, so each later phase adds
files rather than inventing a structure on the spot.

---

## Seed data

`backend/src/main/resources/seed/rooms.json` holds all 404 rooms, and
`wings-summary.json` the 6 wing definitions. Nothing loads them yet; the seed runner arrives with
the schema in Phase 2.

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
| 1 | Bootstrap — scaffolds, Postgres, health check | **done** |
| 2 | Auth + roles (Student, Admin, Guest) | next |
| 3 | Floor map (read-only) | |
| 4 | Request flow + the two partial unique indexes | |
| 5 | Admin approve / reject / block | |
| 6 | Auto-expiry of stale requests | |
| 7 | WebSocket real-time updates | |
| 8 | Dashboards | |
| 9 | Testing, incl. the two concurrency proofs | |
| 10–11 | Stretch: Redis cache-aside, k6 load test | |
| 12–13 | Deployment, resume & interview prep | |
