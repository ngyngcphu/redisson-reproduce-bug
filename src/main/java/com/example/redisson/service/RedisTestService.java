package com.example.redisson.service;

import org.redisson.api.*;
import org.redisson.client.RedisException;
import org.redisson.client.RedisNodeNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class RedisTestService {
  private static final Logger log = LoggerFactory.getLogger(RedisTestService.class);
  private final RedissonClient client;
  private final AtomicLong totalOps = new AtomicLong(0);
  private final AtomicLong errorOps = new AtomicLong(0);
  private final AtomicLong movedErrors = new AtomicLong(0);
  private final AtomicLong nodeNotFoundErrors = new AtomicLong(0);

  public RedisTestService(RedissonClient client) {
    this.client = client;
  }

  public String get(String key) {
    totalOps.incrementAndGet();
    try {
      return (String) client.getBucket(key).get();
    } catch (Exception e) {
      errorOps.incrementAndGet();
      trackError(e);
      throw e;
    }
  }

  public void set(String key, String value) {
    totalOps.incrementAndGet();
    try {
      client.getBucket(key).set(value, Duration.ofSeconds(60));
    } catch (Exception e) {
      errorOps.incrementAndGet();
      trackError(e);
      throw e;
    }
  }

  /**
   * Multi-step operation that stresses Redis connection pool
   * and is sensitive to cluster topology changes
   */
  public void runMultiStepOperation(String key, String value) {
    totalOps.incrementAndGet();
    String mapKey = "test:map:" + key;
    RMap<String, String> map = client.getMap(mapKey);

    try {
      // Step 1: Check if exists
      String existing = map.putIfAbsent("data", value);
      if (existing == null) {
        map.expire(Duration.ofMinutes(5));
      }

      // Step 2: Additional Redis operation (stresses pool)
      client.getBucket("test:bucket:" + key + ":" + System.nanoTime())
          .set(value, Duration.ofSeconds(30));

      // Step 3: Update map
      map.fastPutIfAbsent("completed", "true");

    } catch (Exception e) {
      errorOps.incrementAndGet();
      trackError(e);

      // Attempt cleanup - if this fails too, we have a stuck state
      try {
        map.fastPutIfAbsent("error", e.getClass().getSimpleName());
      } catch (Exception cleanupEx) {
        log.error("Cleanup failed for key {}: {}", mapKey, cleanupEx.getMessage());
      }

      throw e;
    }
  }

  public boolean isRedisClusterError(Exception e) {
    return isMovedError(e) || isNodeNotFound(e);
  }

  public boolean isMovedError(Exception e) {
    return e instanceof RedisException
        && e.getMessage() != null
        && e.getMessage().contains("MOVED");
  }

  public boolean isNodeNotFound(Exception e) {
    return e instanceof RedisNodeNotFoundException
        || (e.getMessage() != null && e.getMessage().contains("RedisNodeNotFoundException"));
  }

  private void trackError(Exception e) {
    if (isMovedError(e)) {
      movedErrors.incrementAndGet();
    }
    if (isNodeNotFound(e)) {
      nodeNotFoundErrors.incrementAndGet();
    }
    // Log raw stack trace
    log.error("", e);
  }

  public Metrics getMetrics() {
    return new Metrics(
        totalOps.get(),
        errorOps.get(),
        movedErrors.get(),
        nodeNotFoundErrors.get());
  }

  public void resetMetrics() {
    totalOps.set(0);
    errorOps.set(0);
    movedErrors.set(0);
    nodeNotFoundErrors.set(0);
  }

  public record Metrics(long total, long errors, long moved, long nodeNotFound) {
    public double rate() {
      return total > 0 ? (double) errors / total * 100 : 0;
    }

    @Override
    public String toString() {
      return String.format("Metrics{total=%d, errors=%d, moved=%d, nodeNotFound=%d, rate=%.2f%%}",
          total, errors, moved, nodeNotFound, rate());
    }
  }
}
