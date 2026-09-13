# Pura Rate Limiter Challenge — Candidate Solution Design

## Context

The user is the candidate for a Pura job interview, building their own
submission for the take-home Rate Limiter API challenge (see repo
`README.md`). A prior orientation pass (summarized separately in chat)
established: the repo's `sample-solution/` is reference-only and has a real
gap (it doesn't implement per-client limits despite the spec requiring it);
`test-harness/` auto-scores out of 65 points across basic limiting, multi-client
isolation, concurrency, config changes, and performance; and no candidate
solution folder exists yet.

This plan captures the architectural design worked out via brainstorming, to
be executed next. Nothing has been implemented yet — this documents the
approved design before any code is written.

## Requirements recap (from `README.md`)

- `POST /api/v1/ratelimit` `{client_id, resource}` → `{allowed, remaining, retry_after?}`
- `POST /api/v1/configure` `{window_seconds, request_per_window}` → echoes config
- Per-client limits, configurable window/limit, 1000+ req/s, <10ms decision
  latency, thread-safe, no required external deps (but extensible to them),
  single-machine, well-tested, well-documented.
- **Spec tension resolved**: the documented `/configure` body has no
  `client_id`/`resource` fields, yet requirement #3 demands different limits
  per client. Resolution (approved): extend `/configure` with *optional*
  `client_id` and `resource` fields — omitted means "set the global default"
  (matches the literal spec example byte-for-byte); present means "set an
  override for that scope." Document this extension explicitly in the
  submission write-up.

## Approved stack & tooling decisions

- **Language**: Java (main sources), targeting **Java 25 (latest LTS)** via
  Gradle Java toolchain support.
- **Framework**: Spring Boot, **Spring MVC / Tomcat** (blocking) — the
  rate-limit check is pure in-memory CPU work, not I/O-bound, so a
  thread-per-request model comfortably clears the throughput/latency targets
  without WebFlux's added complexity.
- **Build**: **Gradle, Kotlin DSL**, multi-project build. Root project
  includes subproject `rate-limiter/` (the actual submission lives there, so
  it can be dropped into the interviewer's `candidates/<name>/` flow per the
  repo's grading instructions).
- **Boilerplate**: prefer Java `record`s for pure immutable value types
  (DTOs, internal decision objects); reserve **Lombok** for POJOs that need
  builders or carry mutable state (e.g. the rule-override registry).
- **Tests**: **Kotest** (JUnit5 platform) in `src/test/kotlin`, mirroring the
  main package structure.
- **Dependency versions**: centralized in a Gradle version catalog
  (`gradle/libs.versions.toml`) — Spring Boot 4.1.1 (Spring Framework 7),
  Lombok, Kotlin, Kotest, and any other library versions are declared once
  there and referenced from both `build.gradle.kts` files via `libs.*`,
  instead of hardcoding version strings in the build scripts.

## Module layout

Traditional layered Spring MVC structure (controller / service / repository /
model / dto / exception / config), rather than grouping by feature:

```
pura/
├── settings.gradle.kts          (root, includes ":rate-limiter")
├── build.gradle.kts             (shared config: Java 25 toolchain, Kotlin, Lombok, Kotest)
├── gradle/
│   └── libs.versions.toml       (version catalog: Spring Boot/Framework, Kotlin, Lombok, Kotest, etc.)
└── rate-limiter/
    ├── build.gradle.kts
    ├── .editorconfig             (2-space indent, scoped to this subproject)
    └── src/
        ├── main/java/.../ratelimiter/
        │   ├── RateLimiterApplication.java
        │   ├── controller/
        │   │   ├── RateLimitController.java    → POST /api/v1/ratelimit
        │   │   └── ConfigureController.java     → POST /api/v1/configure
        │   ├── service/
        │   │   ├── RateLimiterService.java      — orchestrates checkRateLimit(clientId, resource)
        │   │   └── ConfigurationService.java    — validates & applies /configure updates
        │   ├── repository/
        │   │   ├── RateLimitStore.java          — interface (the extensibility seam for Redis/etc.)
        │   │   ├── InMemoryRateLimitStore.java  — ConcurrentHashMap-backed impl
        │   │   └── RuleRegistry.java            — Lombok POJO: global default + client/resource override maps
        │   ├── model/
        │   │   ├── RateLimitRule.java           (window_seconds, request_per_window — record)
        │   │   └── RateLimitDecision.java        (allowed, remaining, retryAfter — record)
        │   ├── dto/
        │   │   └── RateLimitRequest, RateLimitResponse, ConfigureRequest, ConfigureResponse — records
        │   ├── exception/
        │   │   ├── InvalidConfigurationException.java
        │   │   └── GlobalExceptionHandler.java  — @ControllerAdvice, maps to 400 error bodies
        │   └── config/
        │       └── RateLimiterDefaultsProperties.java — @ConfigurationProperties, binds application.yml startup defaults
        └── test/kotlin/.../ratelimiter/          (Kotest specs, mirroring main packages)
            ├── service/RateLimiterServiceSpec.kt
            ├── repository/{InMemoryRateLimitStoreSpec,RuleRegistrySpec}.kt
            ├── controller/{RateLimitControllerSpec,ConfigureControllerSpec}.kt
            └── RateLimiterIntegrationSpec.kt      — @SpringBootTest, real HTTP round-trip
```

**Code style**: add the Spotless Gradle plugin (version pinned in
`libs.versions.toml`) formatting both Java and Kotlin sources, so style stays
consistent without manual enforcement — mirrors this repo's own use of
Prettier for the TS sample solution. Also add an `.editorconfig` at
`rate-limiter/.editorconfig`, scoped to that subproject, with 2-space
indentation for its source files (matching the root repo's own 2-space
style used by the TS sample/harness), so editors format consistently even
before Spotless runs.

**Static analysis**: add the **SpotBugs** Gradle plugin (version pinned in
`libs.versions.toml`), run as part of `./gradlew check`. Unlike the
formatting tools, this has a direct line to a specific rubric item —
"thread-safe implementation" (5 pts, Design & Architecture) — since SpotBugs'
concurrency detectors (unsafe publication, inconsistent synchronization,
etc.) can catch a real race condition a reviewer might otherwise have to
spot by eye. Any findings get resolved before submission, not suppressed.

**`rate-limiter/README.md`** (separate from `docs/design.md`): prerequisites,
build (`./gradlew build`), run (`./gradlew bootRun`, plus how to pick a port),
test (`./gradlew test`), and how to point `test-harness/` at the running
server. This directly satisfies the top-level README's Submission requirement
#2 ("instructions on how to build and run") and the rubric's Documentation
line item — a reviewer expects a README at a glance, not just a design doc.

## Core algorithm — sliding window log

`InMemoryRateLimitStore` keys requests by `"clientId:resource"` in a
`ConcurrentHashMap<String, Deque<Instant>>`. Each check runs inside
`compute()` so the whole read-evict-decide-write sequence is atomic per key
(CHM guarantees no two threads run the remapping function for the same key
concurrently — no explicit locks needed):

1. Evict entries older than `now - windowSeconds`.
2. If remaining size `< limit`: admit — append `now`, return
   `allowed=true`, `remaining = limit - newSize`.
3. Else: reject — return `allowed=false`, `remaining=0`,
   `retry_after = ceil(seconds until the oldest entry ages out)`.

## Config precedence (`RuleRegistry`)

Lookup order, most specific wins: **client+resource exact match → client-only
→ resource-only → global default**. `/configure` validates
`window_seconds > 0` and `request_per_window > 0`, returning `400` with an
error body on violation. Response echoes back whatever scope/fields were set.
Startup defaults (e.g. 60s / 100 req) live in `application.yml` so the
service is usable before any `/configure` call.

## Testing strategy

Kotest specs covering four layers:

1. **Core logic** (`RateLimiterServiceSpec`, `InMemoryRateLimitStoreSpec`): window
   boundary/rollover correctness, `retry_after` math, and a concurrency test
   firing many threads at one key and asserting exactly N are admitted.
2. **Config precedence** (`RuleRegistrySpec`): all four precedence tiers,
   override updates taking effect immediately.
3. **HTTP contract** (`@WebMvcTest`-style slice specs): request/response JSON
   shapes, status codes, and validation-error paths for both endpoints —
   including `/ratelimit` requests with a missing/blank `client_id` or
   `resource`, which must 400 the same way an invalid `/configure` body does.
4. **One true integration spec** (`RateLimiterIntegrationSpec`, `@SpringBootTest`
   on a random port + a real HTTP client): boots the full context and drives
   both endpoints end-to-end. The evaluation rubric scores "integration tests"
   as a separate 3-point line item from unit tests — slice tests alone read as
   "unit-only" to a reviewer skimming the test folder, so this needs to exist
   as its own spec even though the external `test-harness` covers similar
   ground.

**Validation**: both endpoints validate their input via Bean Validation
(`@NotBlank` on `client_id`/`resource` in the ratelimit DTO; `@Positive` on
`window_seconds`/`request_per_window` in the configure DTO) — `GlobalExceptionHandler`
maps both `MethodArgumentNotValidException` (bad DTOs) and
`InvalidConfigurationException` (bad business-rule state) to `400`.

**Contract detail to not get wrong**: per the spec's literal API doc, `POST
/api/v1/ratelimit` always returns **`200 OK`** — including when
`allowed: false`. Do not return `429` for a blocked request; only malformed
input (validation failures) gets a `4xx`.

## Verification (once implemented)

1. `./gradlew :rate-limiter:test` — all Kotest specs pass.
2. `./gradlew :rate-limiter:bootRun` (or the built jar) starts the server on
   a chosen port.
3. From `test-harness/`: `npm install && npm start -- --host localhost --port <port>`
   — confirm all 5 scored categories pass and inspect the printed
   latency/throughput numbers against the rubric's tiers (aim past `<1ms` /
   `>15000 req/s` for max points, not just the spec's stated minimums).
4. Write the submission trade-offs doc: algorithm choice vs. token/leaky
   bucket, the `/configure` extension rationale, the `RateLimitStore`
   interface as the Redis-extensibility story, and the MVC-over-WebFlux call.
   Explicitly address **memory usage**: the sliding-window-log stores one
   timestamp per admitted request per `client_id:resource` key, evicted
   lazily on each access, so steady-state memory is bounded by
   `active_keys × request_per_window`, not unbounded — the evaluation
   rubric scores "efficient memory usage" as its own line item, so this
   needs to be said explicitly, not left implicit in the code.

## Requirements coverage check

Cross-referenced against `README.md`, `test-harness/evaluation_rubric.md`
(manual 100-pt rubric), and `test-harness/README.md`. Gaps found are already
folded into the sections above; noting them here for traceability:

- **Closed**: no `/ratelimit` input validation was planned → added Bean
  Validation + `GlobalExceptionHandler` coverage for both endpoints.
- **Closed**: no true `@SpringBootTest` integration spec was planned, and the
  rubric scores "integration tests" separately from unit tests → added
  `RateLimiterIntegrationSpec`.
- **Closed**: "efficient memory usage" (5 pts, Design & Architecture) had no
  explicit treatment → added as a required topic in the write-up.
- **Closed**: no `rate-limiter/README.md` was planned, only `docs/design.md`
  → the top-level README's Submission requirement #2 needs build/run
  instructions as their own document.
- **Closed**: no formatting/linting tooling was planned, unlike the repo's
  own Prettier use for the TS sample → added Spotless.
- **Closed**: the literal `200 OK`-always contract for `/ratelimit` wasn't
  called out anywhere and is an easy mistake (returning `429` on block) →
  now stated explicitly in the testing strategy section.
- **Informational, no action needed**: `test-harness/README.md` describes a
  4th test category, "Statistics tracking," that doesn't exist in the actual
  `test_harness.ts` (only basic/multi-client/concurrent/config-change/performance
  run) — that doc is stale; nothing to build against it.
- **Informational, no action needed**: Pura's own two rubrics disagree —
  `evaluation_rubric.md`'s Functionality section only lists basic(20)/multi-client(10)/concurrent(10)
  = 40 with no config-change item, while the automated harness (and the
  top-level README's own scoring table) scores config changes as a 4th,
  10-point functionality category, bringing its total to 65. Config-change
  correctness is still covered by the plan either way — just noting the
  source documents don't agree with each other.

## Design doc location

This design is written as a single permanent document at
`rate-limiter/docs/design.md`. That one file serves double duty: it's both
the working design spec and the "brief write-up explaining your design
choices and trade-offs" the top-level `README.md`'s Submission section asks
for. **Status: done** — written and committed (`38c298a`, "Add design doc
for rate limiter candidate submission").

## Implementation plan

Below is the concrete, TDD-sequenced, file-by-file implementation plan,
produced via the `writing-plans` skill from the approved design above. This
is what executes next.

Environment check performed before writing this plan: local JDK is Temurin
21 only; Temurin 25 (`25.0.4-tem`) is available and installable via SDKMAN.
Local Gradle (via SDKMAN) is 8.10.2; the wrapper will be generated at
**9.7.1** (latest stable, required by the Spring Boot 4.1 Gradle plugin,
which needs Gradle 8.14+/9.x). Real, current versions used throughout
(verified via web search, not guessed): Spring Boot 4.1.1, Kotlin 2.4.20,
Kotest 6.1.11, `kotest-extensions-spring` (same version as `kotest`, group
`io.kotest` — see correction below), Lombok 1.18.48, Spotless 8.10.2,
SpotBugs Gradle plugin 6.5.11.

**Correction found during Task 1 (round 2 of its fix loop, before this plan
was executed further):** the original version catalog entry for
`kotest-extensions-spring` used the pre-6.0 coordinates
(`io.kotest.extensions:kotest-extensions-spring:1.3.0`), which transitively
pulls `kotest-framework-api:5.8.1` — an ABI mismatch against
`kotest`/`kotest-runner-junit5` at 6.1.11 that throws `NoSuchMethodError`
during test discovery. Since Kotest 6.0, this artifact moved to group
`io.kotest`, versioned alongside the main Kotest release. Fixed to
`io.kotest:kotest-extensions-spring:6.1.11` (`version.ref = "kotest"`, no
separate version needed). Kotest 6.0 also replaced the old
`extensions(SpringExtension)` instance-call-in-`init{}` pattern with a
class-level `@ApplyExtension(SpringExtension::class)` annotation — every
Kotest spec below that wires Spring (`RateLimiterApplicationTests`,
`ConfigureControllerSpec`, `RateLimitControllerSpec`,
`RateLimiterIntegrationSpec`) already reflects this corrected pattern.
Separately, applying the `org.springframework.boot` plugin alone does not
manage unversioned starter coordinates like `spring-boot-starter-web` — a
BOM import is required. Task 1's `rate-limiter/build.gradle.kts` below
includes `implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))`
for this reason (via the version catalog, not a hardcoded string).

**Found during Task 6:** Spring Boot 4 moved `@WebMvcTest` (and the rest of
the MVC test slice) out of `spring-boot-starter-test` into a separate
`spring-boot-webmvc-test` module. Task 1's `rate-limiter/build.gradle.kts`
below already includes
`testImplementation("org.springframework.boot:spring-boot-webmvc-test")`
for this reason, so every later task's `@WebMvcTest` specs (Task 6, 7) just
work without needing this added again.

**Found and fixed before Task 8** (proactively, by checking Spring Boot 4's
migration notes and verifying against the actual jar before dispatching,
rather than waiting for an implementer to hit it): `@SpringBootTest` no
longer auto-provides `TestRestTemplate` in Spring Boot 4. It moved packages
entirely — `TestRestTemplate` now lives in
`org.springframework.boot.resttestclient.TestRestTemplate` (not
`org.springframework.boot.test.web.client`), needs the
`spring-boot-resttestclient`/`spring-boot-restclient` dependencies (both
already added to Task 1's `rate-limiter/build.gradle.kts` below), and the
test class needs an explicit `@AutoConfigureTestRestTemplate` annotation
(`org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate`)
alongside `@SpringBootTest`. Verified by compiling a throwaway probe file
against the real jar contents, not guessed. Task 8's snippet below already
reflects this.

**One refinement to the earlier design**, discovered while working out real
code: `InvalidConfigurationException` (mentioned earlier as part of error
handling) turned out to have no actual use — Bean Validation's `@Positive`
on primitive `int` fields already rejects both "missing" and "non-positive"
`window_seconds`/`request_per_window` in one annotation (a missing JSON
field binds a primitive to `0`, which `@Positive` then rejects), so there is
no remaining code path that would ever throw it. It's dropped to avoid dead
code; `GlobalExceptionHandler` only needs to handle
`MethodArgumentNotValidException`. Also added, not in the earlier design:
Lombok's `@RequiredArgsConstructor` on every constructor-injected Spring
component (`RateLimiterService`, `ConfigurationService`,
`RateLimitController`, `ConfigureController`) — the standard, idiomatic
Spring use of Lombok, and the reason the Lombok dependency configured in
Task 1 actually gets used rather than sitting unused.

---

# Rate Limiter Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Pura Rate Limiter API challenge submission — a Spring
Boot (Java) service exposing `POST /api/v1/ratelimit` and
`POST /api/v1/configure`, backed by an in-memory sliding-window-log rate
limiter, in the `rate-limiter/` subproject of this repo.

**Architecture:** Traditional layered Spring MVC (`controller` → `service`
→ `repository`, plus `model`/`dto`/`exception`/`config`). Per-key request
logs live in a `ConcurrentHashMap`, mutated atomically via `compute()`.
Config overrides (global/client/resource/client+resource) live in a small
in-memory `RuleRegistry`, seeded from `application.yml` defaults.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Framework 7, Spring MVC),
Gradle 9.7.1 (Kotlin DSL, multi-project, version catalog), Lombok 1.18.48,
Kotest 6.1.11 for tests.

**Spec:** `rate-limiter/docs/design.md` (this repo, committed at `38c298a`)
and the "Approved plan" sections above in this same file.

## Global Constraints

- Java 25 (LTS) via Gradle toolchain; Gradle wrapper pinned to 9.7.1.
- Spring Boot 4.1.1 / Spring Framework 7, **Spring MVC** (not WebFlux).
- Gradle Kotlin DSL, multi-project build: root + `rate-limiter/` subproject.
- Every dependency and plugin version lives in `gradle/libs.versions.toml`,
  referenced via `libs.*` / `alias(libs.plugins.*)` — never a hardcoded
  version string in a `build.gradle.kts`.
- Package root: `com.pura.ratelimiter`.
- Layered packages: `controller` / `service` / `repository` / `model` /
  `dto` / `exception` / `config`.
- Java `record`s for immutable value types; Lombok `@RequiredArgsConstructor`
  for constructor-injected Spring components.
- All tests are Kotest specs (JUnit5 platform) under `src/test/kotlin`,
  mirroring the main package structure.
- JSON uses snake_case keys (`window_seconds`, `client_id`, `retry_after`,
  ...) via a global Jackson naming strategy (`SNAKE_CASE`) — never per-field
  `@JsonProperty`. Null fields are omitted (`default-property-inclusion:
  non_null`).
- `POST /api/v1/ratelimit` always returns `200 OK`, even when
  `allowed:false` — never `429`. Only malformed input gets a `4xx`.
- `/configure` precedence: client+resource exact match → client-only →
  resource-only → global default.
- Spotless + SpotBugs wired into `./gradlew check`; `.editorconfig` at
  `rate-limiter/.editorconfig`, 2-space indentation.

---

### Task 1: Gradle multi-project scaffolding + Spring Boot skeleton

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle/libs.versions.toml`
- Create: `rate-limiter/build.gradle.kts`
- Create: `rate-limiter/.editorconfig`
- Create: `rate-limiter/src/main/resources/application.yml`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/RateLimiterApplication.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/RateLimiterApplicationTests.kt`

**Interfaces:**
- Produces: a working `./gradlew :rate-limiter:test` / `:bootRun` from the
  repo root; the `libs` version catalog accessor usable from every later
  `build.gradle.kts` edit; package root `com.pura.ratelimiter` under both
  `src/main/java` and `src/test/kotlin`.

- [ ] **Step 1: Install a local JDK 25 (so the toolchain doesn't have to rely on network auto-download)**

Run: `source "$HOME/.sdkman/bin/sdkman-init.sh" && sdk install java 25.0.4-tem`

- [ ] **Step 2: Create `settings.gradle.kts`**

```kotlin
rootProject.name = "pura"

