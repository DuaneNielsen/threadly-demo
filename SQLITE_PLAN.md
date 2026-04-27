# Add SQLite to Threadly + threadly-payments — Build Plan

Written 2026-04-23. Read this, confirm open questions with the user, then execute.

## Goal

Give both Spring Boot services a **file-backed SQLite database** so orders and charges persist across restarts, the state is inspectable with `sqlite3`, and we get a realistic-feeling single-file DB (good for demos and for future bug planting around locking / concurrency).

## Why SQLite (vs the status quo)

Today:
- **Threadly** defaults to Postgres (requires `docker compose up` in `threadly/`). The `h2` profile is ephemeral — state is wiped on every restart (and `deploy.sh` restarts on every version change).
- **threadly-payments** is H2-only — also ephemeral.

Problems:
- After each `./deploy.sh v1.1`, the orders table is gone and the cart demo starts from zero.
- Postgres adds Docker as a hard dependency for the demo to have persistence.
- H2's behavior diverges from "real" SQL in subtle ways (good for tests, bad for showing a realistic bug surface).

SQLite splits the difference: one file, no extra process, persists across restarts, plays nicely with JPA, supports multi-reader, and brings its own quirks (single-writer, NUMERIC affinity, TEXT timestamps) that make it a richer bug-planting target.

## Architecture after the change

```
Threadly (8180) ------JPA------> /tmp/threadly.db   (sqlite file)
Payments (8181) ------JPA------> /tmp/payments.db   (sqlite file)
```

Default profile becomes `sqlite` for both services. Postgres (Threadly) and in-memory H2 (both) remain available as alternative profiles.

## Part 1 — Dependencies (both services)

Add to each `pom.xml`:

```xml
<dependency>
    <groupId>org.xerial</groupId>
    <artifactId>sqlite-jdbc</artifactId>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>org.hibernate.orm</groupId>
    <artifactId>hibernate-community-dialects</artifactId>
</dependency>
```

`hibernate-community-dialects` ships the maintained `org.hibernate.community.dialect.SQLiteDialect` — Hibernate 6 dropped the in-tree dialect.

Keep `com.h2database:h2` as `runtime` for tests + the `h2` profile. Keep `org.postgresql:postgresql` in Threadly for the existing Postgres profile.

## Part 2 — Threadly config

### `src/main/resources/application.properties`
Switch the default profile hints so the app boots on SQLite without `-Dspring.profiles.active=...`:

```properties
spring.application.name=threadly
server.port=${APP_PORT:8180}

# Default datasource = SQLite
spring.datasource.url=jdbc:sqlite:${THREADLY_DB:/tmp/threadly.db}
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false
spring.sql.init.mode=always
spring.jpa.defer-datasource-initialization=true

management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

### `src/main/resources/application-postgres.properties` (new)
Move the existing Postgres config from `application.properties` here so it stays reachable via `--spring.profiles.active=postgres`.

### `src/main/resources/application-h2.properties` (unchanged)
Leaves the H2 escape hatch for quick experiments.

### `src/main/resources/data.sql` (unchanged)
Still works. SQLite accepts the existing INSERTs. The `DELETE FROM products` at the top keeps the seed idempotent across restarts.

### JPA entity nits
- `BigDecimal` columns: SQLite uses NUMERIC affinity; Hibernate handles conversion, no code change needed.
- `Instant`/`java.time.*` columns: verify Hibernate writes as ISO-8601 TEXT (the default in community dialect). No entity changes expected.
- `@GeneratedValue(strategy = IDENTITY)`: SQLite supports `INTEGER PRIMARY KEY AUTOINCREMENT`; Hibernate issues the right DDL via the community dialect.

## Part 3 — threadly-payments config

Same shape as Threadly, simpler scope.

### `src/main/resources/application.properties`
```properties
spring.application.name=threadly-payments
server.port=${PAYMENTS_PORT:8181}

spring.datasource.url=jdbc:sqlite:${PAYMENTS_DB:/tmp/payments.db}
spring.datasource.driver-class-name=org.sqlite.JDBC
spring.jpa.database-platform=org.hibernate.community.dialect.SQLiteDialect

spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=false

