package com.Ratelimiter.controller;

import com.Ratelimiter.config.redis.RateLimitResult;
import com.Ratelimiter.config.redis.RateLimiterRedisService;
import com.Ratelimiter.config.zookeeper.RateLimitConfigWatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class RateLimiterController {

    private final RateLimiterRedisService redisRateLimiter;
    private final RateLimitConfigWatcher configWatcher;

    public RateLimiterController(
            RateLimiterRedisService redisRateLimiter,
            RateLimitConfigWatcher configWatcher) {
        this.redisRateLimiter = redisRateLimiter;
        this.configWatcher = configWatcher;
    }

    @GetMapping("/rate-limit")
    public Mono<ResponseEntity<RateLimitResult>> rateLimit(
            @RequestParam String clientId) {

        return redisRateLimiter.allow(
                clientId,
                configWatcher.getCapacity(),
                configWatcher.getRefillRate()
        ).map(result -> {

            if (result.allowed()) {
                return ResponseEntity.ok(result);
            }

            return ResponseEntity.status(429).body(result);
        });
    }
}