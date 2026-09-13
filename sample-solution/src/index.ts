import express from 'express';
import { checkRateLimit, getRateLimiterConfig, updateRateLimiterConfig } from './rate-limiter.js';
import { RateLimitRequest, ConfigureRequest } from './types.js';

// Initialize the rate limiter with resource-specific limits
(function initializeRateLimiter() {
  const config = getRateLimiterConfig();
  
  // Add example resource-specific limits
  config.resources = {
    '/api/v1/sensitive': {
      requests_per_window: 2,
      window_seconds: 10
    }
  };
})();

// Create Express app
const app = express();
app.use(express.json());

// Apply rate limiter middleware to all routes
// This is an example of how you could use the middleware for all routes
// app.use(createRateLimiterMiddleware(defaultConfig));

// POST /api/v1/ratelimit - Check if a request should be allowed or rejected
app.post('/api/v1/ratelimit', (req, res) => {
  const { client_id, resource } = req.body as RateLimitRequest;
  
  if (!client_id || !resource) {
    return res.status(400).json({
      error: 'Bad Request',
      message: 'client_id and resource are required'
    });
  }
  
  const result = checkRateLimit(getRateLimiterConfig(), client_id, resource);
  
  return res.status(200).json(result);
});

// POST /api/v1/configure - Set or update the ratelimiting configuration
app.post('/api/v1/configure', (req, res) => {
  const { window_seconds, request_per_window } = req.body as ConfigureRequest;
  
  if (window_seconds === undefined || request_per_window === undefined) {
    return res.status(400).json({
      error: 'Bad Request',
      message: 'window_seconds and request_per_window are required'
    });
  }
  
  // Validate input
  if (typeof window_seconds !== 'number' || window_seconds <= 0 ||
      typeof request_per_window !== 'number' || request_per_window <= 0) {
    return res.status(400).json({
      error: 'Bad Request',
      message: 'window_seconds and request_per_window must be positive numbers'
    });
  }
  
  // Update configuration
  updateRateLimiterConfig({ window_seconds, request_per_window });
  
  // Return the updated configuration
  return res.status(200).json({
    window_seconds,
    request_per_window
  });
});


// Parse command line arguments
const args = process.argv.slice(2);
let port = 3000;

for (let i = 0; i < args.length; i++) {
  if (args[i] === '--port' && i + 1 < args.length) {
    port = parseInt(args[i + 1], 10);
    break;
  } else if (args[i].startsWith('--port=')) {
    port = parseInt(args[i].split('=')[1], 10);
    break;
  }
}

// Start the server
const PORT = process.env.PORT || port;
const server = app.listen(PORT, () => {
  console.log(`Rate limiter service listening on port ${PORT}`);
});

// Export app and server for testing
export default app;
export { server };