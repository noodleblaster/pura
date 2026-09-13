# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is a rate limiter API coding challenge that tests the ability to design a system handling concurrency, state management, and providing a clean HTTP API interface. The repository contains a sample TypeScript/Express implementation and a test harness for evaluation.

## Repository Structure

- `sample-solution/` - Reference implementation in TypeScript using Express
- `test-harness/` - Test suite that validates rate limiter implementations via HTTP

## Common Development Commands

### Sample Solution (TypeScript/Express)
```bash
cd sample-solution
npm install                          # Install dependencies
npm start                            # Start server on port 3000
npm start -- --port 8080             # Start server on custom port
npm test                             # Run unit tests
npx prettier --write src/**/*.ts     # Format code
```

### Test Harness
```bash
cd test-harness
npm install                          # Install dependencies
npm start -- --host localhost --port 8080  # Run tests against a running server
```

### Running Tests End-to-End
1. In one terminal: `cd sample-solution && npm start -- --port 8080`
2. In another terminal: `cd test-harness && npm start -- --host localhost --port 8080`

## Architecture

### Rate Limiting Implementation

The sample solution uses a **sliding window** algorithm with in-memory storage:

- **Request tracking**: Each client+resource combination maintains a history of request timestamps in a Map (`client_id:resource` → `RequestRecord[]`)
- **Window cleanup**: On each request, expired entries (older than the window) are filtered out before evaluating the limit
- **Configuration**: Global default configuration plus optional per-resource overrides
- **Thread safety**: JavaScript's single-threaded event loop handles concurrency, but the implementation is designed to be correct under concurrent async requests

Key files:
- `sample-solution/src/rate-limiter.ts` - Core rate limiting logic (`checkRateLimit`, `updateRateLimiterConfig`)
- `sample-solution/src/index.ts` - Express server with two endpoints
- `sample-solution/src/types.ts` - TypeScript interfaces

### API Endpoints

**POST /api/v1/ratelimit** - Check if request should be allowed
- Input: `{ client_id: string, resource: string }`
- Output: `{ allowed: boolean, remaining: number, retry_after?: number }`

**POST /api/v1/configure** - Update rate limit configuration
- Input: `{ window_seconds: number, request_per_window: number }`
- Output: `{ window_seconds: number, request_per_window: number }`

### Test Harness Structure

The test harness (`test-harness/test_harness.ts`) validates:
1. Basic rate limiting (requests within/exceeding limits)
2. Multiple clients (separate rate limits per client)
3. Concurrent requests (thread safety)
4. Configuration changes (dynamic reconfiguration)
5. Performance (latency <10ms, throughput >1000 req/s)

Scoring rubric: 65 points total (40 functionality + 15 performance + 10 other criteria)

## TypeScript Configuration

Both projects use ES modules with TypeScript:
- `"type": "module"` in package.json (use .js extensions in imports)
- `"module": "NodeNext"` and `"moduleResolution": "NodeNext"` in tsconfig.json
- Run directly with `ts-node` using ESM loader: `node --loader ts-node/esm`
- Import statements must include `.js` extension (e.g., `from './types.js'`) even though source files are `.ts`

## Code Style

- Prettier configuration in `.prettierrc.json`
- Single quotes, semicolons, 120 character line width
- Format before committing: `npx prettier --write .`

## Implementation Notes

- The rate limiter is designed to work on a single machine without external dependencies (no Redis/database required)
- Request history cleanup happens lazily on each request (no background cleanup job)
- `retry_after` calculation: time until the oldest request in the window expires
- Resource-specific limits can be configured in addition to the global default
