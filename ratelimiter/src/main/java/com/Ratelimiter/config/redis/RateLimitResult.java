package com.Ratelimiter.config.redis;


public record RateLimitResult(
        boolean allowed,
        long remaining,
        long resetSeconds,
        long retryAfterSeconds){
	
}