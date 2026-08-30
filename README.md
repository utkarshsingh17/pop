# pop — AI Production Operations Platform

Answers operational questions like *"why is the order service slow?"* by investigating real
telemetry and producing a root-cause diagnosis with a remediation plan.

The point is that the model does not answer from its prompt. It is given **read-only tools** over
a database and a metrics backend, and it decides what evidence to gather — sweep metrics, notice
latency, sweep the database, spot a sequential scan, pull the execution plan, check the indexes,
then conclude. The evidence trail is recorded alongside the answer.

Built on Spring Boot 4.1 / Spring AI 2.0 / Java 21, with Claude (`claude-opus-5`) as the reasoning
engine.

---

## Architecture

Hexagonal — the domain is pure Java with no Spring, JPA, or HTTP anywhere near it.

```
domain/          Investigation aggregate, Finding, Diagnosis + ports.   No framework imports.
application/     Use cases, and the toolkit the agent calls.
infrastructure/  Adapters: JPA, Postgres, Prometheus, Spring AI, REST, MCP.
```

`InvestigatorPort` is the extension seam. Postgres, Prometheus and Actuator implement it; adding
logs, Kubernetes, or tracing means one new adapter and its tool methods, with no change to the
domain. `Finding` is the common currency that makes that work.

### Two databases, deliberately

| | pop's own database | The database under observation |
|---|---|---|
| Purpose | investigation history | the system being diagnosed |
| Access | read/write, Flyway-migrated | **read-only**, never migrated |
| Bean | `@Primary` `dataSource` | `targetDataSource` |

Boot's `DataSourceAutoConfiguration` backs off as soon as any `DataSource` bean exists, so both are
declared explicitly and the primary is marked `@Primary`. Without that, the read-only target becomes
the application's only datasource and Flyway tries to migrate the system you are observing.
`PopApplicationTests` asserts they stay distinct.

### Giving a language model database access

The agent can be asked to analyse SQL that arrived from a prompt, so there are three independent
barriers, and no single one is trusted:

1. **`SqlSafetyGuard`** — rejects anything not provably read-only. Literals and comments are masked
   by a hand-written scanner before analysis, so `SELECT 'DROP TABLE x'` is allowed while
   `SELECT 1 /* … */ ; DROP TABLE x` is not. It catches data-modifying CTEs, stacked statements,
   dollar-quote escapes, nested block comments, `SELECT … INTO`, and file/network-reaching
   functions. 67 adversarial tests cover it.
2. **A read-only database role** with no write privileges on the target.
3. **A read-only connection pool** with a `statement_timeout` and a row cap.

`EXPLAIN ANALYZE` *executes* the statement it explains, so it is off by default
(`pop.investigation.allow-explain-analyze`) rather than gated on the caller's intent.

pop never writes to the system it observes. Index suggestions are proposals for a human to run.

---

## Running it

Requires Docker, JDK 21, and an Anthropic API key.

```bash
docker compose up -d          # Postgres (+ pg_stat_statements) and Prometheus
export ANTHROPIC_API_KEY=sk-ant-...
./mvnw spring-boot:run
```

Compose seeds a `shop` database with a deliberately diagnosable fault: 400k orders and **no index
on `orders.customer_id`**, plus a workload that scans it. That is the problem the demo asks pop to
explain.

### Ask it something

```bash
curl -s localhost:8080/api/v1/investigations \
  -H 'Content-Type: application/json' \
  -d '{"question": "why is the order service slow?",
       "service": "order-service",
       "lookback": "PT1H"}' | jq
```

The response carries the diagnosis and every finding behind it:

```json
{
  "status": "COMPLETED",
  "highestSeverity": "HIGH",
  "diagnosis": {
    "probableRootCause": "Missing index on orders.customer_id",
    "confidence": "HIGH",
    "remediationSteps": [
      "CREATE INDEX CONCURRENTLY idx_orders_customer_id ON orders (customer_id);",
      "ANALYZE orders;"
    ]
  },
  "findings": [ { "source": "POSTGRES", "severity": "HIGH", "title": "Table 'orders' is scanned sequentially …" } ]
}
```

### Registering what to watch

What pop watches is registered over the API, not baked into configuration. A URL on its own is a
complete registration — the service name is derived from its host and port:

```bash
curl -s localhost:8080/api/v1/services \
  -H 'Content-Type: application/json' \
  -d '{"url": "http://localhost:3001"}' | jq
# -> registered as "localhost-3001", actuator at http://localhost:3001/actuator
```

Add a database when you want the Postgres investigator pointed at it too:

