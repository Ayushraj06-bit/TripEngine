package com.rideflow.notification.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
class NotificationConfig {

  @Bean
  RedisMessageListenerContainer notificationListener(
      RedisConnectionFactory connections, RedisNotifications notifications) {
    RedisMessageListenerContainer container = new RedisMessageListenerContainer();
    container.setConnectionFactory(connections);
    container.addMessageListener(notifications, new ChannelTopic(RedisNotifications.CHANNEL));
    return container;
  }
}
