# Rate Limiter

Spring Boot implementation of the Pura Rate Limiter API challenge. See
[`docs/design.md`](docs/design.md) for the design and trade-offs write-up.

## Prerequisites

- JDK 25 (the Gradle toolchain will use a local JDK 25 if present, or
  download one automatically otherwise, via the Foojay toolchain resolver
  configured in `settings.gradle.kts`)

## Build

```bash
./gradlew :rate-limiter:build
```

The first build/run will download the Gradle 9.7.1 distribution (and a JDK
if one isn't already present locally), which may take a minute or two —
this is expected, not a hang.

## Run

```bash
./gradlew :rate-limiter:bootRun
```

Starts the server on port 8000. To use a different port:

```bash
./gradlew :rate-limiter:bootRun --args='--server.port=8090'
```

## API

```bash
curl -X POST http://localhost:8000/api/v1/ratelimit \
  -H 'Content-Type: application/json' \
  -d '{"client_id": "client-a", "resource": "orders"}'

curl -X POST http://localhost:8000/api/v1/configure \
  -H 'Content-Type: application/json' \
  -d '{"window_seconds": 60, "request_per_window": 100}'
```

`/configure` also accepts two optional fields, `client_id` and `resource`,
beyond the documented spec, to scope a rule to a specific client and/or
resource — see [`docs/design.md`](docs/design.md) for why.

## Test

```bash
./gradlew :rate-limiter:test
```

Runs all Kotest specs (unit, controller slice, and one full integration
spec). `./gradlew :rate-limiter:check` additionally runs Spotless and
SpotBugs.

## Running the test harness against this service

From the repo root, in one terminal:

```bash
./gradlew :rate-limiter:bootRun
```

In another terminal:

```bash
cd test-harness
npm install
npm start -- --host localhost --port 8000
```
