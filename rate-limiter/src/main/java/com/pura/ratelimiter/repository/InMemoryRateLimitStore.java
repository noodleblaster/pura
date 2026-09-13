package com.pura.ratelimiter.repository;

import com.pura.ratelimiter.model.RateLimitDecision;
import com.pura.ratelimiter.model.RateLimitRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InMemoryRateLimitStore implements RateLimitStore {

  // Key = A map to limit bucket
  // Value = A Deque containing 1 timestamp per api request observed
  private final ConcurrentHashMap<String, Deque<Instant>> requestLog = new ConcurrentHashMap<>();

  @Override
  public RateLimitDecision recordAndCheck(String key, RateLimitRule rule, Instant now) {

    if (rule.windowSeconds() <= 0 || rule.requestPerWindow() <= 0) {
      // This block should never execute, since windowSeconds and requestPerWindow are
      // already validated on the config classes. However, since it's critical for the
      // health of the platform, we validate the rule here as well and alert if the
      // condition occurs. The implementation defaults to serving the request to avoid
      // any platform impact from a misconfiguration.
      // TODO: emit a metric/alert for non-positive rule values reaching the store.
      log.warn(
          "Non-positive rate limit rule for key={}: windowSeconds={}, requestPerWindow={}",
          key,
          rule.windowSeconds(),
          rule.requestPerWindow());
      return new RateLimitDecision(true, 0, null);
    }

    // Represents the oldest active timestamp in the deque.
    Instant windowStart = now.minusSeconds(rule.windowSeconds());

    // AtomicReference type is used here to have a "final" to retrieve return value out of the
    // compute() lambda.
    AtomicReference<RateLimitDecision> decision = new AtomicReference<>();

    requestLog.compute(
        key,
        (ignoredKey, existing) -> {

          // 1. Resolve active request timestamps.
          Deque<Instant> timestamps = existing == null ? new ArrayDeque<>() : existing;

          // 2. Prune timestamps from any requests older than the current window.
          //    The remaining timestamps represent active requests within the current window.
          while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
            timestamps.pollFirst();
          }

          if (timestamps.size() < rule.requestPerWindow()) {
            // 3. Total size is under the limit, add the request and capture an allowed decision.
            timestamps.addLast(now);
            decision.set(
                new RateLimitDecision(true, rule.requestPerWindow() - timestamps.size(), null));
          } else {
            // 4. Total size is over the limit, capture a disallowed decision.
            Instant oldest = timestamps.peekFirst();
            long retryAfter =
                oldest == null
                    ? rule.windowSeconds()
                    : Math.max(
                        1,
                        ceilSeconds(
                            Duration.between(now, oldest.plusSeconds(rule.windowSeconds()))));

            decision.set(new RateLimitDecision(false, 0, retryAfter));
          }
          return timestamps;
        });

    // Return the decision to caller.
    return decision.get();
  }

  // Duration.getNano() is always in [0, 999_999_999] regardless of the duration's sign, so
  // adding 1 whenever it's non-zero yields a correct ceiling in both directions.
  private static long ceilSeconds(Duration duration) {
    long seconds = duration.getSeconds();
    return duration.getNano() > 0 ? seconds + 1 : seconds;
  }
}
