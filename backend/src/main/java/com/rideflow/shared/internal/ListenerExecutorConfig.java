package com.rideflow.shared.internal;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.ContextPropagatingTaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * The executor every {@code @Async} method runs on: above all, Spring Modulith's event listeners.
 *
 * <p>Platform threads, not virtual ones. On Java 21 a virtual thread that blocks inside a {@code
 * synchronized} block pins its carrier thread, and there are only as many carriers as cores. A
 * listener does database, Redis and Kafka work that can block that way, and on CI's two-core runner
 * a Phase 10 test saw every listener stand still for ten seconds at once. Thirty-two threads is
 * three times the connection pool, which caps the database work anyway.
 *
 * <p>Named {@code taskExecutor}, the name {@code @Async} looks for when several executors exist -
 * the WebSocket broker defines its own - and {@code applicationTaskExecutor}, the one Boot's own
 * consumers use; Boot then defines none. The decorator carries the caller's context across, so a
 * booking and the listeners it sets off stay one trace.
 */
@Configuration(proxyBeanMethods = false)
class ListenerExecutorConfig {

  private static final int THREADS = 32;

  @Bean(name = {"applicationTaskExecutor", "taskExecutor"})
  ThreadPoolTaskExecutor applicationTaskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(THREADS);
    executor.setMaxPoolSize(THREADS);
    executor.setThreadNamePrefix("listener-");
    executor.setTaskDecorator(new ContextPropagatingTaskDecorator());
    // On shutdown, running listeners finish. Any still queued stay incomplete in the event
    // publication registry, which republishes them on restart.
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(15);
    return executor;
  }
}
