package com.pura.ratelimiter.repository

import com.pura.ratelimiter.model.RateLimitRule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InMemoryRateLimitStoreSpec :
  FunSpec({

    test("admits requests up to the limit and reports remaining count") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 3)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      val first = store.recordAndCheck("client-a:resource", rule, now)
      val second = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(1))
      val third = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(2))

      first.allowed() shouldBe true
      first.remaining() shouldBe 2
      second.remaining() shouldBe 1
      third.remaining() shouldBe 0
    }

    test("blocks requests once the limit is reached, with a positive retryAfterSeconds") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 1)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      store.recordAndCheck("client-a:resource", rule, now)
      val blocked = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(4))

      blocked.allowed() shouldBe false
      blocked.remaining() shouldBe 0
      blocked.retryAfterSeconds() shouldBe 6L
    }

    test("computes retryAfterSeconds by rounding up from sub-second precision, not truncating to whole seconds") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 1)
      val t0 = Instant.parse("2026-01-01T00:00:00.900Z")
      val t1 = t0.plusMillis(4200) // 2026-01-01T00:00:05.100Z

      store.recordAndCheck("client-a:resource", rule, t0)
      val blocked = store.recordAndCheck("client-a:resource", rule, t1)

      blocked.allowed() shouldBe false
      // true remaining = (0.900 + 10) - 5.100 = 5.8s -> rounds up to 6
      blocked.retryAfterSeconds() shouldBe 6L
    }

    test("admits again once the window rolls over") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 1)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      store.recordAndCheck("client-a:resource", rule, now)
      store.recordAndCheck("client-a:resource", rule, now.plusSeconds(5))
      val afterWindow = store.recordAndCheck("client-a:resource", rule, now.plusSeconds(11))

      afterWindow.allowed() shouldBe true
      afterWindow.remaining() shouldBe 0
    }

    test("tracks different keys independently") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 1)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      store.recordAndCheck("client-a:resource", rule, now).allowed() shouldBe true
      store.recordAndCheck("client-b:resource", rule, now).allowed() shouldBe true
    }

    test("admits exactly N concurrent requests to the same key and blocks the rest") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 5)
      val now = Instant.now()
      val threadCount = 20
      val allowedCount = AtomicInteger(0)
      val pool = Executors.newFixedThreadPool(threadCount)
      val ready = CountDownLatch(threadCount)
      val start = CountDownLatch(1)

      repeat(threadCount) {
        pool.submit {
          ready.countDown()
          start.await()
          val decision = store.recordAndCheck("client-concurrent:resource", rule, now)
          if (decision.allowed()) allowedCount.incrementAndGet()
        }
      }
      ready.await()
      start.countDown()
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)

      allowedCount.get() shouldBe 5
    }

    test("allows the request and does not throw when requestPerWindow is non-positive") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(10, 0)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      val decision = store.recordAndCheck("client-a:resource", rule, now)

      decision.allowed() shouldBe true
    }

    test("allows the request and does not throw when windowSeconds is non-positive") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(0, 5)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      val decision = store.recordAndCheck("client-a:resource", rule, now)

      decision.allowed() shouldBe true
    }

    test("allows the request and does not throw when both windowSeconds and requestPerWindow are negative") {
      val store = InMemoryRateLimitStore()
      val rule = RateLimitRule(-5, -3)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      val decision = store.recordAndCheck("client-a:resource", rule, now)

      decision.allowed() shouldBe true
    }

    test("does not affect subsequent requests for the same key once a valid rule is used") {
      val store = InMemoryRateLimitStore()
      val invalidRule = RateLimitRule(10, 0)
      val validRule = RateLimitRule(10, 1)
      val now = Instant.parse("2026-01-01T00:00:00Z")

      store.recordAndCheck("client-a:resource", invalidRule, now)
      val decision = store.recordAndCheck("client-a:resource", validRule, now.plusSeconds(1))

      decision.allowed() shouldBe true
      decision.remaining() shouldBe 0
    }
  })
