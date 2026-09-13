# Rate Limiter API

A rate limiter service that controls the rate of requests to an API. This service implements a sliding window algorithm to track requests within a specified time window.

## Features

- Different rate limits for different clients (identified by a client ID)
- Configurable time windows (e.g., requests per minute, per hour)
- Configurable request limits within those windows
- Support for resource-specific rate limits
- Support for client+resource specific rate limits
- Statistics tracking for rate limiting decisions

## API Endpoints

### POST /api/v1/ratelimit

Check if a request should be allowed or rejected based on rate limits.

**Request:**
```json
{
  "client_id": "string",
  "resource": "string"
}
```

**Response (200 OK):**
```json
{
  "allowed": true|false,
  "remaining": integer,
  "retry_after": integer (seconds, only present when allowed is false)
}
```

## Installation

```bash
npm ci && npm start
```

## Configuration

The rate limiter can be configured in the `src/index.ts` file. The default configuration is:

```typescript
const defaultConfig: RateLimitConfig = {
  default: {
    requests_per_window: 100,
    window_size_ms: 60 * 1000 // 1 minute
  },
  clients: {
    // Example client-specific limits
    'high-volume': {
      requests_per_window: 1000,
      window_size_ms: 60 * 1000 // 1 minute
    },
    'low-volume': {
      requests_per_window: 10,
      window_size_ms: 60 * 1000 // 1 minute
    }
  },
  resources: {
    // Example resource-specific limits
    '/api/v1/sensitive': {
      requests_per_window: 10,
      window_size_ms: 60 * 1000 // 1 minute
    }
  },
  client_resources: {
    // Example client+resource specific limits
    'high-volume': {
      '/api/v1/sensitive': {
        requests_per_window: 50,
        window_size_ms: 60 * 1000 // 1 minute
      }
    }
  }
};
```

## Usage

### Running the Server

```bash
# Start the server
npm start

# Or run in development mode
npm run dev
```

The server will start on port 3000 by default. You can change the port by setting the `PORT` environment variable.

## Testing

```bash
# Run tests
npm test
```

## Design Choices and Trade-offs

### Sliding Window Algorithm

The rate limiter uses a sliding window algorithm to track requests within a time window. This approach provides more accurate rate limiting compared to fixed window algorithms, as it prevents request spikes at window boundaries.

### In-Memory Storage

The rate limiter uses in-memory storage to track request history. This provides low latency and high throughput, but has the following limitations:

- The rate limiter will lose state if the server restarts
- It may not scale well across multiple instances without additional synchronization

For production use, you might want to consider using a distributed cache like Redis to store request history.

### Thread Safety

The rate limiter is designed to be thread-safe, using JavaScript's single-threaded execution model. However, if you're running multiple instances of the service, you'll need to implement a distributed rate limiter using a shared cache.

### Performance Considerations

- The rate limiter is designed to have low latency (< 10ms per decision)
- It can handle high throughput (1000+ requests per second)
- The sliding window algorithm has O(n) time complexity for checking rate limits, where n is the number of requests in the window
- To improve performance for high-traffic scenarios, consider implementing a more efficient data structure like a circular buffer or using a distributed cache with atomic operations

## Future Improvements

- Add support for distributed rate limiting using Redis or another distributed cache
- Implement more efficient data structures for tracking request history
- Add support for rate limiting based on IP address or other identifiers
- Add support for more complex rate limiting rules (e.g., different limits for different HTTP methods)
- Add support for rate limiting based on request payload size or other attributes