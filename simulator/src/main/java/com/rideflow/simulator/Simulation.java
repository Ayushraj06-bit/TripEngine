package com.rideflow.simulator;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sets the users up, starts one actor each, and sends ride requests their way as a Poisson process
 * - all on one small scheduler, with every blocking call on the actors' virtual threads.
 *
 * <p>Started in the background, so the application (and its metrics endpoint) is up while the
 * accounts are still being created.
 */
@Component
public class Simulation implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(Simulation.class);

  private final SimulatorProperties configured;
  private final Accounts accounts;
  private final BackendApi api;
  private final WebSocketStompClient stomp;
  private final JsonMapper json;
  private final MeterRegistry meters;
  private final Clock clock;
  private final ScheduledExecutorService scheduler =
      Executors.newScheduledThreadPool(2, Thread.ofPlatform().name("simulation-", 0).factory());
  private final List<SimulatedDriver> drivers = new ArrayList<>();
  private final List<SimulatedRider> riders = new ArrayList<>();
  private volatile boolean running;
  private volatile Instant startedAt;
  private World world;

  Simulation(
      SimulatorProperties configured,
      Accounts accounts,
      BackendApi api,
      WebSocketStompClient stomp,
      JsonMapper json,
      MeterRegistry meters,
      Clock clock) {
    this.configured = configured;
    this.accounts = accounts;
    this.api = api;
    this.stomp = stomp;
    this.json = json;
    this.meters = meters;
    this.clock = clock;
  }

  @Override
  public void start() {
    running = true;
    Thread.ofVirtual().name("simulation-setup").start(this::run);
  }

  @Override
  public void stop() {
    running = false;
    scheduler.shutdownNow();
    drivers.forEach(Actor::stop);
    riders.forEach(Actor::stop);
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** When the actors started, after setup; empty until then. */
  public Optional<Instant> startedAt() {
    return Optional.ofNullable(startedAt);
  }

  private void run() {
    SimulatorProperties properties = configured.withScenario();
    log.info(
        "Scenario {}: {} drivers, {} riders, {} requests a minute against {}",
        properties.scenario(),
        properties.drivers(),
        properties.riders(),
        properties.demand().requestsPerMinute(),
        properties.backendUrl());
    String stompUrl = properties.backendUrl().toString().replaceFirst("^http", "ws") + "/ws";
    world =
        new World(
            properties,
            api,
            stomp,
            stompUrl,
            json,
            scheduler,
            new ConcurrentHashMap<>(),
            meters,
            clock);
    Accounts.Population population;
    try {
      population = accounts.setUp(properties, world);
    } catch (RuntimeException e) {
      log.error("Setup failed; the simulation will not start", e);
      return;
    }
    if (!running) {
      return;
    }
    for (int i = 0; i < population.drivers().size(); i++) {
      drivers.add(new SimulatedDriver(world, population.drivers().get(i), i));
    }
    for (int i = 0; i < population.riders().size(); i++) {
      riders.add(new SimulatedRider(world, population.riders().get(i), i));
    }
    drivers.forEach(Actor::start);
    riders.forEach(Actor::start);
    startedAt = clock.instant();
    if (properties.demand().requestsPerMinute() > 0 && !riders.isEmpty()) {
      scheduleNextRequest();
    }
  }

  /** One arrival, then the next after an exponential gap: a Poisson process. */
  private void scheduleNextRequest() {
    var gap =
        PoissonArrivals.nextGap(
            world.properties().demand().requestsPerMinute(), ThreadLocalRandom.current());
    if (scheduler.isShutdown()) {
      return;
    }
    scheduler.schedule(
        () -> {
          requestRide();
          scheduleNextRequest();
        },
        gap.toNanos(),
        TimeUnit.NANOSECONDS);
  }

  /** To a rider with no trip, chosen at random; a request with every rider busy is dropped. */
  private void requestRide() {
    int start = ThreadLocalRandom.current().nextInt(riders.size());
    for (int i = 0; i < riders.size(); i++) {
      SimulatedRider rider = riders.get((start + i) % riders.size());
      if (rider.claim()) {
        rider.tell(rider::requestRide);
        return;
      }
    }
    meters.counter("simulator.requests.dropped", "reason", "every_rider_busy").increment();
  }
}
