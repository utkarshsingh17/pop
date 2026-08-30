# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

**pop** — an AI Production Operations Platform. It answers operational questions ("why is the order
service slow?") by giving an LLM read-only tools over real telemetry (Postgres statistics,
Prometheus metrics) and letting the model decide what evidence to gather. Findings accumulate on an
`Investigation` aggregate as a side effect of the tool calls, so the evidence trail is stored
alongside the diagnosis.

Spring Boot 4.1 / Spring AI 2.0 / Java 21, Maven, Lombok. No linter or formatter is configured.
Pushed to `github.com/utkarshsingh17/pop`; the demo target service it watches lives in the
sibling repo `utkarshsingh17/order-service`.

## Commands

```bash
export OPENAI_API_KEY=...                                   # required to boot; see "Model provider" below
./mvnw spring-boot:run                                      # brings up compose.yaml itself

./mvnw clean verify                                         # full build
./mvnw test                                                 # all tests
./mvnw test -Dtest=SqlSafetyGuardTest                       # one class
./mvnw test -Dtest=PopApplicationTests#contextLoads         # one method
```

`spring-boot-docker-compose` is on the classpath, so startup brings up `compose.yaml` (Postgres on
55432, Prometheus on 9090) and blocks until the Postgres healthcheck passes before Flyway runs — no
separate `docker compose up`. Docker must be running or startup fails there instead.
`spring.docker.compose.lifecycle-management: start-only` leaves the containers up when the app
stops; run `docker compose down` when you actually want them gone.

Tests need a working Docker daemon — everything touching Postgres uses Testcontainers, never H2,
because the schema and the statistics queries are Postgres-specific. No test calls a model API.

```bash
curl -s localhost:8080/api/v1/investigations -H 'Content-Type: application/json' \
  -d '{"question":"why is the order service slow?","service":"order-service","lookback":"PT1H"}' | jq
```

Compose seeds a `shop` database with the deliberate fault the demo diagnoses: 400k orders with no
index on `orders.customer_id`.

## Architecture

Hexagonal. The dependency rule is enforced by convention, not by a build plugin, so it has to be
held by hand:

```
domain/          Investigation aggregate, Finding, Diagnosis, value objects + ports.
                 Pure Java — no Spring, JPA, Jackson or HTTP imports. Ever.
application/     Use cases (InvestigateService) and OpsToolkit, the capability layer.
infrastructure/  Adapters: persistence (JPA), investigator/postgres, investigator/prometheus,
                 ai (Spring AI), mcp, web, config.
```

Things that require reading several files to see:

**`InvestigatorPort` is the extension seam.** Postgres and Prometheus implement it; adding logs,
tracing or Kubernetes means one new adapter plus tool methods, with no domain change. `Finding` is
the common currency. Implementations must never throw for an unreachable backend — return empty or
a `Finding` describing the collection failure, so one dead source cannot abort an investigation.
`OpsToolkit` routes to them by `FindingSource` via a map built from the injected `List<InvestigatorPort>`.

**Two datasources, and the `@Primary` is load-bearing.** `PrimaryDataSourceConfig` is pop's own
read/write, Flyway-migrated database; `TargetDataSourceConfig` is the read-only observed database
(`pop.target-datasource.*`, pool of 3, `setReadOnly(true)`, per-connection `statement_timeout`,
`maxRows`). Boot's `DataSourceAutoConfiguration` backs off the moment *any* `DataSource` bean
exists, so both must stay explicitly declared and the primary must stay `@Primary` — otherwise the
read-only target becomes the app's only datasource and Flyway migrates the system under
observation. Any `DataSource`/`JdbcTemplate` injection for the target must be `@Qualifier`-ed with
`TargetDataSourceConfig.TARGET_DATA_SOURCE` / `TARGET_JDBC_TEMPLATE`.
`PopApplicationTests#theTwoDataSourcesShouldBeDistinctAndOnlyTheTargetReadOnly` guards this.

**Three investigators, and the actuator one is point-in-time.** `ActuatorInvestigator` reads a
registered service's own `/actuator` over HTTP — health components, heap, metaspace, threads, GC,
CPU, mean latency, Hikari pool, file descriptors. It cannot answer "when did this start", because
the endpoint holds no history; `PrometheusInvestigator` is the other half of that pair, and the
system prompt tells the agent to use them together. Every actuator finding's `detail` carries a
concrete next check rather than a bare number. It cannot use a declarative `@HttpExchange` client
like the Prometheus adapter does — that binds one base URL at startup, and here the address comes
from the registry at call time, so `ActuatorClient` builds a `RestClient` per request with tighter
timeouts. A missing metric yields no finding at all (a service without a connection pool is not a
problem), and a `max` of `-1` means "unlimited" so ratios against it are skipped.

**A URL alone is a complete registration.** `POST /api/v1/services {"url":"http://localhost:3001"}`
derives the service name from host and port (`localhost-3001`) via `ActuatorEndpoint`, which also
normalises `http://h:3001` and `http://h:3001/actuator` to the same stored value. Passing a URL over
an API is the real SSRF vector — an HTTP GET returns whatever is at the address — so
`TargetUriGuard.requireAllowedHttpUrl` vets scheme and host before the endpoint is ever stored, not
just before it is fetched.

