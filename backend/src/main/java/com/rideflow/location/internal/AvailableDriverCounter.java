package com.rideflow.location.internal;

import com.rideflow.pricing.AvailableDriverCounts;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Surge supply from the availability GEO sets: {@code GEOSEARCH BYBOX} over every vehicle type,
 * counted inside Redis so no driver ids cross the wire.
 *
 * <p>In batches, one script call each: a surge tick asks about thousands of boxes, and one script
 * covering them all would hold Redis - and every driver's position update - for its whole length.
 */
@Component
class AvailableDriverCounter implements AvailableDriverCounts {

  /** Boxes per script call: a few milliseconds of Redis time each. */
  private static final int BATCH = 200;

  private final StringRedisTemplate redis;

  @SuppressWarnings("rawtypes")
  private final RedisScript<List> countInBoxesScript;

  @SuppressWarnings("rawtypes")
  AvailableDriverCounter(StringRedisTemplate redis, RedisScript<List> countInBoxesScript) {
    this.redis = redis;
    this.countInBoxesScript = countInBoxesScript;
  }

  @Override
  public List<Long> countIn(List<Box> boxes) {
    List<Long> counts = new ArrayList<>(boxes.size());
    for (int from = 0; from < boxes.size(); from += BATCH) {
      List<Box> batch = boxes.subList(from, Math.min(boxes.size(), from + BATCH));
      Object[] args = new Object[batch.size() * 4];
      for (int i = 0; i < batch.size(); i++) {
        Box box = batch.get(i);
        args[i * 4] = Double.toString(box.center().longitude());
        args[i * 4 + 1] = Double.toString(box.center().latitude());
        args[i * 4 + 2] = Double.toString(box.widthMeters());
        args[i * 4 + 3] = Double.toString(box.heightMeters());
      }
      @SuppressWarnings("unchecked")
      List<Object> found = redis.execute(countInBoxesScript, LocationKeys.allAvailable(), args);
      for (Object count : found) {
        counts.add(((Number) count).longValue());
      }
    }
    return counts;
  }
}
