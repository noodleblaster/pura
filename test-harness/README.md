# Rate Limiter Evaluator

This directory contains tools for evaluating candidate solutions to the Rate Limiter API Challenge.

## Test Harness

The `test_harness.ts` script is designed to test a candidate's rate limiter implementation against the requirements specified in the challenge. It performs a series of tests to verify:

1. Basic rate limiting functionality
2. Multiple clients with separate rate limits
3. Concurrent request handling
4. Statistics tracking

### Prerequisites

- Node.js (v14 or later)
- npm or yarn

### Setup

Install dependencies:

```bash
npm install
```

or

```bash
yarn install
```

### Usage

As an evaluator, you will:

1. Set up the candidate's rate limiter solution on your machine
2. Start the candidate's rate limiter service according to their instructions
3. Run the test harness against their service:

```bash
npm start -- --host localhost --port 8080
```

Adjust the host and port parameters to match where the candidate's service is running.

Replace `localhost` and `8080` with the appropriate host and port where the candidate's service is running.

### Test Details

#### Basic Rate Limiting

Tests that the rate limiter correctly allows a fixed number of requests (5) and then blocks subsequent requests. It also verifies that after a waiting period, new requests are allowed again.

#### Multiple Clients

Tests that different clients have separate rate limits and are tracked independently.

#### Concurrent Requests

Tests that the rate limiter correctly handles concurrent requests and maintains the rate limit even under load.

### Evaluation Criteria

When evaluating a candidate's solution, consider:

1. **Correctness**: Does it pass all the tests?
2. **Design**: Is the code well-structured and maintainable?
3. **Performance**: Does it handle concurrent requests efficiently?
4. **Documentation**: Is the solution well-documented?
5. **Code Quality**: Is the code clean, readable, and well-tested?

## Common Issues

If candidates are struggling with specific aspects of the challenge, here are some common issues:

1. **Thread Safety**: Many implementations fail to properly handle concurrent requests
2. **Rate Limit Reset**: Some implementations don't correctly reset the rate limit after the time window
4. **Error Handling**: Some implementations don't handle edge cases or invalid inputs properly