management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always
```

### `src/main/resources/application-h2.properties` (new, optional)
Clone the existing in-line H2 config into a profile for tests that want in-memory. **Tests keep using H2 via `@ActiveProfiles("test")`** — don't pollute /tmp with a test DB file.

## Part 4 — Tests

Tests should stay on H2 in-memory — they're supposed to be fast and hermetic. Two places to adjust:

- **threadly `src/test/resources/application-test.properties`** — already forces H2 via explicit datasource URL. Keep as-is.
- **threadly-payments `src/test/resources/application-test.properties`** — create it (currently missing). Contents: H2 in-memory config identical to today's inline default.

Also add a smoke test per service asserting the DB file boot path doesn't explode:
- `ThreadlyApplicationBootTest` — `@SpringBootTest(webEnvironment = NONE)` with sqlite profile pointing at a `@TempDir` file. Asserts context loads and schema comes up.
- `PaymentsApplicationBootTest` — same shape.

## Part 5 — agentic-sre-demo wiring

### `.env` + `.env.example`
Add:
```
# SQLite DB files (set to blank string to use in-profile default)
THREADLY_DB=/tmp/threadly.db
PAYMENTS_DB=/tmp/payments.db
```

### `deploy.sh` + `deploy-payments.sh`
Pass the DB path through to the JVM:
```bash
java -jar "$ACTIVE_DIR/$JAR_NAME" \
    --server.port=$APP_PORT \
    --logging.file.name="$LOG_FILE" \
    --payments.url="$PAYMENTS_URL" \
    --spring.datasource.url="jdbc:sqlite:$THREADLY_DB" \
    ...
