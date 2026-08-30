# pop — AI Production Operations Platform

Answers operational questions like *"why is the order service slow?"* by investigating real
telemetry and producing a root-cause diagnosis with a remediation plan.

The point is that the model does not answer from its prompt. It is given **read-only tools** over
a database and a metrics backend, and it decides what evidence to gather — sweep metrics, notice
latency, sweep the database, spot a sequential scan, pull the execution plan, check the indexes,
then conclude. The evidence trail is recorded alongside the answer.

Built on Spring Boot 4.1 / Spring AI 2.0 / Java 25, with Claude (`claude-opus-5`) as the reasoning
engine.

---

## Architecture

Hexagonal — the domain is pure Java with no Spring, JPA, or HTTP anywhere near it.

```
domain/          Investigation aggregate, Finding, Diagnosis + ports.   No framework imports.
application/     Use cases, and the toolkit the agent calls.
infrastructure/  Adapters: JPA, Postgres, Prometheus, Spring AI, REST, MCP.
```

`InvestigatorPort` is the extension seam. Postgres and Prometheus implement it today; adding logs,
Kubernetes, or tracing means one new adapter and its tool methods, with no change to the domain.
`Finding` is the common currency that makes that work.

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

Requires Docker, JDK 25, and an Anthropic API key.

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

| Endpoint | |
|---|---|
| `POST /api/v1/investigations` | start one; returns 201 with the finished investigation |
| `GET /api/v1/investigations/{id}` | fetch one |
| `GET /api/v1/investigations?limit=20` | recent summaries |
| `GET /actuator/prometheus` | metrics, including `gen_ai.client.token.usage` |

Errors are RFC 9457 Problem Details.

### Driving it from Claude Code

The same capabilities are exposed over MCP, so an external agent can use pop's eyes directly.
`.mcp.json` points at `http://localhost:8080/mcp`; with the app running, ask Claude Code to
*"sweep the database for order-service"*.

Tools on both surfaces: `sweep_database`, `sweep_metrics`, `list_tables`, `describe_table`,
`explain_query`, `suggest_indexes` (plus `evidence_so_far` in-process).

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
| `pop.investigation.default-lookback` | `1h` | |
| `pop.investigation.allow-explain-analyze` | `false` | EXPLAIN ANALYZE executes the query |
| `spring.http.serviceclient.prometheus.base-url` | `localhost:9090` | |

Prompt and completion logging are off by default — query text and metrics are production data.

## Notes for future work

Logs (Loki) and Kubernetes were scoped out of this build. Both fit behind `InvestigatorPort`
without touching the domain. A Kubernetes adapter would be the first one that could *act* rather
than only observe, which needs an approval gate before any `kubectl` command runs — the current
design assumes every investigator is read-only.
