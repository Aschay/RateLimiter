package com.Ratelimiter.gateway;

import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.Ratelimiter.config.redis.RateLimiterRedisService;
import com.Ratelimiter.config.zookeeper.RateLimitConfigWatcher;

@Component
public class RateLimitGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config> {

	private final RateLimiterRedisService redisRateLimiter;
	private final RateLimitConfigWatcher configWatcher;

	public RateLimitGatewayFilterFactory(RateLimiterRedisService redisRateLimiter,
			RateLimitConfigWatcher configWatcher) {

		super(Config.class);

		this.redisRateLimiter = redisRateLimiter;
		this.configWatcher = configWatcher;
	}

	@Override
	public GatewayFilter apply(Config config) {

		return (exchange, chain) -> {

			String clientId = exchange.getRequest().getHeaders().getFirst("X-Client-Id");

			if (clientId == null || clientId.isBlank()) {
				exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);

				return exchange.getResponse().setComplete();
			}

			long capacity = configWatcher.getCapacity();
			double refillRate = configWatcher.getRefillRate();

			return redisRateLimiter.allow(clientId, capacity, refillRate).flatMap(result -> {

				exchange.getResponse().getHeaders().add("X-RateLimit-Limit", String.valueOf(capacity));

				exchange.getResponse().getHeaders().add("X-RateLimit-Remaining", String.valueOf(result.remaining()));

				exchange.getResponse().getHeaders().add("X-RateLimit-Reset",
						String.valueOf(java.time.Instant.now().getEpochSecond() + result.resetSeconds()));

				if (!result.allowed()) {

					exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);

					exchange.getResponse().getHeaders().add("Retry-After", String.valueOf(result.retryAfterSeconds()));

					return exchange.getResponse().setComplete();
				}

				return chain.filter(exchange);
			});
		};
	}

	public static class Config {
	}
}
