package com.rideflow.shared.internal;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Cluster-wide locking for scheduled jobs.
 *
 * <p>PROJECT_SPEC section 3 requires jobs that must run once per cluster to hold a ShedLock.
 * Without it, every backend instance would run the stale-driver sweep and the surge tick, so with
 * the two-instance profile the surge multiplier would step twice per tick and drift away from what
 * the configuration says.
 *
 * <p>The lock table is owned by Flyway, not created by ShedLock, per section 7.
 *
 * <p>{@code defaultLockAtMostFor} is the safety net for an instance that dies holding a lock: the
 * lock is released after it regardless. It is deliberately longer than any job's expected runtime;
 * individual jobs override it when they need something tighter.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30S")
class ShedLockConfig {

  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            // Store timestamps in the database's own time, so clock skew between
            // instances cannot hand the same lock to two of them.
            .usingDbTime()
            .build());
  }
}
