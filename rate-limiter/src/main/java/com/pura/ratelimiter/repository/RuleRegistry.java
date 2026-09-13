package com.pura.ratelimiter.repository;

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties;
import com.pura.ratelimiter.model.RateLimitRule;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

@Component
public class RuleRegistry {

  private final AtomicReference<RateLimitRule> globalDefault;
  private final ConcurrentHashMap<String, RateLimitRule> clientOverrides =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RateLimitRule> resourceOverrides =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, RateLimitRule> clientResourceOverrides =
      new ConcurrentHashMap<>();

  public RuleRegistry(RateLimiterDefaultsProperties defaults) {
    this.globalDefault =
        new AtomicReference<>(
            new RateLimitRule(defaults.windowSeconds(), defaults.requestPerWindow()));
  }

  public RateLimitRule resolveRule(String clientId, String resource) {
    RateLimitRule exact = clientResourceOverrides.get(clientResourceKey(clientId, resource));
    if (exact != null) {
      return exact;
    }
    RateLimitRule clientOnly = clientOverrides.get(clientId);
    if (clientOnly != null) {
      return clientOnly;
    }
    RateLimitRule resourceOnly = resourceOverrides.get(resource);
    if (resourceOnly != null) {
      return resourceOnly;
    }
    return globalDefault.get();
  }

  public void setGlobalDefault(RateLimitRule rule) {
    globalDefault.set(rule);
  }

  public void setClientOverride(String clientId, RateLimitRule rule) {
    clientOverrides.put(clientId, rule);
  }

  public void setResourceOverride(String resource, RateLimitRule rule) {
    resourceOverrides.put(resource, rule);
  }

  public void setClientResourceOverride(String clientId, String resource, RateLimitRule rule) {
    clientResourceOverrides.put(clientResourceKey(clientId, resource), rule);
  }

  /**
   * Builds the {@code "client_id:resource"} key used to address per-key state. Shared with {@link
   * com.pura.ratelimiter.service.RateLimiterService} so the join logic exists in exactly one place.
   */
  public static String clientResourceKey(String clientId, String resource) {
    return clientId + ":" + resource;
  }
}
