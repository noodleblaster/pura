package com.pura.ratelimiter.service;

import com.pura.ratelimiter.model.RateLimitDecision;
import com.pura.ratelimiter.repository.RateLimitStore;
import com.pura.ratelimiter.repository.RuleRegistry;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimiterService {

  private final RateLimitStore rateLimitStore;
  private final RuleRegistry ruleRegistry;

  public RateLimitDecision checkRateLimit(String clientId, String resource) {
    var rule = ruleRegistry.resolveRule(clientId, resource);
    var key = RuleRegistry.clientResourceKey(clientId, resource);
    return rateLimitStore.recordAndCheck(key, rule, Instant.now());
  }
}