include("rate-limiter")
```

- [ ] **Step 3: Create `gradle/libs.versions.toml`**

```toml
[versions]
kotlin = "2.4.20"
springBoot = "4.1.1"
kotest = "6.1.11"
lombok = "1.18.48"
spotless = "8.10.2"
spotbugs = "6.5.11"

[libraries]
lombok = { module = "org.projectlombok:lombok", version.ref = "lombok" }
kotest-runner-junit5 = { module = "io.kotest:kotest-runner-junit5", version.ref = "kotest" }
kotest-assertions-core = { module = "io.kotest:kotest-assertions-core", version.ref = "kotest" }
kotest-extensions-spring = { module = "io.kotest:kotest-extensions-spring", version.ref = "kotest" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "springBoot" }
spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
spotbugs = { id = "com.github.spotbugs", version.ref = "spotbugs" }
```

- [ ] **Step 4: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spotless) apply false
    alias(libs.plugins.spotbugs) apply false
}
```

- [ ] **Step 5: Generate the Gradle wrapper at 9.7.1, using the locally installed Gradle 8.10.2**

Run: `gradle wrapper --gradle-version 9.7.1` (from the repo root)
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`. From here on, use `./gradlew`, not the system `gradle`.

- [ ] **Step 6: Create `rate-limiter/build.gradle.kts`**

```kotlin
plugins {
    java
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spotless)
    alias(libs.plugins.spotbugs)
}

