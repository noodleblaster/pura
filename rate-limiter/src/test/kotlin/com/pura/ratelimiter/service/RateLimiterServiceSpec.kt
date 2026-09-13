package com.pura.ratelimiter.service

import com.pura.ratelimiter.config.RateLimiterDefaultsProperties
import com.pura.ratelimiter.model.RateLimitRule
import com.pura.ratelimiter.repository.InMemoryRateLimitStore
import com.pura.ratelimiter.repository.RuleRegistry
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

class RateLimiterServiceSpec :
  FunSpec({

    fun newService() =
      RateLimiterService(
        InMemoryRateLimitStore(),
        RuleRegistry(RateLimiterDefaultsProperties(60, 2)),
      )

    test("allows requests up to the resolved limit, then blocks") {
      val service = newService()

      service.checkRateLimit("client-a", "orders").allowed() shouldBe true
      service.checkRateLimit("client-a", "orders").allowed() shouldBe true
      service.checkRateLimit("client-a", "orders").allowed() shouldBe false
    }

    test("tracks different clients independently for the same resource") {
      val service = newService()
      service.checkRateLimit("client-a", "orders")
      service.checkRateLimit("client-a", "orders")

      service.checkRateLimit("client-b", "orders").allowed() shouldBe true
    }

    test("tracks different resources independently for the same client") {
      val service = newService()
      service.checkRateLimit("client-a", "orders")
      service.checkRateLimit("client-a", "orders")

      service.checkRateLimit("client-a", "billing").allowed() shouldBe true
    }

    test("a rule change takes effect immediately for a key with existing timestamps") {
      val ruleRegistry = RuleRegistry(RateLimiterDefaultsProperties(60, 5))
      val service = RateLimiterService(InMemoryRateLimitStore(), ruleRegistry)

      // Log 2 of 5 slots under the starting rule.
      service.checkRateLimit("client-a", "orders").allowed() shouldBe true
      service.checkRateLimit("client-a", "orders").allowed() shouldBe true

      // Shrink the limit to 1 — below the 2 timestamps already logged for this key.
      ruleRegistry.setGlobalDefault(RateLimitRule(60, 1))

      val decision = service.checkRateLimit("client-a", "orders")
      decision.allowed() shouldBe false
      decision.retryAfterSeconds().shouldNotBeNull()
      decision.retryAfterSeconds()!!.shouldBeGreaterThan(0L)
    }
  })
