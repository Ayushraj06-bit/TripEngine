package com.rideflow.pricing.internal;

import com.rideflow.pricing.AvailableDriverCounts;
import com.rideflow.shared.RideFlowProperties;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prices every cell with demand, once a tick (PROJECT_SPEC section 8, ADR 0014).
 *
 * <ol>
 *   <li>Cells with a request in the last {@code window-minutes}, from {@code surge:demand:cells}.
 *   <li>Demand of a cell: the requests over the cell and its eight neighbours, so a rider on one
 *       side of a cell border is priced much like a rider on the other. That makes every cell next
 *       to a request one with demand, and they are all priced.
 *   <li>Supply of a cell: available drivers over the same three-by-three block.
 *   <li>Each cell moves one smoothed step towards its target, written to {@code surge:{cell}} with
 *       a {@code ttl-seconds} TTL. Should the job stop, every key lapses and fares fall back to
 *       1.0.
 * </ol>
 *
 * <p>Cells far from any request are never computed: they are 1.0 by default. Cells still above 1.0
 * whose demand has gone are priced too, with nothing left to push them, so they step back down the
 * way they went up rather than dropping all at once when their key expires.
 *
 * <p>Once per cluster per tick: ShedLock, held for most of a tick so an instance whose schedule is
 * a few seconds off cannot take a second step.
 */
@Component
class SurgeJob {

  private static final Logger log = LoggerFactory.getLogger(SurgeJob.class);

  /** Keys per MGET: large enough for one round trip in practice, small enough to never block. */
  private static final int READ_BATCH = 1_000;

  private final StringRedisTemplate redis;
  private final AvailableDriverCounts drivers;
  private final SurgeCalculator calculator;
  private final RideFlowProperties.Surge rules;
  private final Clock clock;
  private final MeterRegistry meters;
  private final Timer tickTimer;
  private final DistributionSummary multipliers;
  private final AtomicLong activeCells = new AtomicLong();

  SurgeJob(
      StringRedisTemplate redis,
      AvailableDriverCounts drivers,
      RideFlowProperties properties,
      Clock clock,
      MeterRegistry meters) {
    this.redis = redis;
    this.drivers = drivers;
    this.rules = properties.surge();
    this.calculator = new SurgeCalculator(rules);
    this.clock = clock;
    this.meters = meters;
    this.tickTimer =
        Timer.builder("surge.tick").description("How long one surge tick took").register(meters);
    this.multipliers =
        DistributionSummary.builder("surge.multiplier")
            .description("The multiplier of each cell with demand, every tick")
            .serviceLevelObjectives(1.0, 1.2, 1.5, 2.0, 2.5)
            .register(meters);
    Gauge.builder("surge.active.cells", activeCells, AtomicLong::get)
        .description("Cells priced above 1.0 by the last tick")
        .register(meters);
  }

  /** One cell as a tick leaves it: the next tick's starting point, and the admin heatmap. */
  record Priced(BigDecimal multiplier, long demand, long supply) {

    String encode() {
      return multiplier.toPlainString() + "|" + demand + "|" + supply;
    }

    static Priced decode(String value) {
      String[] parts = value.split("\\|");
      return new Priced(
          new BigDecimal(parts[0]), Long.parseLong(parts[1]), Long.parseLong(parts[2]));
    }
  }

  @Scheduled(
      fixedRateString = "${rideflow.surge.tick-interval}",
      initialDelayString = "${rideflow.surge.tick-interval}")
  @SchedulerLock(
      name = "surge-tick",
      lockAtMostFor = "PT2M",
      lockAtLeastFor = "${rideflow.surge.lock-at-least-for}")
  void tick() {
    Timer.Sample sample = Timer.start(meters);
    long minute = SurgeKeys.epochMinute(clock.instant());
    long oldestMinute = minute - rules.windowMinutes() + 1;

    // Cells whose latest request has left the window have no demand any more, and overrides
    // past their expiry are dropped from the admins' index whether or not anyone looks.
    redis
        .opsForZSet()
        .removeRangeByScore(SurgeKeys.ACTIVE_CELLS, Double.NEGATIVE_INFINITY, oldestMinute - 1);
    redis
        .opsForZSet()
        .removeRangeByScore(SurgeKeys.OVERRIDES, Double.NEGATIVE_INFINITY, clock.millis());
    Set<String> active = redis.opsForZSet().range(SurgeKeys.ACTIVE_CELLS, 0, -1);
    Map<String, Long> requests =
        requestsIn(active == null ? Set.of() : active, oldestMinute, minute);
    Map<String, Priced> previous = snapshot();

    Set<String> cells = new LinkedHashSet<>();
    requests.keySet().forEach(cell -> cells.addAll(Geohash.block(cell)));
    previous.forEach(
        (cell, priced) -> {
          if (priced.multiplier().compareTo(SurgeCalculator.NONE) > 0) {
            cells.add(cell);
          }
        });

    List<String> ordered = new ArrayList<>(cells);
    Map<String, Long> demand = new HashMap<>();
    List<String> withDemand = new ArrayList<>();
    for (String cell : ordered) {
      long sum = 0;
      for (String neighbour : Geohash.block(cell)) {
        sum += requests.getOrDefault(neighbour, 0L);
      }
      demand.put(cell, sum);
      if (sum > 0) {
        withDemand.add(cell);
      }
    }
    Map<String, Long> supply = supplyOf(withDemand);
    List<String> current = readValues(ordered.stream().map(SurgeKeys::multiplier).toList());

    Map<String, Priced> next = new LinkedHashMap<>();
    for (int i = 0; i < ordered.size(); i++) {
      String cell = ordered.get(i);
      BigDecimal from =
          current.get(i) == null ? SurgeCalculator.NONE : new BigDecimal(current.get(i));
      long cellDemand = demand.get(cell);
      long cellSupply = supply.getOrDefault(cell, 0L);
      BigDecimal value = calculator.smooth(from, calculator.target(cellDemand, cellSupply));
      next.put(cell, new Priced(value, cellDemand, cellSupply));
    }

    write(next);
    long surging =
        next.values().stream()
            .filter(priced -> priced.multiplier().compareTo(SurgeCalculator.NONE) > 0)
            .count();
    activeCells.set(surging);
    next.values().stream()
        .filter(
            priced ->
                priced.demand() > 0 || priced.multiplier().compareTo(SurgeCalculator.NONE) > 0)
        .forEach(
            priced -> multipliers.record(calculator.forPricing(priced.multiplier()).doubleValue()));
    long took = sample.stop(tickTimer);
    Duration elapsed = Duration.ofNanos(took);
    if (elapsed.compareTo(Duration.ofSeconds(rules.tickSeconds()).dividedBy(2)) > 0) {
      log.warn(
          "Surge tick took {} ms for {} cells, over half a tick", elapsed.toMillis(), next.size());
    } else {
      log.debug(
          "Surge tick: {} cells priced, {} surging, {} ms",
          next.size(),
          surging,
          elapsed.toMillis());
    }
  }