group = "com.pura"
version = "0.0.1-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.springframework.boot:spring-boot-restclient")
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.spring)
}

tasks.withType<Test> {
    useJUnitPlatform()
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat()
    }
    kotlin {
        target("src/*/kotlin/**/*.kt")
        ktlint()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}
```

- [ ] **Step 7: Create `rate-limiter/.editorconfig`**

```ini
root = true

[*]
indent_style = space
indent_size = 2
charset = utf-8
trim_trailing_whitespace = true
insert_final_newline = true
```

- [ ] **Step 8: Create `rate-limiter/src/main/resources/application.yml`**

```yaml
server:
  port: 8080

spring:
  jackson:
    property-naming-strategy: SNAKE_CASE
    default-property-inclusion: non_null

ratelimiter:
  default:
    window-seconds: 60
    request-per-window: 100
```

- [ ] **Step 9: Write the failing smoke test**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/RateLimiterApplicationTests.kt`:

```kotlin
package com.pura.ratelimiter

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
@ApplyExtension(SpringExtension::class)
class RateLimiterApplicationTests : FunSpec() {
    init {
        test("the application context loads") {
            // SpringBootTest fails this test itself if the context can't start.
        }
    }
}
```

- [ ] **Step 10: Run it and confirm it fails**

Run: `./gradlew :rate-limiter:test`
Expected: FAIL — `IllegalStateException: Unable to find a @SpringBootConfiguration` (no application class exists yet).

