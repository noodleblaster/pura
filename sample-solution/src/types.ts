/**
 * Types and interfaces for the rate limiter service
 */

// Request to check if a client's request should be allowed
export interface RateLimitRequest {
  client_id: string;
  resource: string;
}

// Response indicating if a request is allowed
export interface RateLimitResponse {
  allowed: boolean;
  remaining: number;
  retry_after?: number; // Only present when allowed is false
}


// Configuration for a rate limit rule
export interface RateLimitRule {
  requests_per_window: number;
  window_seconds: number;
}

// Request to configure rate limits
export interface ConfigureRequest {
  window_seconds: number;
  request_per_window: number;
}

// Response for configure request
export interface ConfigureResponse {
  window_seconds: number;
  request_per_window: number;
}

// Configuration for rate limits
export interface RateLimitConfig {
  default: RateLimitRule;
  resources?: {
    [resource: string]: RateLimitRule;
  };
}