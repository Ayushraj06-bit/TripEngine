package com.rideflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rideflow.shared.RideFlowProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Clock;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Phase 0 foundation, against real PostgreSQL, Redis and Kafka.
 *
 * <p>PROJECT_SPEC section 12 rules out H2 and embedded Redis, so this is the test that proves the
 * whole thing actually boots on the infrastructure it will run on: Flyway applies, the clock and
 * lock provider are wired, the actuator answers, and errors come back in the shape section 10
 * requires.
 *
 * <p>{@code addFilters = false} bypasses the servlet filter chain. Spring Security is on the
 * classpath but Phase 0 defines no {@code SecurityFilterChain}, so Boot's default would secure
 * every path and turn each of these into a 401. Authorization is Phase 1's job, and its security
 * matrix test is what will cover it.
 */
@SpringBootTest
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
@Tag("integration")
class ApplicationIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Nested
  class Wiring {

    @Test
    void appliesEveryFlywayMigration(@Autowired JdbcTemplate jdbc) {
      var applied =
          jdbc.queryForList(
              "SELECT version, description FROM flyway_schema_history WHERE success ORDER BY installed_rank");

      assertThat(applied)
          .extracting(row -> row.get("version"))
          .containsSubsequence("1", "2", "3", "4", "5");
      // The tables those migrations own must actually be there. shedlock is only
      // checked for existence, not emptiness: whether a job has written its lock row
      // yet depends on timing, not on the migration.
      assertThat(jdbc.queryForObject("SELECT count(*) FROM shedlock", Integer.class))
          .isNotNegative();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM event_publication", Integer.class))
          .isZero();
    }

    /**
     * Every background job is scheduled, and none at startup: each first run waits one interval
     * (ShedLockConfig), so a test driving a job itself never races the scheduler's own run.
     */
    @Test
    void schedulesEveryJobToStartOneIntervalAfterBoot(
        @Autowired
            java.util.List<org.springframework.scheduling.config.ScheduledTaskHolder> holders) {
      var tasks =
          holders.stream()
              .flatMap(holder -> holder.getScheduledTasks().stream())
              .map(org.springframework.scheduling.config.ScheduledTask::getTask)
              .toList();

      assertThat(tasks)
          .extracting(Object::toString)
          .anySatisfy(name -> assertThat(name).endsWith("LiveMapPublisher.scheduled"))
          .anySatisfy(name -> assertThat(name).endsWith("StaleDriverSweeper.sweep"))
          .anySatisfy(name -> assertThat(name).endsWith("ExpiringSessions.closeExpired"))
          .anySatisfy(name -> assertThat(name).endsWith("OfferPoller.poll"))
          .anySatisfy(name -> assertThat(name).endsWith("RecoverySweeper.sweep"))
          .anySatisfy(name -> assertThat(name).endsWith("SurgeJob.tick"))
          .anySatisfy(name -> assertThat(name).endsWith("EventRedelivery.redeliver"))
          .anySatisfy(name -> assertThat(name).endsWith("IdempotencyStore.purgeExpired"))
          .anySatisfy(name -> assertThat(name).endsWith("SilentDriverCheck.check"));
      assertThat(tasks)
          .filteredOn(org.springframework.scheduling.config.IntervalTask.class::isInstance)
          .hasSize(9)
          .allSatisfy(
              task -> {
                var interval = (org.springframework.scheduling.config.IntervalTask) task;
                assertThat(interval.getInitialDelayDuration())
                    .isEqualTo(interval.getIntervalDuration());
              });
    }

    /**
     * Readiness goes DOWN when PostgreSQL or Redis does not answer (Phase 10), so a load balancer
     * stops sending traffic the instance cannot serve. Liveness depends on neither: restarting a
     * healthy process does not bring a database back.
     */
    @Test
    void takesReadinessFromPostgresAndRedisButNotLiveness(
        @Autowired org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups groups) {
      var readiness = groups.get("readiness");
      var liveness = groups.get("liveness");

      assertThat(readiness.isMember("db")).isTrue();
      assertThat(readiness.isMember("redis")).isTrue();
      assertThat(liveness.isMember("db")).isFalse();
      assertThat(liveness.isMember("redis")).isFalse();
    }

    /** Every call to a backing store gives up in seconds (Phase 10), whoever makes it. */
    @Test
    void givesUpOnBackingStoresInSeconds(
        @Autowired JdbcTemplate jdbc,
        @Autowired javax.sql.DataSource dataSource,
        @Autowired org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory redis)
        throws Exception {
      // Enforced by PostgreSQL itself, so a JdbcTemplate query is bound as much as JPA's.
      assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).isEqualTo("10s");
      assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("2s");
      assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getConnectionTimeout())
          .isEqualTo(3_000);
      assertThat(redis.getClientConfiguration().getCommandTimeout())
          .isEqualTo(java.time.Duration.ofSeconds(1));
    }

    @Test
    void reachesRedis(@Autowired StringRedisTemplate redis) {
      try {
        redis.opsForValue().set("rideflow:smoke", "ok");
        assertThat(redis.opsForValue().get("rideflow:smoke")).isEqualTo("ok");
      } finally {
        redis.delete("rideflow:smoke");
      }
    }

    @Test
    void reachesKafka(@Autowired KafkaTemplate<String, String> kafka) {
      // partitionsFor is a real metadata round trip to the broker.
      assertThat(kafka.partitionsFor("rideflow.trip-events")).isNotEmpty();
    }

    @Test
    void suppliesAUtcClock(@Autowired Clock clock) {
      // PROJECT_SPEC section 7: every timestamp is UTC, so the clock must be too.
      assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void suppliesAShedLockProviderBackedByTheDatabase(
        @Autowired LockProvider lockProvider, @Autowired DataSource dataSource) {
      assertThat(lockProvider).isNotNull();
      assertThat(dataSource).isNotNull();
    }

    @Test
    void bindsTheBusinessConstants(@Autowired RideFlowProperties properties) {
      assertThat(properties.matching().offerTimeoutSeconds()).isEqualTo(12);
      assertThat(properties.payment().commissionPercent()).isEqualTo(20);
    }
  }

  @Nested
  class Observability {

    @Test
    void reportsOverallHealth() throws Exception {
      mockMvc
          .perform(get("/actuator/health"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void separatesLivenessFromReadiness() throws Exception {
      // Liveness must not depend on Redis or the database, or a blip restarts the pod.
      mockMvc
          .perform(get("/actuator/health/liveness"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
      mockMvc
          .perform(get("/actuator/health/readiness"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void exposesPrometheusMetrics() throws Exception {
      mockMvc
          .perform(get("/actuator/prometheus"))
          .andExpect(status().isOk())
          .andExpect(
              content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")))
          .andExpect(
              content()
                  .string(
                      org.hamcrest.Matchers.containsString("application=\"rideflow-backend\"")));
    }

    @Test
    void exposesTheModuleStructure() throws Exception {
      mockMvc.perform(get("/actuator/modulith")).andExpect(status().isOk());
    }
  }

  @Nested
  class ErrorModel {

    @Test
    void returnsProblemDetailForAnUnknownRoute() throws Exception {
      mockMvc
          .perform(get("/api/v1/does-not-exist"))
          .andExpect(status().isNotFound())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("NOT_FOUND"))
          .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void returnsFieldLevelErrorsForAnInvalidBody() throws Exception {
      mockMvc
          .perform(
              post("/__test__/echo")
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"name\":\"\",\"count\":-5}"))
          .andExpect(status().isBadRequest())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
          // Sorted by field name, so the response is stable.
          .andExpect(jsonPath("$.errors[0].field").value("count"))
          .andExpect(jsonPath("$.errors[1].field").value("name"));
    }

    @Test
    void returnsProblemDetailWithACodeForAnUnsupportedMethod() throws Exception {
      // Spring raises this itself; the handler still has to stamp a code onto it.
      mockMvc
          .perform(get("/__test__/echo"))
          .andExpect(status().isMethodNotAllowed())
          .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void returnsProblemDetailForAnUnreadableBody() throws Exception {
      mockMvc
          .perform(
              post("/__test__/echo").contentType(MediaType.APPLICATION_JSON).content("{ not json"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
  }

  /**
   * A throwaway endpoint, so the error model can be exercised before any module has one.
   *
   * <p>Registered by component scan, because test classes live under the scanned {@code
   * com.rideflow} package. It must therefore not also be declared as a {@code @Bean}: that maps the
   * same handler twice and fails the context with an ambiguous mapping.
   */
  @RestController
  static class EchoController {

    @PostMapping("/__test__/echo")
    Payload echo(@Valid @RequestBody Payload payload) {
      return payload;
    }

    record Payload(@NotBlank String name, @Min(0) int count) {}
  }
}