- [ ] **Step 11: Create the application class**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/RateLimiterApplication.java`:

```java
package com.pura.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RateLimiterApplication {
    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
```

- [ ] **Step 12: Run it and confirm it passes**

Run: `./gradlew :rate-limiter:test`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/ gradlew gradlew.bat rate-limiter/
git commit -m "Scaffold Gradle multi-project build and Spring Boot skeleton"
```

---

### Task 2: Sliding-window store (`RateLimitStore` / `InMemoryRateLimitStore`)

**Files:**
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/model/RateLimitRule.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/model/RateLimitDecision.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/repository/RateLimitStore.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/repository/InMemoryRateLimitStore.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/repository/InMemoryRateLimitStoreSpec.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks (this is the base layer).
- Produces: `record RateLimitRule(int windowSeconds, int requestPerWindow)`;
  `record RateLimitDecision(boolean allowed, int remaining, Long retryAfterSeconds)`;
  `interface RateLimitStore { RateLimitDecision recordAndCheck(String key, RateLimitRule rule, Instant now); }`;
  `@Component class InMemoryRateLimitStore implements RateLimitStore`.

- [ ] **Step 1: Write the failing tests**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/repository/InMemoryRateLimitStoreSpec.kt`:

```kotlin
package com.pura.ratelimiter.repository

import com.pura.ratelimiter.model.RateLimitRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InMemoryRateLimitStoreSpec : FunSpec({

    test("admits requests up to the limit and reports remaining count") {
        val store = InMemoryRateLimitStore()
        val rule = RateLimitRule(10, 3)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        val first = store.recordAndCheck("client-a:resource", rule, now)
        val second = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(1))
        val third = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(2))

        first.allowed() shouldBe true
        first.remaining() shouldBe 2
        second.remaining() shouldBe 1
        third.remaining() shouldBe 0
    }

    test("blocks requests once the limit is reached, with a positive retryAfterSeconds") {
        val store = InMemoryRateLimitStore()
        val rule = RateLimitRule(10, 1)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        store.recordAndCheck("client-a:resource", rule, now)
        val blocked = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(4))

        blocked.allowed() shouldBe false
        blocked.remaining() shouldBe 0
        blocked.retryAfterSeconds() shouldBe 6L
    }

    test("admits again once the window rolls over") {
        val store = InMemoryRateLimitStore()
        val rule = RateLimitRule(10, 1)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        store.recordAndCheck("client-a:resource", rule, now)
        store.recordAndCheck("client-a:resource", rule, now.plusSeconds(5))
        val afterWindow = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(11))

        afterWindow.allowed() shouldBe true
        afterWindow.remaining() shouldBe 0
    }

    test("tracks different keys independently") {
        val store = InMemoryRateLimitStore()
        val rule = RateLimitRule(10, 1)
        val now = Instant.parse("2026-01-01T00:00:00Z")

        store.recordAndCheck("client-a:resource", rule, now).allowed() shouldBe true
        store.recordAndCheck("client-b:resource", rule, now).allowed() shouldBe true
    }

    test("admits exactly N concurrent requests to the same key and blocks the rest") {
        val store = InMemoryRateLimitStore()
        val rule = RateLimitRule(10, 5)
        val now = Instant.now()
        val threadCount = 20
        val allowedCount = AtomicInteger(0)
        val pool = Executors.newFixedThreadPool(threadCount)
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)

        repeat(threadCount) {
            pool.submit {
                ready.countDown()
                start.await()
                val decision = store.recordAndCheck("client-concurrent:resource", rule, now)
                if (decision.allowed()) allowedCount.incrementAndGet()
            }
        }
        ready.await()
        start.countDown()
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.SECONDS)

        allowedCount.get() shouldBe 5
    }
})
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :rate-limiter:test --tests "*InMemoryRateLimitStoreSpec*"`
Expected: FAIL — `RateLimitRule`, `InMemoryRateLimitStore` etc. don't exist yet (compilation error).

- [ ] **Step 3: Implement the model records**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/model/RateLimitRule.java`:

```java
package com.pura.ratelimiter.model;

public record RateLimitRule(int windowSeconds, int requestPerWindow) {
}
```

Create `rate-limiter/src/main/java/com/pura/ratelimiter/model/RateLimitDecision.java`:

```java
package com.pura.ratelimiter.model;

public record RateLimitDecision(boolean allowed, int remaining, Long retryAfterSeconds) {
}
```

- [ ] **Step 4: Implement the store interface and its in-memory implementation**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/repository/RateLimitStore.java`:

```java
package com.pura.ratelimiter.repository;

import com.pura.ratelimiter.model.RateLimitDecision;
import com.pura.ratelimiter.model.RateLimitRule;
import java.time.Instant;

public interface RateLimitStore {
    RateLimitDecision recordAndCheck(String key, RateLimitRule rule, Instant now);
}
```

Create `rate-limiter/src/main/java/com/pura/ratelimiter/repository/InMemoryRateLimitStore.java`:

```java
package com.pura.ratelimiter.repository;

