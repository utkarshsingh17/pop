# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repository is

This directory (`pop/.claude/skills/`) is a **library of Claude Code skills** for Spring Boot 4 /
Spring Framework 7 backend development. There is no build, test, or lint step for the skills
themselves — they are Markdown instructions plus illustrative Java/SQL/XML/YAML files that are
*read*, never compiled.

The surrounding `pop/` project is the **AI Production Operations Platform** — a hexagonal Spring
Boot 4.1 / Spring AI 2.0 service that diagnoses production problems with LLM tool calling over
Postgres and Prometheus. See `pop/README.md`. These skills are the convention layer it is built
against, so a change here is a change to how that codebase should look.

## Host project commands

Run from `/Users/gojo/Documents/personal/pop`:

```bash
./mvnw spring-boot:run                                    # run the app
./mvnw test                                               # all tests
./mvnw test -Dtest=PopApplicationTests                    # one test class
./mvnw test -Dtest=PopApplicationTests#methodName         # one test method
./mvnw clean verify                                       # full build
```

Not a git repository. There is no linter or formatter configured.

## Skill anatomy

Every skill is a directory whose name **must match** the `name:` in its frontmatter:

```
<skill-name>/
  SKILL.md              # required — frontmatter + the instructions
  examples/             # optional — bad-*.java / good-*.java pairs
  templates/            # optional — drop-in starting files
```

`SKILL.md` frontmatter is exactly two keys, `name` and `description`, with the description as a
folded block (`>`) that starts with "Use when …" and names the concrete triggers (class kinds,
annotations, package layouts, user phrases) that should activate the skill. That description is the
only thing the model sees when deciding whether to load the skill, so it carries the routing weight.

### SKILL.md body conventions

- Sections are topical (`## Layer Rules`, `## Relationships`, `## Pagination`), each stating rules
  as terse imperative bullets, then a code block demonstrating them.
- Contrasting code is marked inline with `// ✅ GOOD` and `// ❌ BAD` comments.
- **Every skill ends with a `## Gotchas` section** written in a fixed voice: `Agent <does wrong
  thing> — <do this instead>`. These encode observed model failure modes and are the highest-value
  part of a skill. When adding rules, add the matching gotcha.
- Skills point at their own assets by relative path (e.g. "use the template in
  `templates/BaseAssignedIdEntity.java`").

### examples/ and templates/

- `examples/` files are **not compilable and not on the Maven source path** — `bad-*.java` deliberately
  packs every anti-pattern into one class with trailing `//` comments naming each violation;
  `good-*.java` is its corrected twin. They often omit package/import declarations.
- `templates/` files are meant to be copied into a real project and use `com.example.*` placeholder
  packages.

## Spring Boot 4 baseline

The skills exist largely to correct model habits from Boot 3 and earlier. These renames/removals
recur across skills and must stay consistent everywhere:

- `javax.persistence.*` → `jakarta.persistence.*`; Jakarta Persistence 3.2 / Hibernate 7
- `spring-boot-starter-web` → `spring-boot-starter-webmvc` (tests: `spring-boot-starter-webmvc-test`)
- Jackson 3: `com.fasterxml.jackson` → `tools.jackson`; declare `JsonMapper` beans not `ObjectMapper`;
  `@JsonComponent` → `@JacksonComponent`
- `@MockBean` removed → `@MockitoBean` / `@MockitoSpyBean`; `TestRestTemplate` → `RestTestClient`;
  `@SpringBootTest` no longer auto-provides MockMvc (add `@AutoConfigureMockMvc`)
- Spring Security 7: lambda DSL only — no `and()`, `authorizeRequests()`, `antMatchers()`
- Retries are built into Framework 7 (`@Retryable` + `@EnableResilientMethods`) — never add spring-retry
- API versioning is native (`version` attribute on mappings + `spring.mvc.apiversion.*`) — never
  hand-roll duplicated `/v1` `/v2` controllers

Known drift: `rest-api-conventions/templates/ApiResponse.java` still imports
`com.fasterxml.jackson.annotation.JsonInclude` while the skills mandate Jackson 3.

## Overlapping skills

Several skills describe **mutually exclusive** choices rather than layers that stack. When editing
one, check whether its counterpart contradicts it:

- `layered-architecture` (controller → service → repository) vs `hexagonal-architecture`
  (domain/application/infrastructure, no Spring or JPA in domain) — a project picks one.
- `rest-api-conventions` (`ApiResponse` success/data/error envelope) vs `problem-details-rfc9457`
  (`ProblemDetail`, RFC 9457) — two incompatible error shapes; both define a
  `@RestControllerAdvice`.
- `spring-data-jpa` (entities as the persistence model) vs `hexagonal-architecture` (separate
  `OrderJpaEntity` adapter mapped to a pure domain `Order`).
- `openapi-first` generates the controller interfaces that `rest-api-conventions` otherwise
  hand-writes.
