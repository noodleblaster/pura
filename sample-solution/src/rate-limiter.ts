import { Request, Response, NextFunction } from 'express';
import { RateLimitConfig, RateLimitRule, RateLimitResponse, ConfigureRequest } from './types.js';

/**
 * A request record for tracking rate limiting
 */
interface RequestRecord {
  timestamp: number;
}

// Store request history for each client+resource combination
const requestHistory: Map<string, RequestRecord[]> = new Map();

// Global configuration that can be updated
let globalConfig: RateLimitConfig = {
  default: {
    requests_per_window: 5,
    window_seconds: 10
  },
  resources: {}
};

/**
 * Get a unique key for a client+resource combination
 */
function getKey(clientId: string, resource: string): string {
  return `${clientId}:${resource}`;
}

/**
 * Get the appropriate rate limit rule for a resource
 * All requests are treated equally regardless of client ID
 */
function getRuleForResource(
  config: RateLimitConfig,
  resource: string
): RateLimitRule {
  // Check for resource specific rule
  if (config.resources?.[resource]) {
    return config.resources[resource];
  }
  
  // Use default rule
  return config.default;
}

/**
 * Check if a request from a client for a resource should be allowed
 */
export function checkRateLimit(
  config: RateLimitConfig,
  clientId: string,
  resource: string
): RateLimitResponse {
  // Get the applicable rate limit rule
  const rule = getRuleForResource(config, resource);
  
  // Get the key for tracking this resource
  const key = getKey(clientId, resource);
  
  // Get current time
  const now = Date.now();
  
  // Initialize request history for this key if not exists
  if (!requestHistory.has(key)) {
    requestHistory.set(key, []);
  }
  
  // Get request history for this key
  const history = requestHistory.get(key)!;
  
  // Remove expired requests (older than the window size)
  const windowSizeMs = rule.window_seconds * 1000;
  const windowStart = now - windowSizeMs;
  const validRequests = history.filter(req => req.timestamp >= windowStart);
  requestHistory.set(key, validRequests);
  
  // Check if the request should be allowed
  if (validRequests.length < rule.requests_per_window) {
    // Request is allowed
    validRequests.push({ timestamp: now });
    requestHistory.set(key, validRequests);
    
    
    return {
      allowed: true,
      remaining: rule.requests_per_window - validRequests.length
    };
  } else {
    // Request is blocked
    
    // Calculate retry after time (when the oldest request will expire)
    const oldestRequest = validRequests[0];
    const windowSizeMs = rule.window_seconds * 1000;
    const retryAfter = Math.ceil((oldestRequest.timestamp + windowSizeMs - now) / 1000);
    
    return {
      allowed: false,
      remaining: 0,
      retry_after: retryAfter > 0 ? retryAfter : 1 // Ensure retry_after is at least 1 second
    };
  }
}


/**
 * Update the rate limiter configuration
 */
export function updateRateLimiterConfig(configRequest: ConfigureRequest): RateLimitConfig {
  // Update the default rule
  globalConfig.default = {
    requests_per_window: configRequest.request_per_window,
    window_seconds: configRequest.window_seconds
  };
  
  return globalConfig;
}

/**
 * Get the current rate limiter configuration
 */
export function getRateLimiterConfig(): RateLimitConfig {
  return globalConfig;
}

/**
 * Create a rate limiter middleware for Express
 */
export function createRateLimiterMiddleware(config: RateLimitConfig) {
  return (req: Request, res: Response, next: NextFunction) => {
    // Extract client ID and resource from request
    // This is just an example, you might want to extract these from headers, query params, etc.
    const clientId = req.headers['x-client-id'] as string || 'default';
    const resource = req.path;
    
    // Check if the request should be allowed
    const result = checkRateLimit(config, clientId, resource);
    
    // Set rate limit headers
    res.setHeader('X-RateLimit-Limit', getRuleForResource(config, resource).requests_per_window.toString());
    res.setHeader('X-RateLimit-Remaining', result.remaining.toString());
    
    if (!result.allowed) {
      // If request is not allowed, set retry-after header and return 429 Too Many Requests
      res.setHeader('Retry-After', result.retry_after!.toString());
      return res.status(429).json({
        error: 'Too Many Requests',
        retry_after: result.retry_after
      });
    }
    
    // Request is allowed, proceed to next middleware
    next();
  };
}