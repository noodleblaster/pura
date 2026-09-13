package com.pura.ratelimiter.repository;

import com.pura.ratelimiter.model.RateLimitDecision;
import com.pura.ratelimiter.model.RateLimitRule;
import java.time.Instant;

public interface RateLimitStore {
  RateLimitDecision recordAndCheck(String key, RateLimitRule rule, Instant now);
}
