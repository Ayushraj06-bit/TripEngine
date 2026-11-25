package com.rideflow.pricing.internal;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.Ticker;
import com.rideflow.pricing.SurgeAdministration.CellOverride;
import com.rideflow.shared.RideFlowProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The admins' two surge levers: the kill switch, and an override per cell.
 *
 * <p>In PostgreSQL, not beside the computed values in Redis, which keeps nothing on disk: those
 * come back with the next tick, but a restart would turn a switched-off surge back on by itself.
 *
 * <p>All of them are kept for {@code cache-seconds}, so quotes cost two small queries in that time
 * rather than two each. A change made here applies here at once, and on other instances within it.
 */
@Component
class SurgeLevers {

  /**
   * @param overrides by cell, soonest to end first; past their expiry too, which the reader checks
   */
  record Levers(boolean off, Map<String, CellOverride> overrides) {}

  private final JdbcTemplate jdbc;

  /** One value, under one key. */
  private final LoadingCache<Boolean, Levers> cached;

  SurgeLevers(JdbcTemplate jdbc, Ticker surgeCacheTicker, RideFlowProperties properties) {
    this.jdbc = jdbc;
    this.cached =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(properties.surge().cacheSeconds()))
            .ticker(surgeCacheTicker)
            .build(key -> read());
  }

  /** As of at most {@code cache-seconds} ago: what quotes go by. */
  Levers current() {
    return cached.get(true);
  }

  /** As they are now: what admins are shown, whichever instance made the change. */
  Levers read() {
    boolean off =
        Boolean.TRUE.equals(jdbc.queryForObject("SELECT off FROM surge_switch", Boolean.class));
    Map<String, CellOverride> overrides = new LinkedHashMap<>();
    jdbc.query(
        "SELECT cell, multiplier, expires_at FROM surge_overrides ORDER BY expires_at, cell",
        row -> {
          overrides.put(
              row.getString("cell"),
              new CellOverride(
                  row.getString("cell"),
                  row.getBigDecimal("multiplier"),
                  row.getObject("expires_at", OffsetDateTime.class).toInstant()));
        });
    return new Levers(off, overrides);
  }

  void setOverride(String cell, BigDecimal multiplier, Instant expiresAt) {
    jdbc.update(
        """
        INSERT INTO surge_overrides (cell, multiplier, expires_at) VALUES (?, ?, ?)
        ON CONFLICT (cell) DO UPDATE
            SET multiplier = excluded.multiplier, expires_at = excluded.expires_at""",
        cell,
        multiplier,
        OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
    forget();
  }

  void removeOverride(String cell) {
    jdbc.update("DELETE FROM surge_overrides WHERE cell = ?", cell);
    forget();
  }

  void setOff(boolean off) {
    jdbc.update("UPDATE surge_switch SET off = ?", off);
    forget();
  }

  /** The next read asks the database. */
  void forget() {
    cached.invalidateAll();
  }
}
