package com.Ratelimiter.config.redis;

import java.time.Duration;
import java.util.List;
import reactor.util.retry.Retry;

import reactor.core.publisher.Mono;

import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class RateLimiterRedisService {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List> rateLimitScript;

    public RateLimiterRedisService(ReactiveStringRedisTemplate redisTemplate) {

        this.redisTemplate = redisTemplate;

        this.rateLimitScript = new DefaultRedisScript<>();

        this.rateLimitScript.setScriptText("""
            local tokens = tonumber(redis.call('HGET', KEYS[1], 'tokens'))

            local lastRefill = tonumber(redis.call('HGET', KEYS[1], 'last_refill'))

            local capacity = tonumber(ARGV[1])

            local refillRate = tonumber(ARGV[2])

            local now = tonumber(ARGV[3])

            if tokens == nil then
                tokens = capacity
                lastRefill = now
            end

            local elapsed = now - lastRefill

            if elapsed > 0 then
                tokens = math.min(
                    capacity,
                    tokens + (elapsed / 1000.0 * refillRate)
                )

                lastRefill = now
            end

            local allowed = 0

            if tokens >= 1 then
                tokens = tokens - 1
                allowed = 1
            end

            local remaining = math.floor(tokens)

            local resetSeconds = 0

            if tokens < capacity then
                resetSeconds = math.ceil(
                    (capacity - tokens) / refillRate
                )
            end

            local retryAfterSeconds = 0

            if allowed == 0 then
                retryAfterSeconds = math.ceil(
                    (1 - tokens) / refillRate
                )
            end

            redis.call(
                'HSET',
                KEYS[1],
                'tokens', tokens,
                'last_refill', lastRefill
            )
            redis.call(
                'EXPIRE',
                 KEYS[1],
                 3600
           )

            return {
                allowed,
                remaining,
                resetSeconds,
                retryAfterSeconds
            }
            """);

        this.rateLimitScript.setResultType(List.class);
    }

    public Mono<RateLimitResult> allow(
            String clientId,
            long capacity,
            double refillRate) {

        String key = "rate-limit:bucket:{" + clientId + "}";

        long now = System.currentTimeMillis();

        return redisTemplate.execute(
                rateLimitScript,
                List.of(key),
                String.valueOf(capacity),
                String.valueOf(refillRate),
                String.valueOf(now)
        )
        .next()
        .map(result -> new RateLimitResult(
                ((Number) result.get(0)).longValue() == 1,
                ((Number) result.get(1)).longValue(),
                ((Number) result.get(2)).longValue(),
                ((Number) result.get(3)).longValue()
        ))
        .retryWhen(
                Retry.backoff(2, Duration.ofMillis(10))
        )
        .onErrorResume(error ->
                Mono.just(new RateLimitResult(false, 0, 0, 0))
        );
    }
}