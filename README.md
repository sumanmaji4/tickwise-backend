# Tickwise: Support & SLA Desk (backend)

Frontend repository: https://github.com/sumanmaji4/tickwise

Companies receive support requests by email and web forms. Without a system, requests get lost,
nobody owns them, and SLA promises are breached silently. Tickwise turns every request into a
ticket with an owner. It tracks SLA deadlines in **business hours** (tenant timezone, working
hours, holidays, DST), warns at 80% and escalates on breach without polling every ticket, and
reports on performance.

> Portfolio project. The focus is correctness, tests and design rather than feature count.

## Contents

- [Features](#features)
- [Architecture](#architecture)
- [Project layout](#project-layout)
- [Quick start (Docker)](#quick-start-docker)
- [Local development](#local-development)
- [Using Supabase instead of local Postgres](#using-supabase-instead-of-local-postgres)
- [Testing](#testing)
- [API conventions](#api-conventions)
- [Configuration](#configuration)
- [Status and known limitations](#status-and-known-limitations)

## Features

| Area | What it does |
|---|---|
| **SLA engine** | Deadlines counted in business minutes, using the tenant's timezone, working hours and holidays, including DST changes and overnight shifts. The clock pauses while waiting on the customer, and a priority change recalculates deadlines. |
| **Escalation** | A WARNING at 80% of the SLA and a BREACH at the deadline, on a Redis delay queue. No cron scan of all tickets. Jobs are idempotent and re-check ticket state before acting. |
| **Assignment** | Round-robin within a team, skipping agents at capacity. Two agents can never own the same ticket: optimistic locking on the ticket, plus a row lock per team. |
| **Ticket intake** | A public web form (rate limited, honeypot) and a signed inbound-email webhook. Duplicates are detected by Message-ID, replies are threaded (reply token, then headers, then `[#id]`), auto-replies are filtered, and attachments go to S3/MinIO. |
| **Outbound email** | A transactional outbox delivered by the worker (Mailpit locally, Brevo in production). |
| **Real-time** | STOMP over WebSocket, scoped per tenant, fanned out through Redis pub/sub. |
| **Reporting** | First-response time, resolution time, breach rate and open tickets per tenant, team and agent. |
| **Onboarding** | Self-service org signup (tenant, first admin, default team, SLA defaults in one transaction). Admins invite teammates by email with single-use, expiring links; only a hash of each token is stored. |
| **Tickets** | Created by customers (web form, email) or by agents on a customer's behalf; status, priority and assignee changes; admin-only soft delete that keeps the audit trail. |
| **Multi-tenancy** | The tenant comes only from the JWT. Every query is tenant-scoped, and composite foreign keys stop cross-tenant references. |
| **Audit** | An append-only history of every status, priority, assignment and SLA change, enforced by a database trigger. |

### Screens (frontend)

1. Sign up (create a workspace), log in, accept an invitation
2. Agent ticket queue: filters, sort by SLA due, live countdown badges (green → yellow → red), "New ticket" form
3. Ticket detail: conversation, reply box, status, priority and assignee controls, SLA timers, audit log
4. Manager dashboard: breach rate, response times, workload per agent
5. Admin settings: business hours, holidays, SLA policies, teams, agent invitations, routing
6. Public support form (`/support/<workspace>`)

## Architecture

```
            ┌──────────── Next.js dashboard (client-side, TanStack Query, STOMP) ─────────────┐
            │                     REST (JWT)                 WebSocket /ws (JWT in CONNECT)   │
            ▼                                                                                 ▼
  ┌──────────────────── API process (Spring Boot, default profile) ───────────────────────────┐
  │ tenant · auth · ticket(+intake) · sla · assignment · email · notification · reporting     │
  └───────┬───────────────────────┬────────────────────────────┬──────────────────────────────┘
          │ PostgreSQL (Flyway)   │ Redis: delay queue ZSET,   │ MinIO / S3: attachments
          │ system of record      │ pub/sub, rate limits       │
  ┌───────┴───────────────────────┴────────────────────────────┴──────────────────────────────┐
  │ Worker process (same jar, `worker` profile): escalations, inbound-email queue, outbox     │
  └────────────────────────────────────────────────────────────────────────────────────────────┘
```

- **Modular monolith.** Entities and repositories are package-private per module. Modules talk
  through public services and domain events. `ArchitectureTest` (ArchUnit) fails the build on
  module cycles, cross-module repository or entity use, or impurity in the SLA core.
- **Frontend.** A pure client of the REST API, with no business logic or API routes in Next.js.

Design decisions are recorded as ADRs (in the shared `docs/` folder next to both repositories):

| ADR | Decision |
|---|---|
| [0001](../docs/adr/0001-modular-monolith.md) | Modular monolith with enforced module boundaries |
| [0002](../docs/adr/0002-multi-tenancy.md) | Shared-schema multi-tenancy, tenant from the token only |
| [0003](../docs/adr/0003-stateless-jwt-auth.md) | Stateless JWT via Spring Security's resource server |
| [0004](../docs/adr/0004-assignment-locking.md) | Optimistic locking on tickets, pessimistic lock per team |
| [0005](../docs/adr/0005-escalation-delay-queue.md) | SLA escalations on a Redis sorted-set delay queue |
| [0006](../docs/adr/0006-email-intake-and-threading.md) | Email intake, threading and loop prevention |
| [0007](../docs/adr/0007-realtime-and-reporting-read-model.md) | Real-time via Redis pub/sub; reporting read model |

SLA semantics (DST, pause, reprioritisation, boundaries): [docs/sla-engine.md](../docs/sla-engine.md)

## Project layout

The backend and the frontend are separate repositories that sit side by side. Shared files are
in the parent folder:

```
Tickwise/
├── backend/             Spring Boot API and worker   (this README: backend/README.md)
├── frontend/            Next.js 16 dashboard         (frontend/README.md)
├── docs/                ADRs and SLA engine semantics
├── load-tests/          k6 scripts
├── docker-compose.yml   postgres, redis, minio, mailpit, backend, worker, frontend
└── .env.example
```

**Backend modules** (`backend/src/main/java/com/tickwise/`):

| Package | Responsibility |
|---|---|
| `tenant` | Tenants, teams, users, staff directory |
| `auth` | Login, JWT, security rules, 401/403 problem responses |
| `sla` | Business-time calculator, SLA clocks, policies, calendar; `sla.escalation` holds the delay queue and worker |
| `ticket` | Tickets, messages, attachments, audit; `ticket.intake` holds threading and the public form |
| `assignment` | Round-robin, claim and assign, team locks |
| `email` | Webhook, inbound queue, auto-reply detection, outbox and senders |
| `notification` | In-app notifications; `notification.realtime` holds the WebSocket/STOMP side |
| `reporting` | Metrics projection and report queries |
| `shared` | Error format, request ids, principal, storage, rate limiter |

**Frontend** (`frontend/src/`): `app/` holds the routes (`(console)/tickets`, `dashboard`,
`admin`, `login`, `support/[tenantSlug]`), `lib/` holds the API client, auth, real-time, SLA
and time helpers, and `components/` holds the UI.

## Quick start (Docker)

From the parent `Tickwise/` folder:

```bash
cp .env.example .env        # then replace every change-me value
docker compose up --build
```

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| Public support form | http://localhost:3000/support/acme |
| API / Swagger UI | http://localhost:8080/swagger-ui.html |
| Worker health | http://localhost:8081/actuator/health |
| Mailpit (sent email) | http://localhost:8025 |
| MinIO console | http://localhost:9001 |

The API starts with the `seed` profile and creates two tenants. Each has an admin, a manager,
agents, routing, holidays and a queue of tickets in different states. Every demo user's
password is `TICKWISE_SEED_PASSWORD` from `.env`.

| Workspace | Timezone | Users |
|---|---|---|
| `acme` | America/New_York | `admin@`, `manager@`, `alex@`, `sam@`, `jordan@acme.test` |
| `globex` | Europe/Berlin | `admin@`, `manager@`, `riley@`, `taylor@globex.test` |

To simulate an inbound email (signed the way the provider signs it):

```bash
BODY='{"items":[{"MessageId":"<demo-1@mail.test>","From":{"Address":"pat@customer.test"},"To":[{"Address":"acme@inbound.tickwise.test"}],"Subject":"Printer on fire","RawTextBody":"Help!"}]}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$TICKWISE_WEBHOOK_SECRET" | sed 's/^.* //')
curl -s localhost:8080/webhooks/inbound-email -H 'Content-Type: application/json' -H "X-Tickwise-Signature: sha256=$SIG" -d "$BODY"
```

## Local development

Requirements: JDK 21, Node 24, Docker. You don't need to install Maven; use `./mvnw`.

```bash
docker compose up -d postgres redis mailpit minio

cd backend
# Put your settings in backend/.env (git-ignored, loaded automatically by Spring Boot);
# every variable is listed under Configuration below. Real environment variables override it.
./mvnw spring-boot:run -Dspring-boot.run.profiles=seed     # API on :8080
./mvnw spring-boot:run -Dspring-boot.run.profiles=worker   # worker on :8081 (second terminal)

cd ../frontend
npm install
npm run dev                                                # http://localhost:3000
```

The frontend reads the API address from `NEXT_PUBLIC_API_BASE_URL` (default
`http://localhost:8080`). The value is baked in at build time.

## Using Supabase instead of local Postgres

The backend works with any PostgreSQL. For Supabase, use the **Session pooler** connection
(IPv4, port 5432). The direct connection is IPv6-only, and the transaction pooler (port 6543)
breaks prepared statements and Flyway's migration lock.

```bash
TICKWISE_DB_URL=jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require
TICKWISE_DB_USER=postgres.<project-ref>
TICKWISE_DB_PASSWORD=<database password>
SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=5   # the API and the worker each open their own pool
```

Flyway creates the schema on first start. Redis, MinIO and Mailpit are still needed. The
integration tests always use their own throwaway Postgres container.

## Testing

```bash
cd backend
./mvnw test      # unit, web-slice and architecture tests (223): no Docker needed
./mvnw verify    # adds the Testcontainers integration tests (*IT): needs Docker

cd ../frontend
npm run lint && npm run build
```

| Suite | Proves |
|---|---|
| `BusinessTimeCalculatorTest`, `BusinessTimeScenarioTest`, `SlaClockTest` (109 tests) | UK bank holidays, a 60-working-day deadline, a six-day week, Sydney DST (25 h and 23 h days), split shifts; Business-time arithmetic: overnight, weekends, holidays, US and EU DST both ways, overnight shifts, exact boundaries, many-day deadlines; pause, resume, retarget and reopen; property tests over 4,000+ random cases |
| `ThreadResolverTest`, `EmailParsingTest` | Threading order and anti-hijack sender checks; auto-reply detection; reply-address parsing; HMAC verification (fail closed); provider payload mapping; HTML-to-text; filename sanitising |
| `RoundRobinTest`, `TicketStatusTest` | Rotation, capacity skipping, departed agents; the full status-transition matrix |
| `ApiSecurityWebMvcTest`, `JwtTokenServiceTest`, `RequestIdFilterTest` | 400/401/403/404/429 are all `application/problem+json` with a `requestId`; role rules; tenant from the token only; forged, expired and malformed tokens rejected |
| `ArchitectureTest` | No module cycles; no cross-module repository or entity use; pure SLA core |
| `TenantIsolationIT`, `TicketLifecycleIT`, `ReportingIT` | No cross-tenant reads or writes; the database rejects cross-tenant FKs; SLA effects of every lifecycle step; stale version gives 409; append-only audit; report numbers |
| `AssignmentConcurrencyIT` | 8 simultaneous claims → exactly 1 owner; concurrent auto-assign never exceeds capacity |
| `EscalationIT`, `RedisEscalationQueueIT` | Warning at 80%, breach at the deadline (controlled clock); idempotent redelivery; cancel, reschedule and reconciliation; lease semantics |
| `InboundEmailIT`, `PublicFormIT` | Webhook → queue → ticket with attachment; duplicates; threading; anti-hijack; auto-replies ignored; outbox; 429 with Retry-After; honeypot |
| `WebSocketIT` | Events reach the tenant's topic; other tenants' topics and bad tokens are rejected |
| `OnboardingIT` | Signup creates tenant, admin, team, routing and SLA defaults; invitations are single-use, replaceable, revocable and expire after 7 days; raw tokens are never stored and are scrubbed from the outbox after delivery |
| `TicketAdminIT` | Agent-created tickets (channel AGENT, team routing, audit actor); foreign teams rejected; admin-only soft delete hides the ticket from queue, reports and threading while keeping its audit trail |

### Load tests

k6 scripts for ticket creation and the inbound webhook are in [`../load-tests`](../load-tests/README.md).

| Script | Machine | Rate | p95 | p99 | Errors |
|---|---|---|---|---|---|
| `ticket-creation.js` | – | – | – | – | – |
| `inbound-webhook.js` | – | – | – | – | – |

Results are not recorded yet (see [Status](#status-and-known-limitations)).

## API conventions

- OpenAPI at `/v3/api-docs`, Swagger UI at `/swagger-ui.html`.
- Errors are RFC 7807 `application/problem+json` with a stable `type`
  (`urn:tickwise:problem:<slug>`), a `requestId`, `errors[]` for validation failures, and
  `Retry-After` on 429.
- Optimistic concurrency: `PATCH /api/tickets/{id}` takes the `version` the client saw; a
  stale version gives 409.
- Every response carries `X-Request-Id`. Logs include `requestId`, `tenantId` and `userId`;
  Compose emits ECS JSON logs.

## Configuration

All configuration comes from environment variables (see `.env.example` in the parent folder).
No secrets are committed.

| Variable | Default | Notes |
|---|---|---|
| `TICKWISE_DB_URL` / `_USER` / `_PASSWORD` | `jdbc:postgresql://localhost:5432/tickwise` / `tickwise` / – | |
| `TICKWISE_REDIS_HOST` / `_PORT` | `localhost` / `6379` | |
| `TICKWISE_JWT_SECRET` | – | **Required**, ≥ 32 characters |
| `TICKWISE_JWT_TTL` | `PT1H` | |
| `TICKWISE_CORS_ORIGINS` | `http://localhost:3000` | Also the allowed WebSocket origins |
| `TICKWISE_WEBHOOK_SECRET` / `_MODE` / `_HEADER` | – / `hmac` / `X-Tickwise-Signature` | Empty secret rejects every call |
| `TICKWISE_EMAIL_PROVIDER` | `log` | `log`, `mailpit` or `brevo` |
| `TICKWISE_BREVO_API_KEY`, `TICKWISE_EMAIL_FROM`, `TICKWISE_INBOUND_DOMAIN` | – / `support@tickwise.test` / `inbound.tickwise.test` | |
| `TICKWISE_S3_ENDPOINT` / `_BUCKET` / `_ACCESS_KEY` / `_SECRET_KEY` | `http://localhost:9000` / `tickwise-attachments` / – / – | |
| `TICKWISE_FORM_RATE_PER_CLIENT` / `_PER_TENANT` | `5` / `120` per minute | |
| `TICKWISE_SEED_PASSWORD` | – | Required with the `seed` profile |
| `TICKWISE_FRONTEND_URL` | `http://localhost:3000` | Base URL for invitation links |
| `NEXT_PUBLIC_API_BASE_URL` (frontend, build time) | `http://localhost:8080` | |

## Status and known limitations

| Check | State |
|---|---|
| Backend unit, web-slice and architecture tests (`./mvnw test`) | Passing (223 tests) |
| CI (GitHub Actions, `.github/workflows/ci.yml` in each repo) | Configured: the backend runs `./mvnw verify` including the Testcontainers ITs; the frontend runs lint, type-check, audit and build. Not yet run (needs a push to GitHub). |
| Frontend type-check, lint and production build | Passing |
| Testcontainers integration tests (`./mvnw verify`) | Written and compiling; will first run in CI (or locally with Docker) |
| Full stack via `docker compose up` | Not yet run |
| Frontend against a live API | Not yet exercised |
| k6 load-test results | Not yet recorded |

Known limitations:

- No refresh tokens or token revocation. Role changes and deactivation take effect when the
  token expires (1 hour by default).
- Admins can create teams and invite users, but cannot yet rename or delete teams, or edit or deactivate users.
- Soft-deleted tickets cannot be restored from the UI (the row and audit trail remain in the database).
- Agent replies can't carry attachments; only inbound email attachments are stored.
- Policy and calendar changes apply to running SLA clocks at their next transition, not
  retroactively.
- The MinIO image tag in `docker-compose.yml` is unverified (MinIO no longer publishes to
  Docker Hub). Override it with `MINIO_IMAGE` if the pull fails.
