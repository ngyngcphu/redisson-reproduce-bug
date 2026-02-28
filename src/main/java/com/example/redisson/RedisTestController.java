package com.example.redisson;

import com.example.redisson.service.RedisTestService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
public class RedisTestController {
  private static final Logger log = LoggerFactory.getLogger(RedisTestController.class);
  private final RedisTestService service;
  private final ExecutorService executor = Executors.newFixedThreadPool(128);
  private final AtomicBoolean running = new AtomicBoolean(false);
  private final AtomicInteger keyPoolSize = new AtomicInteger(256);
  private final AtomicInteger sleepMs = new AtomicInteger(0);
  private volatile String hashTag = "";

  public RedisTestController(RedisTestService service) {
    this.service = service;
  }

  /**
   * Start load test
   * POST /load/start?concurrency=512&keyPool=256&sleepMs=0&hashTag=flood
   */
  @PostMapping("/load/start")
  public String startLoad(
      @RequestParam(defaultValue = "10") int concurrency,
      @RequestParam(defaultValue = "256") int keyPool,
      @RequestParam(defaultValue = "0") int sleepMs,
      @RequestParam(defaultValue = "") String hashTag) {

    if (running.getAndSet(true)) {
      return "Already running!";
    }
    keyPoolSize.set(Math.max(1, keyPool));
    this.sleepMs.set(Math.max(0, sleepMs));
    this.hashTag = hashTag == null ? "" : hashTag;

    service.resetMetrics();
    for (int i = 0; i < concurrency; i++) {
      executor.submit(this::runLoadLoop);
    }
    return String.format("Started %d threads (keyPool=%d, sleepMs=%d, hashTag=%s)",
        concurrency, keyPoolSize.get(), this.sleepMs.get(), this.hashTag);
  }

  /**
   * Stop load test
   * POST /load/stop
   */
  @PostMapping("/load/stop")
  public String stopLoad() {
    running.set(false);
    return "Stopping...";
  }

  /**
   * Manual test endpoint for spamming
   * GET /test?key=mykey
   * Returns: value or "ERROR: message"
   */
  @GetMapping("/test")
  public String test(@RequestParam String key) {
    try {
      String value = service.get(key);
      return value != null ? value : "NULL";
    } catch (Exception e) {
      return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  /**
   * Set value (helper for manual testing)
   * POST /set?key=mykey&value=myvalue
   */
  @PostMapping("/set")
  public String set(@RequestParam String key, @RequestParam String value) {
    try {
      service.set(key, value);
      return "OK";
    } catch (Exception e) {
      return "ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage();
    }
  }

  /**
   * Get metrics
   * GET /metrics
   */
  @GetMapping("/metrics")
  public Map<String, Object> metrics() {
    RedisTestService.Metrics m = service.getMetrics();
    return Map.of(
        "total", m.total(),
        "errors", m.errors(),
        "movedErrors", m.moved(),
        "nodeNotFoundErrors", m.nodeNotFound(),
        "errorRate", String.format("%.2f%%", m.rate()),
        "running", running.get());
  }

  private void runLoadLoop() {
    int counter = 0;
    while (running.get()) {
      try {
        int idx = Math.floorMod(counter++, keyPoolSize.get());
        String key = hashTag.isBlank()
            ? "key-" + idx
            : "{" + hashTag + "}:key-" + idx;

        // Multi-step operation that stresses Redis
        service.runMultiStepOperation(key, "value-" + idx);

        if (sleepMs.get() > 0) {
          Thread.sleep(sleepMs.get());
        }
      } catch (Exception e) {
        if (service.isRedisClusterError(e)) {
          log.warn("Redis cluster error: {}", e.getMessage());
        }
      }
    }
  }
}
