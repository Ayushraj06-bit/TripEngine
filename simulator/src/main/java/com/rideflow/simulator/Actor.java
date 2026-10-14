package com.rideflow.simulator;

import com.rideflow.simulator.BackendApi.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.random.RandomGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * One simulated app: a mailbox served by a single virtual thread, so everything an actor does - its
 * tick, an offer arriving, a lost connection - happens one at a time on its own thread, and its
 * state needs no locks. The shared scheduler only decides when; the work runs here.
 *
 * <p>Also the app's connection handling, which drivers and riders share: connect, resync over REST
 * after every (re)connect, renew the token before the backend closes the socket on it, and drop the
 * connection now and then on purpose.
 */
abstract class Actor {

  private static final Logger log = LoggerFactory.getLogger(Actor.class);

  /**
   * The socket is renewed this long before the token it was opened with expires: before {@link
   * UserSession#REFRESH_MARGIN}, so it is renewed here first and not left on a token the backend is
   * about to close it for.
   */
  private static final Duration RENEW_SOCKET_MARGIN = UserSession.REFRESH_MARGIN.multipliedBy(2);

  protected final World world;
  protected final UserSession session;
  protected final StompLink link;
  private final ExecutorService mailbox;
  private final AtomicBoolean tickQueued = new AtomicBoolean();
  private boolean offline = true;
  private boolean reconnectPending;

  Actor(World world, UserSession session, String name) {
    this.world = world;
    this.session = session;
    this.link = new StompLink(world.stomp(), world.stompUrl(), world.json());
    this.mailbox = Executors.newSingleThreadExecutor(Thread.ofVirtual().name(name).factory());
  }

  /** The queues this app subscribes to. */
  abstract List<String> destinations();

  /** A message on one of them, on the actor's own thread. */
  abstract void onMessage(String destination, JsonNode envelope);

  /** Catches up over REST, the source of truth, after any (re)connect. */
  abstract void resync();

  /** Every {@code tick} while connected. */
  abstract void tick();

  void start() {
    tell(this::reconnect);
    long period = world.properties().tick().toMillis();
    // Spread over the first tick, so a thousand apps do not all report in the same millisecond.
    world
        .scheduler()
        .scheduleAtFixedRate(
            () -> {
              // Never more than one tick waiting: a slow backend must not build a backlog.
              if (tickQueued.compareAndSet(false, true)) {
                tell(this::connectedTick);
              }
            },
            random().nextLong(period),
            period,
            TimeUnit.MILLISECONDS);
  }

  void stop() {
    mailbox.shutdownNow();
    link.disconnect();
  }

  /** Runs {@code work} on this actor's thread. A failure is logged and the actor carries on. */
  void tell(Runnable work) {
    if (mailbox.isShutdown()) {
      return;
    }
    mailbox.execute(
        () -> {
          try {
            work.run();
          } catch (ApiException e) {
            // Already counted by type; refusals are part of the traffic.
            log.debug("{}: {}", Thread.currentThread().getName(), e.getMessage());
          } catch (RuntimeException e) {
            world.meters().counter("simulator.errors", "type", "SIMULATOR").increment();
            log.warn("{} failed", Thread.currentThread().getName(), e);
          }
        });
  }

  void later(Duration delay, Runnable work) {
    if (!world.scheduler().isShutdown()) {
      world.scheduler().schedule(() -> tell(work), delay.toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  RandomGenerator random() {
    return ThreadLocalRandom.current();
  }

  boolean offline() {
    return offline;
  }

  private void connectedTick() {
    tickQueued.set(false);
    if (offline) {
      return;
    }
    if (random().nextDouble() < world.properties().driver().disconnectProbability()) {
      // The phone went into a tunnel.
      world.meters().counter("simulator.disconnects").increment();
      link.disconnect();
      wentOffline();
      return;
    }
    if (session.expiresWithin(RENEW_SOCKET_MARGIN)) {
      session.refresh();
      link.disconnect();
      reconnect();
    }
    if (!offline) {
      tick();
    }
  }

  /** Connects with a fresh token, or tries again after a while. */
  private void reconnect() {
    if (link.connected()) {
      return;
    }
    try {
      link.connect(
          session.accessToken(),
          destinations(),
          new StompLink.Listener() {
            @Override
            public void onMessage(String destination, JsonNode envelope) {
              tell(() -> Actor.this.onMessage(destination, envelope));
            }

            @Override
            public void onLost() {
              tell(Actor.this::wentOffline);
            }
          });
    } catch (IllegalStateException | ApiException e) {
      log.debug("Could not connect {}: {}", session.maskedPhone(), e.getMessage());
      wentOffline();
      return;
    }
    offline = false;
    afterConnect();
    resync();
  }

  /** Just after connecting, before the resync. */
  void afterConnect() {}

  /** Offline until the reconnect, which is scheduled once however many times this is heard. */
  private void wentOffline() {
    offline = true;
    if (reconnectPending) {
      return;
    }
    reconnectPending = true;
    later(
        World.within(world.properties().driver().offlineFor(), random()),
        () -> {
          reconnectPending = false;
          reconnect();
        });
  }
}
