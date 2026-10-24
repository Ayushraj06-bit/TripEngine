package com.rideflow.shared;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Where every rate limit keeps its buckets: in Redis, through Bucket4j, so every instance counts
 * against one budget (PROJECT_SPEC section 3). Kept in memory, two instances behind a load balancer
 * each allowed the whole limit.
 *
 * <ul>
 *   <li><strong>Keys are hashed.</strong> A limit keyed by phone number or client address should
 *       not leave a list of them readable in Redis.
 *   <li><strong>Every bucket expires</strong> once it would have refilled, so the keys clean up
 *       after themselves (PROJECT_SPEC section 7: every key has a TTL).
 *   <li><strong>Time comes from the injected {@link Clock}</strong>, as everywhere else: a test
 *       clock refills a bucket when it moves.
 *   <li><strong>The connection is made on first use.</strong> The application starts while Redis is
 *       away, and readiness reports it, as for every other Redis path.
 *   <li><strong>A closed connection is replaced.</strong> Spring's connection factory shuts its
 *       client down when stopped and makes a new one when started again - which Spring's test
 *       context cache does to every idle context - and a connection kept from the old client stays
 *       closed.
 * </ul>
 *
 * <p>Not final: Spring Modulith's observability wraps every bean of a module's API in a subclass.
 */
public class RateLimitBuckets implements AutoCloseable {

  private static final String PREFIX = "rate:";

  private final Supplier<RedisClient> client;
  private final TimeMeter time;
  private final Duration timeout;
  private volatile Connected connected;

  private record Connected(
      StatefulRedisConnection<String, byte[]> connection, ProxyManager<String> proxies) {}

  /**
   * @param client the Redis client, asked for only when a bucket is first needed
   * @param timeout how long one bucket operation may wait on Redis
   */
  public RateLimitBuckets(Supplier<RedisClient> client, Clock clock, Duration timeout) {
    this.client = client;
    this.timeout = timeout;
    this.time =
        new TimeMeter() {
          @Override
          public long currentTimeNanos() {
            return TimeUnit.MILLISECONDS.toNanos(clock.millis());
          }

          @Override
          public boolean isWallClockBased() {
            return true;
          }
        };
  }

  BucketProxy bucket(String limit, String key, Supplier<BucketConfiguration> configuration) {
    return connected().proxies().builder().build(PREFIX + limit + ":" + hash(key), configuration);
  }

  /** Discards every bucket of {@code limit}. For tests; see {@link KeyedRateLimiter#reset()}. */
  void clear(String limit) {
    var commands = connected().connection().sync();
    ScanArgs matching = ScanArgs.Builder.matches(PREFIX + limit + ":*").limit(500);
    KeyScanCursor<String> cursor = commands.scan(matching);
    while (true) {
      if (!cursor.getKeys().isEmpty()) {
        commands.del(cursor.getKeys().toArray(String[]::new));
      }
      if (cursor.isFinished()) {
        return;
      }
      cursor = commands.scan(ScanCursor.of(cursor.getCursor()), matching);
    }
  }

  @Override
  public synchronized void close() {
    if (connected != null) {
      connected.connection().close();
      connected = null;
    }
  }

  private Connected connected() {
    Connected ready = connected;
    if (ready != null && ready.connection().isOpen()) {
      return ready;
    }
    synchronized (this) {
      ready = connected;
      if (ready != null && ready.connection().isOpen()) {
        return ready;
      }
      close();
      StatefulRedisConnection<String, byte[]> connection;
      try {
        connection = client.get().connect(RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE));
      } catch (RuntimeException unreachable) {
        // Tried again on the next request: nothing is kept until a connection is made.
        throw new DataAccessResourceFailureException(
            "Rate limits are kept in Redis, which did not answer", unreachable);
      }
      connected =
          new Connected(
              connection,
              Bucket4jLettuce.casBasedBuilder(connection)
                  .clientClock(time)
                  .requestTimeout(timeout)
                  .expirationAfterWrite(
                      ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                          Duration.ofSeconds(1)))
                  .build());
      return connected;
    }
  }

  private static String hash(String key) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
      // 128 bits: no two keys a limit will ever see collide.
      return HexFormat.of().formatHex(digest, 0, 16);
    } catch (NoSuchAlgorithmException impossible) {
      // Every Java runtime has SHA-256.
      throw new IllegalStateException(impossible);
    }
  }
}