```bash
export POP_SECRET_KEY=$(openssl rand -base64 32)   # required before any password is stored

curl -s localhost:8080/api/v1/services \
  -H 'Content-Type: application/json' \
  -d '{"name": "order-service",
       "url": "http://localhost:3001",
       "jdbcUrl": "jdbc:postgresql://localhost:55432/shop",
       "username": "pop_readonly",
       "password": "pop_readonly"}' | jq

curl -s -X POST localhost:8080/api/v1/services/order-service/probe | jq
```

`http://host:3001` and `http://host:3001/actuator` are both accepted and normalise to the same
thing.

### Three sources, and what each is for

| | reads | answers |
|---|---|---|
| `sweep_runtime` | the service's own actuator | what is wrong **now**: heap, metaspace, threads, GC, CPU, pool, descriptors, failing health components |
| `sweep_metrics` | Prometheus | **when** it changed — p99, error rate, trends over a window |
| `sweep_database` | the registered database | why a query is slow — scans, locks, bloat, plans |
| `sweep_logs` | a file path, or the actuator logfile | **why it died** — fatal errors, ERROR rate, clustered exception types |

Actuator is read directly from the process, so it needs no scrape config and is live the moment you
register. It has no history, which is exactly what Prometheus is for; the system prompt tells the
agent to use them together. Every actuator finding carries a concrete next check — a heap near its
limit suggests `/actuator/heapdump`, blocked threads suggest `/actuator/threaddump`.

Then investigate it as before — `sweep_database` now runs against the registered database rather
than the configured one. A service that is *not* registered still falls back to
`pop.target-datasource.*`, so the demo works with no registration at all.

Passwords are encrypted with AES-GCM before they are stored and are never returned by any endpoint.
Without `pop.security.secret-key` set, a registration carrying a password is refused rather than
stored in the clear. Hosts are vetted at registration: link-local addresses (cloud instance
metadata), wildcard and multicast are rejected, and `pop.security.allowed-target-hosts` narrows it
to a known set.

| Endpoint | |
|---|---|
| `POST /api/v1/services` | register a service (a bare `{"url": …}` is enough); 201 |
| `GET /api/v1/services` | list registrations |
| `GET /api/v1/services/{name}` | fetch one |
| `PATCH /api/v1/services/{name}` | update coordinates, label, or enabled |
| `DELETE /api/v1/services/{name}` | deregister |
| `POST /api/v1/services/{name}/probe` | verify the credentials now |
| `POST /api/v1/investigations` | start one; returns 201 with the finished investigation |
| `GET /api/v1/investigations/{id}` | fetch one |
| `GET /api/v1/investigations?limit=20` | recent summaries |
| `GET /actuator/prometheus` | metrics, including `gen_ai.client.token.usage` |

Errors are RFC 9457 Problem Details.

### Driving it from Claude Code

The same capabilities are exposed over MCP, so an external agent can use pop's eyes directly.
`.mcp.json` points at `http://localhost:8080/mcp`; with the app running, ask Claude Code to
*"sweep the database for order-service"*.

Tools on both surfaces: `sweep_database`, `sweep_metrics`, `sweep_runtime`, `sweep_logs`,
`list_tables`, `describe_table`, `explain_query`, `suggest_indexes` (plus `evidence_so_far`
in-process).

---

## Tests

```bash
./mvnw clean verify              # 170 tests
./mvnw test -Dtest=SqlSafetyGuardTest
```

Pure-Java domain tests run in ~0.1s with no Spring context. Anything touching Postgres uses
Testcontainers rather than H2, because the schema is Postgres-specific and an in-memory substitute
would let real mapping drift pass. No test calls the Anthropic API.

## Configuration

| Property | Default | |
|---|---|---|
| `spring.ai.anthropic.api-key` | — | required |
| `spring.ai.anthropic.chat.model` | `claude-opus-5` | |
| `pop.target-datasource.*` | `localhost:5432/shop` | the observed database |
| `pop.target-datasource.statement-timeout` | `5s` | ceiling on every statement pop runs |
| `pop.target-datasource.max-pools` | `10` | registered databases held open at once |
| `pop.security.secret-key` | — | base64 AES key; required to store a registered password |
| `pop.security.allowed-target-hosts` | — | empty means any host but link-local/wildcard/multicast |
| `pop.security.allowed-log-dirs` | — | empty refuses file log sources; a path is read off pop's own disk |
| `pop.investigation.default-lookback` | `1h` | |
| `pop.investigation.allow-explain-analyze` | `false` | EXPLAIN ANALYZE executes the query |
| `spring.http.serviceclient.prometheus.base-url` | `localhost:9090` | |

Prompt and completion logging are off by default — query text and metrics are production data.

## Notes for future work

Logs (Loki) and Kubernetes were scoped out of this build. Both fit behind `InvestigatorPort`
without touching the domain. A Kubernetes adapter would be the first one that could *act* rather
than only observe, which needs an approval gate before any `kubectl` command runs — the current
design assumes every investigator is read-only.
