package com.example.redisson;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SubscriptionMode;
import org.redisson.client.FailedConnectionDetector;
import org.redisson.connection.balancer.RoundRobinLoadBalancer;
import org.redisson.codec.JsonJacksonCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
  @Value("${redis.host}")
  private String redisHost;

  @Value("${redis.port:9736}")
  private int redisPort;

  @Value("${redis.password:}")
  private String redisPassword;

  @Bean
  public RedissonClient redissonClient() {
    Config config = new Config();
    config.setThreads(8);
    config.setNettyThreads(4);
    config.setCodec(new JsonJacksonCodec());

    config.useClusterServers()
        .addNodeAddress("redis://" + redisHost + ":" + redisPort)
        .setPassword(redisPassword.isEmpty() ? null : redisPassword)
        .setScanInterval(2000)
        .setSlaveConnectionMinimumIdleSize(10)
        .setSlaveConnectionPoolSize(10)
        .setMasterConnectionMinimumIdleSize(10)
        .setMasterConnectionPoolSize(10)
        .setIdleConnectionTimeout(10000)
        .setConnectTimeout(10000)
        .setTimeout(15000)
        .setRetryAttempts(3)
        .setRetryInterval(1000)
        .setFailedSlaveReconnectionInterval(3000)
        .setFailedSlaveNodeDetector(new FailedConnectionDetector())
        .setSubscriptionsPerConnection(5)
        .setSubscriptionConnectionMinimumIdleSize(1)
        .setSubscriptionConnectionPoolSize(50)
        .setReadMode(ReadMode.MASTER)
        .setSubscriptionMode(SubscriptionMode.MASTER)
        .setPingConnectionInterval(30000)
        .setKeepAlive(true)
        .setTcpNoDelay(true)
        .setLoadBalancer(new RoundRobinLoadBalancer());

    return Redisson.create(config);
  }
}
