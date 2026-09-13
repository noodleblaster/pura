# Rate Limiter — Design & Trade-offs

This document describes the design of this submission for the Pura Rate
Limiter API Challenge, and the trade-offs behind each significant decision.
For build/run/test instructions, see [`../README.md`](../README.md).

## Overview

The service is a Spring Boot (Java) application exposing the two endpoints
specified in the challenge:

- `POST /api/v1/ratelimit` — check whether a request from a given client, for
  a given resource, should be allowed.
- `POST /api/v1/configure` — set or update rate-limit configuration.

Rate limiting is implemented as an in-memory **sliding window log**: for each
distinct `(client_id, resource)` pair, the service keeps the timestamps of
recent admitted requests and evaluates the limit against however many of
those timestamps still fall inside the current window.

## Resolving a spec ambiguity: per-client limits vs. the `/configure` contract

The challenge's functional requirements ask for "different rate limits for
different clients," but the documented `/configure` request body —
`{window_seconds, request_per_window}` — has no field to address a specific
client or resource. Taken literally, there would be no way to ever set a
client-specific limit.

This submission resolves the ambiguity by **extending** `/configure` with
two optional fields, `client_id` and `resource`:

- Neither field present → sets the **global default** rule. This is exactly
  the request/response shape shown in the spec, so a client that only ever
  sends the documented body sees the documented behavior.
- Either or both present → sets an **override** scoped to that client and/or
  resource, layered on top of the global default.

Lookup precedence when a request comes in, most specific first:

1. `client_id` + `resource` exact match
2. `client_id` only
3. `resource` only
4. Global default

This keeps the documented contract intact as the default path while making
the "different limits per client" requirement actually satisfiable. The
`/configure` response echoes back whichever fields were set, so the caller
can see exactly what scope it applied to.

## Algorithm: sliding window log

Each `(client_id, resource)` key maps to a small ordered collection of
`Instant` timestamps — one per admitted request. On every check:

1. Evict timestamps older than `now - window_seconds`.
2. If the remaining count is below the configured limit: admit the request,
   record `now`, and report `remaining = limit - new_count`.
3. Otherwise: reject the request, report `remaining = 0`, and compute
   `retry_after` as the number of seconds until the *oldest* surviving
   timestamp ages out of the window — the earliest moment a slot frees up.

This was chosen over the alternatives for its precision and its direct match
to the spec's semantics (`retry_after` maps naturally onto "when does the
oldest request expire"):

- **Token bucket** models bursts and smooth refill well, but its notion of a
  "burst capacity" doesn't map cleanly onto the spec's literal "N requests
  per window" framing, and would need a less direct justification for
  `retry_after`.
- **Fixed window counter** is the simplest to implement (one counter + reset
  time per key), but has the well-known boundary problem: a client can send
  up to `2×limit` requests across a single window boundary. Given
  correctness is weighted heavily in the evaluation, this was ruled out.
- **Sliding window counter** (two fixed buckets with weighted interpolation)
  approximates the sliding window log in O (1) memory instead of
  O (requests-in-window), at the cost of a small error near window
  boundaries. This is the natural next optimization if memory became a
  concern at much higher scale (see Memory, below) — but for the scale this
  service targets, the log's exactness was preferred.

## Concurrency

Per-key state lives in a `ConcurrentHashMap<String, Deque<Instant>>`, keyed
by `"client_id:resource"`. Every check runs inside that map's `compute()`,
which guarantees the entire read-evict-decide-write sequence executes
atomically for a given key — no two threads can run the remapping function
for the same key at the same time. This gives exact, race-free enforcement
of the limit under concurrent load without any explicit lock objects to
create, hold, or forget to release. Different keys never contend with each
other, since `ConcurrentHashMap` synchronizes at the granularity of
individual bins, not the whole map.

The HTTP layer is conventional Spring MVC on the default Tomcat thread pool.
The rate-limit check itself is pure in-memory CPU work with no I/O, so
there's nothing for a non-blocking/reactive stack to win here — a
thread-per-request model comfortably meets the throughput and latency
targets without the added complexity of end-to-end reactive types.

One boundary worth stating explicitly: `RateLimiterService.checkRateLimit`'s
two steps — `resolveRule` then `recordAndCheck` — are not jointly atomic
with a concurrent `/configure` call, so a request landing exactly during a
reconfiguration may be evaluated under the old rule; this is benign and
arguably correct (config changes aren't meant to be transactional with
checks), but it is a real limit on the "exact" claims made elsewhere in this
doc.

## Memory usage

Steady-state memory is bounded, not unbounded: each `(client_id, resource)`
key holds at most `request_per_window` timestamps (older entries are evicted
on every access, and no more than the limit is ever admitted per window), so
total memory is `O(active_keys × request_per_window)`. Keys for clients that
stop sending requests are never proactively cleaned up, but they hold at
most one window's worth of small timestamp objects — the sliding window
counter algorithm mentioned above would flatten each key to O (1) if this
ever needed to shrink further at very large client counts, at the cost of
the exactness this design otherwise prioritizes.

Note that switching algorithms alone doesn't bound the *number* of distinct
keys: `client_id`/`resource`/override-map entries all grow with cardinality,
so unbounded distinct clients or resources still mean unbounded map entries;
a size- or TTL-bounded cache (e.g. Caffeine with `expireAfterAccess`) would
be the next step if that mattered at scale.

## Extensibility

The store is defined behind a `RateLimitStore` interface; `InMemoryRateLimitStore`
(the `ConcurrentHashMap`-backed implementation described above) is the only
implementation needed to satisfy the challenge's "no required external
dependencies" constraint, but the seam is real: a Redis-backed
implementation — e.g. a sorted set per key plus a Lua script for the
atomic evict-and-check — would be a second class implementing the same
interface, with no change to the service or controller layers.

## Error handling

Both endpoints validate their input via Bean Validation: `client_id` and
`resource` must be non-blank on `/ratelimit`; `window_seconds` and
`request_per_window` must be positive on `/configure`. A single
`@ControllerAdvice` maps validation failures and invalid-configuration
errors to `400` with a structured error body.

One contract detail worth stating explicitly: `POST /api/v1/ratelimit`
always returns `200 OK`, including when `allowed: false` — a rejected
request is a normal, well-formed answer, not an error. Only malformed input
produces a `4xx`.

## Testing

- **Unit tests** for the core sliding-window logic: boundary/rollover
  correctness, `retry_after` calculation, and a concurrency test that fires
  many threads at a single key and asserts exactly the configured number are
  admitted.
- **Unit tests** for configuration precedence: all four lookup tiers, and
  that an override takes effect immediately on the next request.
- **Slice tests** against both controllers: request/response shapes, status
  codes, and validation-error paths.
- **One integration test** that boots the full Spring context on a random
  port and drives both endpoints over real HTTP, exercising the whole stack
  together rather than each layer in isolation.

## Tooling

- **Gradle (Kotlin DSL), multi-project build**, with all dependency versions
  centralized in `gradle/libs.versions.toml` rather than scattered across
  build scripts.
- **Java 25 (LTS)** via Gradle toolchain support; **Spring Boot 4.1.x**
  (Spring Framework 7).
- **Spotless** for consistent Java/Kotlin formatting, and an `.editorconfig`
  for baseline editor behavior — mirroring this repo's own use of Prettier
  for the TypeScript sample solution.
- **SpotBugs**, run as part of `./gradlew check`, specifically for its
  concurrency detectors (unsafe publication, inconsistent synchronization) —
  a direct, automated check on the thread-safety claims made above.
- **Kotest** for the test suite, running on the JUnit 5 platform.
