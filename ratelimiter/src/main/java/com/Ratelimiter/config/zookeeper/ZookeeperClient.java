package com.Ratelimiter.config.zookeeper;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ZookeeperClient {

    private final CuratorFramework client;

    public ZookeeperClient(
            @Value("${zookeeper.connect-string}") String connectString) {

        client = CuratorFrameworkFactory.newClient(
                connectString,
                new ExponentialBackoffRetry(1000, 3)
        );

        client.start();
    }

    public CuratorFramework getClient() {
        return client;
    }
}