import com.pura.ratelimiter.model.RateLimitDecision;
import com.pura.ratelimiter.model.RateLimitRule;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRateLimitStore implements RateLimitStore {

    private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

    @Override
    public RateLimitDecision recordAndCheck(String key, RateLimitRule rule, Instant now) {
        Instant windowStart = now.minusSeconds(rule.windowSeconds());
        AtomicReference<RateLimitDecision> decision = new AtomicReference<>();

        requestLog.compute(key, (ignoredKey, existing) -> {
            Deque<Instant> timestamps = existing == null ? new ArrayDeque<>() : existing;
            while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < rule.requestPerWindow()) {
                timestamps.addLast(now);
                decision.set(new RateLimitDecision(true, rule.requestPerWindow() - timestamps.size(), null));
            } else {
                Instant oldest = timestamps.peekFirst();
                long retryAfter = Math.max(1, oldest.getEpochSecond() + rule.windowSeconds() - now.getEpochSecond());
                decision.set(new RateLimitDecision(false, 0, retryAfter));
            }
            return timestamps;
        });

        return decision.get();
    }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*InMemoryRateLimitStoreSpec*"`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
git add rate-limiter/src/main/java/com/pura/ratelimiter/model rate-limiter/src/main/java/com/pura/ratelimiter/repository rate-limiter/src/test/kotlin/com/pura/ratelimiter/repository
git commit -m "Add sliding-window RateLimitStore with atomic per-key checks"
```

---

### Task 3: Config precedence (`RateLimiterDefaultsProperties` / `RuleRegistry`)

**Files:**
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/config/RateLimiterDefaultsProperties.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/repository/RuleRegistry.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/repository/RuleRegistrySpec.kt`

**Interfaces:**
- Consumes: `RateLimitRule` (Task 2).
- Produces: `record RateLimiterDefaultsProperties(int windowSeconds, int requestPerWindow)`;
  `@Component class RuleRegistry` with `RateLimitRule resolveRule(String clientId, String resource)`,
  `void setGlobalDefault(RateLimitRule)`, `void setClientOverride(String, RateLimitRule)`,
  `void setResourceOverride(String, RateLimitRule)`,
  `void setClientResourceOverride(String, String, RateLimitRule)`.

