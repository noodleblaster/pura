package com.pura.ratelimiter.repository

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties
import com.pura.ratelimiter.model.RateLimitRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class RuleRegistrySpec :
  FunSpec({

    fun newRegistry() = RuleRegistry(RateLimiterDefaultsProperties(60, 100))

    test("falls back to the global default when no override is set") {
      newRegistry().resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("a resource-only override applies to every client for that resource") {
      val registry = newRegistry()
      registry.setResourceOverride("sensitive", RateLimitRule(10, 5))

      registry.resolveRule("client-a", "sensitive") shouldBe RateLimitRule(10, 5)
      registry.resolveRule("client-b", "sensitive") shouldBe RateLimitRule(10, 5)
      registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)
    }

    test("a client-only override applies to every resource for that client") {
      val registry = newRegistry()
      registry.setClientOverride("premium-client", RateLimitRule(1, 1000))

      registry.resolveRule("premium-client", "orders") shouldBe RateLimitRule(1, 1000)
      registry.resolveRule("premium-client", "sensitive") shouldBe RateLimitRule(1, 1000)
    }

    test("a client+resource override wins over both client-only and resource-only overrides") {
      val registry = newRegistry()
      registry.setClientOverride("client-a", RateLimitRule(10, 10))
      registry.setResourceOverride("sensitive", RateLimitRule(5, 5))
      registry.setClientResourceOverride("client-a", "sensitive", RateLimitRule(1, 1))

      registry.resolveRule("client-a", "sensitive") shouldBe RateLimitRule(1, 1)
      registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(10, 10)
      registry.resolveRule("client-b", "sensitive") shouldBe RateLimitRule(5, 5)
    }

    test("updating the global default takes effect immediately") {
      val registry = newRegistry()
      registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(60, 100)

      registry.setGlobalDefault(RateLimitRule(30, 50))

      registry.resolveRule("client-a", "orders") shouldBe RateLimitRule(30, 50)
    }
  })
