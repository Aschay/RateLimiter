package com.Ratelimiter.config.zookeeper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class RateLimitConfigWatcher {

    private final ZookeeperClient zooKeeperClient;

    private volatile int capacity;
    private volatile double refillRate;

    private CuratorCache cache;

    public RateLimitConfigWatcher(ZookeeperClient zooKeeperClient) {
        this.zooKeeperClient = zooKeeperClient;
    }

    @PostConstruct
    public void watch() {

        cache = CuratorCache.builder(
                zooKeeperClient.getClient(),
                "/rate-limiter/config"
        ).build();

        cache.listenable().addListener((type, oldData, data) -> {

            if (data == null) {
                return;
            }

            String path = data.getPath();

            String value = new String(
                    data.getData(),
                    StandardCharsets.UTF_8
            );

            if (path.endsWith("/capacity")) {
                capacity = Integer.parseInt(value);
            }

            if (path.endsWith("/refillRate")) {
                refillRate = Double.parseDouble(value);
            }

            System.out.println(
                    "capacity=" + capacity +
                    ", refillRate=" + refillRate
            );
        });

        cache.start();
    }

    public int getCapacity() {
        return capacity;
    }

    public double getRefillRate() {
        return refillRate;
    }

    @PreDestroy
    public void close() {
        cache.close();
    }
}