- [ ] **Step 1: Write the failing tests**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/repository/RuleRegistrySpec.kt`:

```kotlin
package com.pura.ratelimiter.repository

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties
import com.pura.ratelimiter.model.RateLimitRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RuleRegistrySpec : FunSpec({

    fun newRegistry() = RuleRegistry(RateLimiterDefaultsProperties(60, 100))

    test("falls back to the global default when no override is set") {
        newRegistry().resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("a resource-only override applies to every client for that resource") {
        val registry = newRegistry()
        registry.setResourceOverride("sensitive", RateLimitRule(10, 5))

        registry.resolveRule("client-a", "sensitive") shouldBe RateLimitRule(10, 5)
        registry.resolveRule("client-b", "sensitive") shouldBe RateLimitRule(10, 5)
        registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("a client-only override applies to every resource for that client") {
        val registry = newRegistry()
        registry.setClientOverride("premium-client", RateLimitRule(1, 1000))

        registry.resolveRule("premium-client", "orders") shouldBe RateLimitRule(1, 1000)
        registry.resolveRule("premium-client", "sensitive") shouldBe RateLimitRule(1, 1000)
    }

    test("a client+resource override wins over both client-only and resource-only overrides") {
        val registry = newRegistry()
        registry.setClientOverride("client-a", RateLimitRule(10, 10))
        registry.setResourceOverride("sensitive", RateLimitRule(5, 5))
        registry.setClientResourceOverride("client-a", "sensitive", RateLimitRule(1, 1))

        registry.resolveRule("client-a", "sensitive") shouldBe RateLimitRule(1, 1)
        registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(10, 10)
        registry.resolveRule("client-b", "sensitive") shouldBe RateLimitRule(5, 5)
    }

    test("updating the global default takes effect immediately") {
        val registry = newRegistry()
        registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)

        registry.setGlobalDefault(RateLimitRule(30, 50))

        registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(30, 50)
    }
})
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :rate-limiter:test --tests "*RuleRegistrySpec*"`
Expected: FAIL — `RateLimiterDefaultsProperties` and `RuleRegistry` don't exist yet.

- [ ] **Step 3: Implement the properties class**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/config/RateLimiterDefaultsProperties.java`:

```java
package com.pura.ratelimiter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ratelimiter.default")
public record RateLimiterDefaultsProperties(int windowSeconds, int requestPerWindow) {
}
```

- [ ] **Step 4: Implement `RuleRegistry`**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/repository/RuleRegistry.java`:

```java
package com.pura.ratelimiter.repository;

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties;
import com.pura.ratelimiter.model.RateLimitRule;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RuleRegistry {

    private final AtomicReference<RateLimitRule> globalDefault;
    private final ConcurrentHashMap<String, RateLimitRule> clientOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimitRule> resourceOverrides = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RateLimitRule> clientResourceOverrides = new ConcurrentHashMap<>();

    public RuleRegistry(RateLimiterDefaultsProperties defaults) {
        this.globalDefault = new AtomicReference<>(
                new RateLimitRule(defaults.windowSeconds(), defaults.requestPerWindow()));
    }

    public RateLimitRule resolveRule(String clientId, String resource) {
        RateLimitRule exact = clientResourceOverrides.get(clientResourceKey(clientId, resource));
        if (exact != null) {
            return exact;
        }
        RateLimitRule clientOnly = clientOverrides.get(clientId);
        if (clientOnly != null) {
            return clientOnly;
        }
        RateLimitRule resourceOnly = resourceOverrides.get(resource);
        if (resourceOnly != null) {
            return resourceOnly;
        }
        return globalDefault.get();
    }

    public void setGlobalDefault(RateLimitRule rule) {
        globalDefault.set(rule);
    }

    public void setClientOverride(String clientId, RateLimitRule rule) {
        clientOverrides.put(clientId, rule);
    }

    public void setResourceOverride(String resource, RateLimitRule rule) {
        resourceOverrides.put(resource, rule);
    }

    public void setClientResourceOverride(String clientId, String resource, RateLimitRule rule) {
        clientResourceOverrides.put(clientResourceKey(clientId, resource), rule);
    }

    private static String clientResourceKey(String clientId, String resource) {
        return clientId + ":" + resource;
    }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*RuleRegistrySpec*"`
Expected: PASS (all 5 tests).

- [ ] **Step 6: Commit**

```bash
git add rate-limiter/src/main/java/com/pura/ratelimiter/config rate-limiter/src/main/java/com/pura/ratelimiter/repository/RuleRegistry.java rate-limiter/src/test/kotlin/com/pura/ratelimiter/repository/RuleRegistrySpec.kt
git commit -m "Add config precedence via RuleRegistry"
```

---

### Task 4: `RateLimiterService`

**Files:**
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/service/RateLimiterService.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/service/RateLimiterServiceSpec.kt`

**Interfaces:**
- Consumes: `RateLimitStore.recordAndCheck(String, RateLimitRule, Instant)` (Task 2),
  `RuleRegistry.resolveRule(String, String)` (Task 3).
- Produces: `@Service class RateLimiterService` with
  `RateLimitDecision checkRateLimit(String clientId, String resource)`.

- [ ] **Step 1: Write the failing tests**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/service/RateLimiterServiceSpec.kt`:

```kotlin
package com.pura.ratelimiter.service

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties
import com.pura.ratelimiter.repository.InMemoryRateLimitStore
import com.pura.ratelimiter.repository.RuleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RateLimiterServiceSpec : FunSpec({

    fun newService() = RateLimiterService(
        InMemoryRateLimitStore(),
        RuleRegistry(RateLimiterDefaultsProperties(60, 2)),
    )

    test("allows requests up to the resolved limit, then blocks") {
        val service = newService()

        service.checkRateLimit("client-a", "orders").allowed() shouldBe true
        service.checkRateLimit("client-a", "orders").allowed() shouldBe true
        service.checkRateLimit("client-a", "orders").allowed() shouldBe false
    }

    test("tracks different clients independently for the same resource") {
        val service = newService()
        service.checkRateLimit("client-a", "orders")
        service.checkRateLimit("client-a", "orders")

        service.checkRateLimit("client-b", "orders").allowed() shouldBe true
    }

    test("tracks different resources independently for the same client") {
        val service = newService()
        service.checkRateLimit("client-a", "orders")
        service.checkRateLimit("client-a", "orders")

        service.checkRateLimit("client-a", "billing").allowed() shouldBe true
    }
})
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :rate-limiter:test --tests "*RateLimiterServiceSpec*"`
Expected: FAIL — `RateLimiterService` doesn't exist yet.

- [ ] **Step 3: Implement `RateLimiterService`**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/service/RateLimiterService.java`:

```java
package com.pura.ratelimiter.service;

import com.pura.ratelimiter.model.RateLimitDecision;
import com.pura.ratelimiter.repository.RateLimitStore;
import com.pura.ratelimiter.repository.RuleRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

    private final RateLimitStore rateLimitStore;
    private final RuleRegistry ruleRegistry;

    public RateLimitDecision checkRateLimit(String clientId, String resource) {
        var rule = ruleRegistry.resolveRule(clientId, resource);
        var key = clientId + ":" + resource;
        return rateLimitStore.recordAndCheck(key, rule, Instant.now());
    }
}
```

- [ ] **Step 4: Run to confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*RateLimiterServiceSpec*"`
Expected: PASS (all 3 tests).

- [ ] **Step 5: Commit**

```bash
git add rate-limiter/src/main/java/com/pura/ratelimiter/service/RateLimiterService.java rate-limiter/src/test/kotlin/com/pura/ratelimiter/service/RateLimiterServiceSpec.kt
git commit -m "Add RateLimiterService orchestrating store + rule registry"
```

---

### Task 5: `ConfigurationService` + configure DTOs

**Files:**
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/dto/ConfigureRequest.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/dto/ConfigureResponse.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/service/ConfigurationService.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/service/ConfigurationServiceSpec.kt`

**Interfaces:**
- Consumes: `RuleRegistry` setters (Task 3).
- Produces: `record ConfigureRequest(@Positive int windowSeconds, @Positive int requestPerWindow, String clientId, String resource)`;
  `record ConfigureResponse(int windowSeconds, int requestPerWindow, String clientId, String resource)`;
  `@Service class ConfigurationService` with `ConfigureResponse applyConfiguration(ConfigureRequest request)`.

- [ ] **Step 1: Write the failing tests**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/service/ConfigurationServiceSpec.kt`:

```kotlin
package com.pura.ratelimiter.service

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties
import com.pura.ratelimiter.dto.ConfigureRequest
import com.pura.ratelimiter.model.RateLimitRule
import com.pura.ratelimiter.repository.RuleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConfigurationServiceSpec : FunSpec({

    test("with no client_id or resource, sets the global default and echoes it back") {
        val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
        val service = ConfigurationService(registry)

        val response = service.applyConfiguration(ConfigureRequest(10, 5, null, null))

        response.windowSeconds() shouldBe 10
        response.requestPerWindow() shouldBe 5
        response.clientId() shouldBe null
        response.resource() shouldBe null
        registry.resolveRule("any-client", "any-resource") shouldBe RateLimitRule(10, 5)
    }

    test("with only resource set, sets a resource override") {
        val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
        val service = ConfigurationService(registry)

        service.applyConfiguration(ConfigureRequest(10, 5, null, "sensitive"))

        registry.resolveRule("any-client", "sensitive") shouldBe RateLimitRule(10, 5)
        registry.resolveRule("any-client", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("with only client_id set, sets a client override") {
        val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
        val service = ConfigurationService(registry)

        service.applyConfiguration(ConfigureRequest(10, 5, "premium-client", null))

        registry.resolveRule("premium-client", "orders") shouldBe RateLimitRule(10, 5)
        registry.resolveRule("other-client", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("with both client_id and resource set, sets a client+resource override") {
        val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
        val service = ConfigurationService(registry)

        service.applyConfiguration(ConfigureRequest(1, 1, "client-a", "sensitive"))

        registry.resolveRule("client-a", "sensitive") shouldBe RateLimitRule(1, 1)
        registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)
    }
})
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :rate-limiter:test --tests "*ConfigurationServiceSpec*"`
Expected: FAIL — `ConfigureRequest` and `ConfigurationService` don't exist yet.

- [ ] **Step 3: Implement the DTOs**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/dto/ConfigureRequest.java`:

```java
package com.pura.ratelimiter.dto;

import jakarta.validation.constraints.Positive;

public record ConfigureRequest(
        @Positive int windowSeconds, @Positive int requestPerWindow, String clientId, String resource) {
}
```

Create `rate-limiter/src/main/java/com/pura/ratelimiter/dto/ConfigureResponse.java`:

```java
package com.pura.ratelimiter.dto;

public record ConfigureResponse(int windowSeconds, int requestPerWindow, String clientId, String resource) {
}
```

- [ ] **Step 4: Implement `ConfigurationService`**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/service/ConfigurationService.java`:

```java
package com.pura.ratelimiter.service;

import com.pura.ratelimiter.dto.ConfigureRequest;
import com.pura.ratelimiter.dto.ConfigureResponse;
import com.pura.ratelimiter.model.RateLimitRule;
import com.pura.ratelimiter.repository.RuleRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigurationService {

    private final RuleRegistry ruleRegistry;

    public ConfigureResponse applyConfiguration(ConfigureRequest request) {
        var rule = new RateLimitRule(request.windowSeconds(), request.requestPerWindow());
        boolean hasClient = request.clientId() != null && !request.clientId().isBlank();
        boolean hasResource = request.resource() != null && !request.resource().isBlank();

        if (hasClient && hasResource) {
            ruleRegistry.setClientResourceOverride(request.clientId(), request.resource(), rule);
        } else if (hasClient) {
            ruleRegistry.setClientOverride(request.clientId(), rule);
        } else if (hasResource) {
            ruleRegistry.setResourceOverride(request.resource(), rule);
        } else {
            ruleRegistry.setGlobalDefault(rule);
        }

        return new ConfigureResponse(
                request.windowSeconds(), request.requestPerWindow(), request.clientId(), request.resource());
    }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*ConfigurationServiceSpec*"`
Expected: PASS (all 4 tests).

- [ ] **Step 6: Commit**

```bash
git add rate-limiter/src/main/java/com/pura/ratelimiter/dto/ConfigureRequest.java rate-limiter/src/main/java/com/pura/ratelimiter/dto/ConfigureResponse.java rate-limiter/src/main/java/com/pura/ratelimiter/service/ConfigurationService.java rate-limiter/src/test/kotlin/com/pura/ratelimiter/service/ConfigurationServiceSpec.kt
git commit -m "Add ConfigurationService applying scoped rate-limit overrides"
```

---

### Task 6: Error handling + `ConfigureController`

**Files:**
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/dto/ErrorResponse.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/exception/GlobalExceptionHandler.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/controller/ConfigureController.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/controller/ConfigureControllerSpec.kt`

**Interfaces:**
- Consumes: `ConfigurationService.applyConfiguration(ConfigureRequest)` (Task 5).
- Produces: `POST /api/v1/configure` HTTP endpoint; `record ErrorResponse(String error)`;
  a `@RestControllerAdvice` that maps `MethodArgumentNotValidException` to `400`
  (reused as-is by Task 7's controller too).

- [ ] **Step 1: Write the failing tests**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/controller/ConfigureControllerSpec.kt`:

```kotlin
package com.pura.ratelimiter.controller

import com.pura.ratelimiter.dto.ConfigureResponse
import com.pura.ratelimiter.service.ConfigurationService
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(ConfigureController::class)
@ApplyExtension(SpringExtension::class)
class ConfigureControllerSpec : FunSpec() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var configurationService: ConfigurationService

    init {
        test("sets the global default and echoes it back with 200") {
            `when`(configurationService.applyConfiguration(any())).thenReturn(
                ConfigureResponse(10, 5, null, null),
            )

            mockMvc.perform(
                post("/api/v1/configure")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"window_seconds":10,"request_per_window":5}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.window_seconds").value(10))
                .andExpect(jsonPath("$.request_per_window").value(5))
        }

        test("rejects a non-positive window_seconds with 400") {
            mockMvc.perform(
                post("/api/v1/configure")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"window_seconds":0,"request_per_window":5}"""),
            )
                .andExpect(status().isBadRequest)
        }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :rate-limiter:test --tests "*ConfigureControllerSpec*"`
Expected: FAIL — `ConfigureController` doesn't exist yet.

- [ ] **Step 3: Implement `ErrorResponse` and `GlobalExceptionHandler`**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/dto/ErrorResponse.java`:

```java
package com.pura.ratelimiter.dto;

public record ErrorResponse(String error) {
}
```

Create `rate-limiter/src/main/java/com/pura/ratelimiter/exception/GlobalExceptionHandler.java`:

```java
package com.pura.ratelimiter.exception;

import com.pura.ratelimiter.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationFailure(MethodArgumentNotValidException exception) {
        var message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Invalid request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(message));
    }
}
```

- [ ] **Step 4: Implement `ConfigureController`**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/controller/ConfigureController.java`:

