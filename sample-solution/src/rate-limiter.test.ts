import { describe, it } from 'node:test';
import assert from 'node:assert';
import { checkRateLimit } from './rate-limiter.js';
import { RateLimitConfig } from './types.js';

// Test configuration
const testConfig: RateLimitConfig = {
  default: {
    requests_per_window: 5,
    window_seconds: 1 // 1 second
  },
  resources: {
    '/api/v1/sensitive': {
      requests_per_window: 2,
      window_seconds: 1 // 1 second
    }
  }
};

describe('Rate Limiter', () => {
  it('should allow requests within the limit', () => {
    // Make 5 requests (within the default limit)
    for (let i = 0; i < 5; i++) {
      const result = checkRateLimit(testConfig, 'test-client', '/api/v1/resource');
      assert.strictEqual(result.allowed, true);
      assert.strictEqual(result.remaining, 5 - (i + 1));
    }
  });

  it('should block requests over the limit', () => {
    // Use a unique resource path for this test to ensure clean state
    const uniqueResource = '/api/v1/resource-limit-test';
    
    // Make 5 requests (within the default limit)
    for (let i = 0; i < 5; i++) {
      const result = checkRateLimit(testConfig, 'any-client', uniqueResource);
      assert.strictEqual(result.allowed, true);
    }

    // This request should be blocked (over the limit)
    const result = checkRateLimit(testConfig, 'any-client', uniqueResource);
    assert.strictEqual(result.allowed, false);
    assert.strictEqual(result.remaining, 0);
    assert.ok(result.retry_after !== undefined);
  });

  // Client-specific limits are no longer supported

  it('should apply resource-specific limits', () => {
    // Make 2 requests (within the sensitive resource limit)
    for (let i = 0; i < 2; i++) {
      const result = checkRateLimit(testConfig, 'test-client-3', '/api/v1/sensitive');
      assert.strictEqual(result.allowed, true);
    }

    // This request should be blocked
    const result = checkRateLimit(testConfig, 'test-client-3', '/api/v1/sensitive');
    assert.strictEqual(result.allowed, false);
  });

  // Client+resource specific limits are no longer supported
});