```

(Only add this line if we decide to expose DB path as a deploy-time knob; otherwise the app just picks up `THREADLY_DB` from the shell env via `${THREADLY_DB:/tmp/threadly.db}` placeholder in `application.properties`.)

**Decision:** rely on env-var substitution in `application.properties`. Deploy scripts already `source .env`, so `THREADLY_DB`/`PAYMENTS_DB` are already in the JVM's environment when the process starts. No deploy-script changes needed unless we want a `--reset-db` flag (see open questions).

### `setup.sh`
Add one more prerequisite check: `sqlite3` CLI (optional but handy for inspection). Skip if missing, don't hard-fail.

## Part 6 — Claude prompts

`CLAUDE_SRE_ANALYSIS_PHASE.md` + `CLAUDE_SRE_REMEDIATION_PHASE.md`:
- Update the per-service table: **Database column** becomes `SQLite (/tmp/threadly.db)` and `SQLite (/tmp/payments.db)`.
- Add a diagnostic hint: *"Run `sqlite3 /tmp/threadly.db '.tables'` or `'SELECT * FROM orders'` if the bug involves persisted state."*
- Add `Bash(sqlite3 *)` to the analysis-phase `--allowedTools` list so Claude can actually run those queries.

## Part 7 — Docs

Update `CLAUDE.md` + `README.md`:
- Architecture diagram gets two `sqlite` boxes hanging off each service.
- `.env` vars table gains `THREADLY_DB`, `PAYMENTS_DB`.
- Demo runbook teardown gains optional `rm /tmp/threadly.db /tmp/payments.db` if you want a clean slate.
- Note: the H2 and Postgres profiles still exist; SQLite is the new default.

## Part 8 — Task list

1. **Dependencies** — add `sqlite-jdbc` + `hibernate-community-dialects` to both `pom.xml`s
2. **Threadly `application.properties`** — switch default to SQLite, factor Postgres into `application-postgres.properties`
3. **threadly-payments `application.properties`** — switch default to SQLite, carve `application-h2.properties` out of the inline H2 config
4. **Smoke boot tests** — one per service, SQLite profile against a `@TempDir`-backed file
5. **Existing tests still green** — run `./mvnw test` in both repos, confirm H2 still works via the `test` profile
6. **Rebuild v1.0 + v1.1 JARs** for Threadly; rebuild v1.0 JAR for payments
7. **agentic-sre-demo .env/.env.example** — add `THREADLY_DB` + `PAYMENTS_DB`
8. **Claude prompts** — update DB references, add sqlite3 to allowedTools
9. **Docs** — CLAUDE.md, README.md
10. **End-to-end verification:**
    - `rm -f /tmp/threadly.db /tmp/payments.db`
    - `./deploy-payments.sh v1.0`
    - `./deploy.sh v1.1`
    - Place an order through the UI
    - Redeploy Threadly (`./deploy.sh v1.1` again) — confirm the order is still in `sqlite3 /tmp/threadly.db 'SELECT id,email,status FROM orders'`
    - Confirm the existing `/products/7` bug still trips the demo loop (parser fix + Fluent Bit stack still work unchanged)
11. **Commit** — one commit per repo with a focused message

## Part 9 — Decisions already made (don't re-ask user)

- **Scope:** both services get SQLite (symmetry; both also get tests).
- **Profile model:** SQLite becomes the new default profile; H2 and Postgres stay reachable for test / docker scenarios.
- **DB file location:** `/tmp/threadly.db` and `/tmp/payments.db` (mirrors the log-file convention, easy to `rm`, not polluting the repo).
- **Dialect:** `org.hibernate.community.dialect.SQLiteDialect` (official community artifact — not a random third-party fork).
- **Migrations:** stay on `ddl-auto=update`. No Flyway. This is a demo.
- **Seeding:** keep `data.sql`; the existing `DELETE FROM products; INSERT INTO products ...` is idempotent.
- **Tests:** stay on H2 in-memory via `application-test.properties`. SQLite profile gets one smoke test per service, no more.
- **No DB inspector container.** `sqlite3` CLI is enough; adding sqlite-web to docker-compose is scope creep.
- **JDBC URL template:** `jdbc:sqlite:${THREADLY_DB:/tmp/threadly.db}` (env var with inline fallback), same shape for payments.

## Part 10 — Open questions to confirm before building

1. **Reset-on-deploy?** Should `deploy.sh`/`deploy-payments.sh` delete the DB file as part of a deploy, mirroring how they truncate the log file today? Options:
   - (a) Never touch the DB — state persists across deploys (default assumption above)
   - (b) Always wipe — state resets per deploy (matches current behavior, loses the whole point of SQLite)
   - (c) Add a `--reset` flag: `./deploy.sh v1.1 --reset` wipes first
2. **Postgres stay or go?** Threadly currently has a full `docker-compose.yml` + Postgres driver for the default profile. Keep as an alt profile, or remove entirely and simplify the project?
3. **Persist across git tags?** If a deploy swaps JARs between v1.0 and v1.1, schema changes (hypothetical) would need to work both ways. For now v1.0 and v1.1 have **identical schemas**, so this is a non-issue — but worth flagging in case a future version adds a column.
4. **One shared file or two?** Two keeps service boundaries clean (today's default). A shared `/tmp/demo.db` would let us show a cross-service query on the dashboard. Probably stick with two.

## Part 11 — Files touched (summary)

### Modified
- `threadly/pom.xml` — add sqlite-jdbc, hibernate-community-dialects
- `threadly/src/main/resources/application.properties` — swap default to sqlite
- `threadly-payments/pom.xml` — same
- `threadly-payments/src/main/resources/application.properties` — swap default to sqlite
- `agentic-sre-demo/.env`, `.env.example` — add DB path vars
- `agentic-sre-demo/CLAUDE_SRE_ANALYSIS_PHASE.md` — DB ref + sqlite3 tool allow
- `agentic-sre-demo/CLAUDE_SRE_REMEDIATION_PHASE.md` — DB ref
- `agentic-sre-demo/CLAUDE.md`, `README.md` — docs

### New
- `threadly/src/main/resources/application-postgres.properties`
- `threadly-payments/src/main/resources/application-h2.properties`
- `threadly-payments/src/test/resources/application-test.properties`
- `threadly/src/test/java/.../ThreadlyApplicationBootTest.java`
- `threadly-payments/src/test/java/.../PaymentsApplicationBootTest.java`

### Rebuilt (JARs)
- `threadly/builds/v1.0/threadly.jar`
- `threadly/builds/v1.1/threadly.jar`
- `threadly-payments/builds/v1.0/threadly-payments.jar`

## Estimate
~1 hour for a fresh context, plus test time.