```java
package com.pura.ratelimiter.controller;

import com.pura.ratelimiter.dto.ConfigureRequest;
import com.pura.ratelimiter.dto.ConfigureResponse;
import com.pura.ratelimiter.service.ConfigurationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ConfigureController {

    private final ConfigurationService configurationService;

    @PostMapping("/api/v1/configure")
    public ConfigureResponse configure(@Valid @RequestBody ConfigureRequest request) {
        return configurationService.applyConfiguration(request);
    }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*ConfigureControllerSpec*"`
Expected: PASS (both tests).

- [ ] **Step 6: Commit**

```bash
git add rate-limiter/src/main/java/com/pura/ratelimiter/dto/ErrorResponse.java rate-limiter/src/main/java/com/pura/ratelimiter/exception rate-limiter/src/main/java/com/pura/ratelimiter/controller/ConfigureController.java rate-limiter/src/test/kotlin/com/pura/ratelimiter/controller/ConfigureControllerSpec.kt
git commit -m "Add /api/v1/configure endpoint with validation error handling"
```

---

### Task 7: `RateLimitController`

**Files:**
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/dto/RateLimitRequest.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/dto/RateLimitResponse.java`
- Create: `rate-limiter/src/main/java/com/pura/ratelimiter/controller/RateLimitController.java`
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/controller/RateLimitControllerSpec.kt`

**Interfaces:**
- Consumes: `RateLimiterService.checkRateLimit(String, String)` (Task 4);
  `GlobalExceptionHandler` (Task 6, reused as-is).
- Produces: `POST /api/v1/ratelimit` HTTP endpoint.

- [ ] **Step 1: Write the failing tests**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/controller/RateLimitControllerSpec.kt`:

```kotlin
package com.pura.ratelimiter.controller

import com.pura.ratelimiter.model.RateLimitDecision
import com.pura.ratelimiter.service.RateLimiterService
import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(RateLimitController::class)
@ApplyExtension(SpringExtension::class)
class RateLimitControllerSpec : FunSpec() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockitoBean
    lateinit var rateLimiterService: RateLimiterService

    init {
        test("returns 200 with allowed=true when under the limit") {
            `when`(rateLimiterService.checkRateLimit(eq("client-a"), eq("orders")))
                .thenReturn(RateLimitDecision(true, 4, null))

            mockMvc.perform(
                post("/api/v1/ratelimit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"client_id":"client-a","resource":"orders"}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remaining").value(4))
                .andExpect(jsonPath("$.retry_after").doesNotExist())
        }

        test("returns 200 (not 429) with allowed=false and retry_after when over the limit") {
            `when`(rateLimiterService.checkRateLimit(eq("client-a"), eq("orders")))
                .thenReturn(RateLimitDecision(false, 0, 6L))

            mockMvc.perform(
                post("/api/v1/ratelimit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"client_id":"client-a","resource":"orders"}"""),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.retry_after").value(6))
        }

        test("rejects a request with a blank client_id with 400") {
            mockMvc.perform(
                post("/api/v1/ratelimit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"client_id":"","resource":"orders"}"""),
            )
                .andExpect(status().isBadRequest)
        }
    }
}
```

- [ ] **Step 2: Run to confirm it fails**

Run: `./gradlew :rate-limiter:test --tests "*RateLimitControllerSpec*"`
Expected: FAIL — `RateLimitController` doesn't exist yet.

- [ ] **Step 3: Implement the DTOs**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/dto/RateLimitRequest.java`:

```java
package com.pura.ratelimiter.dto;

import jakarta.validation.constraints.NotBlank;

public record RateLimitRequest(@NotBlank String clientId, @NotBlank String resource) {
}
```

Create `rate-limiter/src/main/java/com/pura/ratelimiter/dto/RateLimitResponse.java`:

```java
package com.pura.ratelimiter.dto;

public record RateLimitResponse(boolean allowed, int remaining, Long retryAfter) {
}
```

- [ ] **Step 4: Implement `RateLimitController`**

Create `rate-limiter/src/main/java/com/pura/ratelimiter/controller/RateLimitController.java`:

```java
package com.pura.ratelimiter.controller;

import com.pura.ratelimiter.dto.RateLimitRequest;
import com.pura.ratelimiter.dto.RateLimitResponse;
import com.pura.ratelimiter.service.RateLimiterService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RateLimitController {

    private final RateLimiterService rateLimiterService;

    @PostMapping("/api/v1/ratelimit")
    public RateLimitResponse checkRateLimit(@Valid @RequestBody RateLimitRequest request) {
        var decision = rateLimiterService.checkRateLimit(request.clientId(), request.resource());
        return new RateLimitResponse(decision.allowed(), decision.remaining(), decision.retryAfterSeconds());
    }
}
```

- [ ] **Step 5: Run to confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*RateLimitControllerSpec*"`
Expected: PASS (all 3 tests).

- [ ] **Step 6: Commit**

```bash
git add rate-limiter/src/main/java/com/pura/ratelimiter/dto/RateLimitRequest.java rate-limiter/src/main/java/com/pura/ratelimiter/dto/RateLimitResponse.java rate-limiter/src/main/java/com/pura/ratelimiter/controller/RateLimitController.java rate-limiter/src/test/kotlin/com/pura/ratelimiter/controller/RateLimitControllerSpec.kt
git commit -m "Add /api/v1/ratelimit endpoint"
```

---

### Task 8: End-to-end integration spec

**Files:**
- Test: `rate-limiter/src/test/kotlin/com/pura/ratelimiter/RateLimiterIntegrationSpec.kt`

**Interfaces:**
- Consumes: the full running application (Tasks 1–7) over real HTTP.
- Produces: nothing new — this is a pure verification task.

- [ ] **Step 1: Write the integration spec**

Create `rate-limiter/src/test/kotlin/com/pura/ratelimiter/RateLimiterIntegrationSpec.kt`:

```kotlin
package com.pura.ratelimiter

import io.kotest.core.extensions.ApplyExtension
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ApplyExtension(SpringExtension::class)
class RateLimiterIntegrationSpec : FunSpec() {

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @LocalServerPort
    var port: Int = 0

    private fun jsonEntity(body: String): HttpEntity<String> {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_JSON
        return HttpEntity(body, headers)
    }

    init {
        test("configuring a client+resource override then exceeding it blocks with 200 and allowed=false") {
            val configureResponse = restTemplate.postForEntity(
                "http://localhost:$port/api/v1/configure",
                jsonEntity(
                    """{"window_seconds":10,"request_per_window":2,"client_id":"integration-client","resource":"orders"}""",
                ),
                String::class.java,
            )
            configureResponse.statusCode.value() shouldBe 200

            val ratelimitEntity = jsonEntity("""{"client_id":"integration-client","resource":"orders"}""")
            val url = "http://localhost:$port/api/v1/ratelimit"

            val first = restTemplate.postForEntity(url, ratelimitEntity, String::class.java)
            val second = restTemplate.postForEntity(url, ratelimitEntity, String::class.java)
            val third = restTemplate.postForEntity(url, ratelimitEntity, String::class.java)

            first.statusCode.value() shouldBe 200
            first.body!! shouldContain "\"allowed\":true"
            second.body!! shouldContain "\"allowed\":true"
            third.statusCode.value() shouldBe 200
            third.body!! shouldContain "\"allowed\":false"
        }
    }
}
```

- [ ] **Step 2: Run and confirm it passes**

Run: `./gradlew :rate-limiter:test --tests "*RateLimiterIntegrationSpec*"`
Expected: PASS. (If it fails, it's exercising real wiring across every prior
task — read the failure carefully before changing anything from Tasks 1–7.)

- [ ] **Step 3: Commit**

```bash
git add rate-limiter/src/test/kotlin/com/pura/ratelimiter/RateLimiterIntegrationSpec.kt
git commit -m "Add end-to-end integration spec"
```

---

### Task 9: Tooling verification, README, and harness run

**Files:**
- Create: `rate-limiter/README.md`
- Modify: any files flagged by Spotless/SpotBugs in the step below

**Interfaces:**
- Consumes: the complete application (Tasks 1–8).
- Produces: a submission-ready `rate-limiter/` directory.

- [ ] **Step 1: Run the full check task**

Run: `./gradlew :rate-limiter:check`
Expected: runs all tests + Spotless (`spotlessCheck`) + SpotBugs
(`spotbugsMain`, `spotbugsTest`). If Spotless reports unformatted files, run
`./gradlew :rate-limiter:spotlessApply` and re-run `check`. If SpotBugs
reports findings, fix them in the flagged file (do not suppress) and re-run.

- [ ] **Step 2: Write `rate-limiter/README.md`**

Create `rate-limiter/README.md`:

```markdown
# Rate Limiter

Spring Boot implementation of the Pura Rate Limiter API challenge. See
[`docs/design.md`](docs/design.md) for the design and trade-offs write-up.

## Prerequisites

- JDK 25 (the Gradle toolchain will use a local JDK 25 if present, or
  download one automatically otherwise)

## Build

```bash
./gradlew :rate-limiter:build
```

## Run

```bash
./gradlew :rate-limiter:bootRun
```

Starts the server on port 8080. To use a different port:

```bash
./gradlew :rate-limiter:bootRun --args='--server.port=8090'
```

## Test

```bash
./gradlew :rate-limiter:test
```

Runs all Kotest specs (unit, controller slice, and one full integration
spec). `./gradlew :rate-limiter:check` additionally runs Spotless and
SpotBugs.

## Running the grading test-harness against this service

From the repo root, in one terminal:

```bash
./gradlew :rate-limiter:bootRun
```

In another terminal:

```bash
cd test-harness
npm install
npm start -- --host localhost --port 8080
```
```

- [ ] **Step 3: Commit**

```bash
git add rate-limiter/README.md
git commit -m "Add rate-limiter README with build/run/test instructions"
```

- [ ] **Step 4: Run the grading test-harness against the live service**

Run (terminal 1): `./gradlew :rate-limiter:bootRun`
Run (terminal 2): `cd test-harness && npm install && npm start -- --host localhost --port 8080`
Expected: all 5 scored categories pass; note the printed latency/throughput
numbers against the rubric's tiers (see "Verification" section above this
plan) and come back to optimize if they land below the top tier.

---

## Self-review notes

- **Spec coverage**: every requirement in `rate-limiter/docs/design.md` maps
  to a task above — algorithm (Task 2), config precedence (Task 3),
  orchestration (Task 4), the `/configure` extension (Task 5–6), both
  endpoints (Tasks 6–7), the `200`-always contract (Task 7, verified in its
  own test), integration coverage (Task 8), tooling + docs (Task 1, Task 9).
- **Type consistency checked**: `RateLimitDecision.retryAfterSeconds()` (Task
  2) is what `RateLimitController` (Task 7) reads and maps to
  `RateLimitResponse.retryAfter`; `RuleRegistry`'s four setter names (Task 3)
  are exactly what `ConfigurationService` (Task 5) calls; `RateLimitStore`'s
  `recordAndCheck(String, RateLimitRule, Instant)` signature (Task 2) is
  exactly what `RateLimiterService` (Task 4) calls.
- **Placeholder scan**: no TODOs; every step has real, complete code.
