package com.rideflow.notification.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.scripting.support.ResourceScriptSource;
import tools.jackson.databind.json.JsonMapper;

@Configuration
class NotificationConfig {

  @Bean
  RedisMessageListenerContainer realtimeListener(
      RedisConnectionFactory connections,
      SimpUserRegistry users,
      SimpMessagingTemplate messaging,
      JsonMapper json) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connections);
    // A virtual thread per message, like the rest of the application. The container's own
    // default starts a platform thread for each, and every instance receives every message.
    SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor("rt-events-");
    executor.setVirtualThreads(true);
    container.setTaskExecutor(executor);
    container.addMessageListener(
        new LocalDelivery(users, messaging, json),
        new ChannelTopic(RedisRealtimePublisher.CHANNEL));
    return container;
  }

  @Bean
  RedisScript<Long> tripSequenceScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setScriptSource(
        new ResourceScriptSource(new ClassPathResource("redis/trip_sequence.lua")));
    script.setResultType(Long.class);
    return script;
  }
}
