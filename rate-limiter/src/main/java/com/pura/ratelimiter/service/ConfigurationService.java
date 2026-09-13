package com.pura.ratelimiter.service;

import com.pura.ratelimiter.dto.ConfigureRequest;
import com.pura.ratelimiter.dto.ConfigureResponse;
import com.pura.ratelimiter.model.RateLimitRule;
import com.pura.ratelimiter.repository.RuleRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ConfigurationService {

  private final RuleRegistry ruleRegistry;

  public ConfigureResponse applyConfiguration(ConfigureRequest request) {
    var rule = new RateLimitRule(request.windowSeconds(), request.requestPerWindow());
    boolean hasClient = request.clientId() != null && !request.clientId().isBlank();
    boolean hasResource = request.resource() != null && !request.resource().isBlank();

    if (hasClient && hasResource) {
      ruleRegistry.setClientResourceOverride(request.clientId(), request.resource(), rule);
    } else if (hasClient) {
      ruleRegistry.setClientOverride(request.clientId(), rule);
    } else if (hasResource) {
      ruleRegistry.setResourceOverride(request.resource(), rule);
    } else {
      ruleRegistry.setGlobalDefault(rule);
    }

    return new ConfigureResponse(
        request.windowSeconds(),
        request.requestPerWindow(),
        request.clientId(),
        request.resource());
  }
}