**The service registry decides which database a sweep hits.** `POST /api/v1/services` registers a
`MonitoredService` (name, optional Prometheus label, optional JDBC coordinates).
`TargetDataSourceRegistry` — a bounded LRU of read-only Hikari pools keyed by service, closed on
eviction — resolves it at sweep time. Investigators depend on the narrow `TargetDatabaseResolver`
seam rather than the registry itself, so a test supplies a fixed template with a lambda. An
unregistered service falls back to the statically configured `pop.target-datasource.*`, which is
what keeps the bundled demo and `PopApplicationTests` working unchanged. `SqlAnalysisService`
resolves via `InvestigationContext` because its methods are called by tools that carry no service
argument; validate identifiers *before* calling `jdbc()`, since Java evaluates the receiver first
and an invalid name would otherwise open a pool it never needed.

**Registered credentials are encrypted, and registration is the security checkpoint.**
`SecretCipher` (AES-GCM, fresh IV per encryption, key from `pop.security.secret-key`) encrypts in
`MonitoredServiceJpaAdapter`, never in the domain — `DatabaseTarget` carries plaintext in memory
only and redacts its own `toString`. With no key configured, registering a service that carries a
password is refused with 503 rather than stored readable. `TargetUriGuard` vets the host before any
pool opens: link-local (cloud metadata), wildcard and multicast are always blocked, multi-host URLs
are refused rather than half-checked, and `pop.security.allowed-target-hosts` narrows it further.
Loopback and private ranges stay allowed on purpose — a database on a private network is the normal
case.

**Three independent barriers on SQL the model composes**, none of them trusted alone:
`SqlSafetyGuard` (masks literals and comments with a hand-written scanner, then rejects anything not
provably read-only — data-modifying CTEs, stacked statements, dollar-quote escapes, nested block
comments, `SELECT … INTO`, file/network functions); the `pop_readonly` database role; and the
read-only pool with its timeout and row cap. `SqlSafetyGuardTest` is adversarial and large — extend
it when touching the guard. `EXPLAIN ANALYZE` *executes* what it explains, so it is off by default
(`pop.investigation.allow-explain-analyze`) rather than gated on caller intent. pop never writes to
the observed system; index suggestions are proposals for a human.

**Two tool surfaces over one toolkit.** `OpsToolkit` holds all the work; `application/tool/OpsTools`
(`@Tool`, in-process Spring AI agent) and `infrastructure/mcp/OpsMcpTools` (`@McpTool`, external MCP
clients) are thin adapters over it. Never point both annotation mechanisms at the same method —
that produces duplicate tool definitions. Toolkit methods return human-readable prose, not JSON,
because the consumer is a model. MCP callers get ephemeral, unpersisted investigations: the
platform's eyes, not its memory.

**`InvestigationContext` is a `ThreadLocal`,** not a parameter. The model calls tools by name with
only the arguments it chose, so it cannot identify which investigation a call belongs to — and
must not be trusted to. `SpringAiDiagnosisEngine` binds the aggregate for the whole agent loop via
`runWithin(...)`; tools read it back with `require()`. Always clear in a `finally` — a leaked
binding records findings onto someone else's investigation.

**`InvestigateService` is deliberately not `@Transactional`.** The agent call makes model
round-trips and can take minutes; each save is its own short transaction. A failed agent loop still
persists the evidence gathered before the failure.

**`SpringAiDiagnosisEngine.AgentDiagnosis`** is a wire record separate from the domain `Diagnosis`,
shaped for reliable model output (confidence as a free string, coerced by substring match, degrading
to `LOW` rather than failing deserialisation). Keep domain invariants out of it.

**Prometheus access** is a Boot 4 declarative HTTP interface (`PrometheusApiClient`, no
`@Component`) registered by `@ImportHttpServices(group = "prometheus")` on `PopApplication`; the
group name must match `spring.http.serviceclient.prometheus.*`. Retries live on the separate
`PrometheusQueryGateway` bean because Framework 7 resilience is proxy-based and self-invocation
would bypass the advice.

Errors are RFC 9457 Problem Details (`ProblemDetailExceptionHandler`). Flyway owns the schema
(`ddl-auto: validate`); add migrations under `src/main/resources/db/migration`.

## Model provider drift

`README.md` describes Anthropic / `claude-opus-5`, but the code is wired to OpenAI: `pom.xml` pulls
`spring-ai-starter-model-openai`, and `application.yml` reads `spring.ai.openai.api-key` from
`OPENAI_API_KEY` with `OPENAI_MODEL` defaulting to `gpt-4o`. Trust the config, not the README.
Switching providers should stay confined to that one dependency and the `spring.ai.*` block — no
application code imports a provider-specific type, and it should stay that way.

## Conventions

`.claude/skills/` is a 24-skill library of Spring Boot 4 / Framework 7 conventions that this
codebase is built against, with its own `CLAUDE.md`. Read `.claude/skills/CLAUDE.md` before editing
skills, and consult the relevant `SKILL.md` before writing code in its area. The ones this project
actually follows (several skills describe mutually exclusive choices): `hexagonal-architecture`,
`problem-details-rfc9457`, `http-interface-clients`, `resilience-retry`, `flyway-migrations`,
`testing-pyramid`, `spring-ai-integration`, `mcp-server` — **not** `layered-architecture`,
`rest-api-conventions`' envelope, or `openapi-first`.

Spring Boot 4 renames that recur here and are easy to regress: `spring-boot-starter-webmvc` (not
`-web`), `@MockitoBean` (not `@MockBean`), Jackson 3 (`tools.jackson`), `@Retryable` from
`org.springframework.resilience` with `maxRetries` (not spring-retry's `maxAttempts`), per-technology
slice-test starters. Lombok's annotation processor is declared explicitly in `pom.xml` because
JDK 23+ no longer discovers processors on the classpath.
