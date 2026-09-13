package com.pura.ratelimiter.service

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties
import com.pura.ratelimiter.dto.ConfigureRequest
import com.pura.ratelimiter.model.RateLimitRule
import com.pura.ratelimiter.repository.RuleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ConfigurationServiceSpec :
  FunSpec({

    test("with no client_id or resource, sets the global default and echoes it back") {
      val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
      val service = ConfigurationService(registry)

      val response = service.applyConfiguration(ConfigureRequest(10, 5, null, null))

      response.windowSeconds() shouldBe 10
      response.requestPerWindow() shouldBe 5
      response.clientId() shouldBe null
      response.resource() shouldBe null
      registry.resolveRule("any-client", "any-resource") shouldBe RateLimitRule(10, 5)
    }

    test("with only resource set, sets a resource override") {
      val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
      val service = ConfigurationService(registry)

      service.applyConfiguration(ConfigureRequest(10, 5, null, "sensitive"))

      registry.resolveRule("any-client", "sensitive") shouldBe RateLimitRule(10, 5)
      registry.resolveRule("any-client", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("with only client_id set, sets a client override") {
      val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
      val service = ConfigurationService(registry)

      service.applyConfiguration(ConfigureRequest(10, 5, "premium-client", null))

      registry.resolveRule("premium-client", "orders") shouldBe RateLimitRule(10, 5)
      registry.resolveRule("other-client", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("with both client_id and resource set, sets a client+resource override") {
      val registry = RuleRegistry(RateLimiterDefaultsProperties(60, 100))
      val service = ConfigurationService(registry)

      service.applyConfiguration(ConfigureRequest(1, 1, "client-a", "sensitive"))

      registry.resolveRule("client-a", "sensitive") shouldBe RateLimitRule(1, 1)
      registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)
    }
  })