  /** What the last tick left, for the admin heatmap. */
  Map<String, Priced> snapshot() {
    Map<Object, Object> raw = redis.opsForHash().entries(SurgeKeys.SNAPSHOT);
    Map<String, Priced> priced = new LinkedHashMap<>();
    raw.forEach((cell, value) -> priced.put((String) cell, Priced.decode((String) value)));
    return priced;
  }

  /** Requests per cell over the window. Only active cells can have any. */
  private Map<String, Long> requestsIn(Set<String> active, long oldestMinute, long minute) {
    List<String> cells = new ArrayList<>(active);
    List<String> keys = new ArrayList<>();
    for (String cell : cells) {
      for (long m = oldestMinute; m <= minute; m++) {
        keys.add(SurgeKeys.demand(cell, m));
      }
    }
    List<String> counts = readValues(keys);
    Map<String, Long> requests = new LinkedHashMap<>();
    int perCell = (int) (minute - oldestMinute + 1);
    for (int i = 0; i < cells.size(); i++) {
      long sum = 0;
      for (int m = 0; m < perCell; m++) {
        String count = counts.get(i * perCell + m);
        sum += count == null ? 0 : Long.parseLong(count);
      }
      if (sum > 0) {
        requests.put(cells.get(i), sum);
      }
    }
    return requests;
  }

  /** Available drivers over each cell's three-by-three block, measured from the cell's centre. */
  private Map<String, Long> supplyOf(List<String> cells) {
    List<AvailableDriverCounts.Box> boxes = new ArrayList<>(cells.size());
    for (String cell : cells) {
      Geohash.Bounds bounds = Geohash.bounds(cell);
      boxes.add(
          new AvailableDriverCounts.Box(
              bounds.center(), 3 * bounds.widthMeters(), 3 * bounds.heightMeters()));
    }
    List<Long> counts = boxes.isEmpty() ? List.of() : drivers.countIn(boxes);
    Map<String, Long> supply = new HashMap<>();
    for (int i = 0; i < cells.size(); i++) {
      supply.put(cells.get(i), counts.get(i));
    }
    return supply;
  }

  private List<String> readValues(List<String> keys) {
    List<String> values = new ArrayList<>(keys.size());
    for (int from = 0; from < keys.size(); from += READ_BATCH) {
      values.addAll(
          redis
              .opsForValue()
              .multiGet(keys.subList(from, Math.min(keys.size(), from + READ_BATCH))));
    }
    return values;
  }

  /**
   * One pipelined round trip: each cell's multiplier - deleted when back at 1.0, the default - and
   * the snapshot, built under a temporary name and swapped in whole, so a reader never sees half.
   */
  private void write(Map<String, Priced> next) {
    Duration ttl = Duration.ofSeconds(rules.ttlSeconds());
    Map<String, String> snapshot = new LinkedHashMap<>();
    next.forEach(
        (cell, priced) -> {
          if (priced.demand() > 0 || priced.multiplier().compareTo(SurgeCalculator.NONE) > 0) {
            snapshot.put(cell, priced.encode());
          }
        });
    String building = SurgeKeys.SNAPSHOT + ":building";
    redis.executePipelined(
        new SessionCallback<Object>() {
          @Override
          @SuppressWarnings("unchecked")
          public Object execute(RedisOperations operations) throws DataAccessException {
            RedisOperations<String, String> ops = operations;
            next.forEach(
                (cell, priced) -> {
                  if (priced.multiplier().compareTo(SurgeCalculator.NONE) > 0) {
                    ops.opsForValue()
                        .set(SurgeKeys.multiplier(cell), priced.multiplier().toPlainString(), ttl);
                  } else {
                    ops.delete(SurgeKeys.multiplier(cell));
                  }
                });
            ops.delete(building);
            if (snapshot.isEmpty()) {
              ops.delete(SurgeKeys.SNAPSHOT);
            } else {
              ops.opsForHash().putAll(building, snapshot);
              // The TTL before the rename, which carries it over: should the rename never
              // happen, the half-built key still lapses on its own.
              ops.expire(building, ttl);
              ops.rename(building, SurgeKeys.SNAPSHOT);
            }
            return null;
          }
        });
  }
}
