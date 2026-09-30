-- Remove drivers who stopped reporting, atomically, in bounded batches.
--
-- PROJECT_SPEC section 7 gives drivers:lastseen and the GEO sets no TTL, because a
-- TTL cannot express "gone quiet for thirty seconds" - only this sweep can. Without
-- it the map fills with drivers who closed the app in a tunnel, and matching keeps
-- offering trips nobody will answer.
--
-- Batched on purpose. An unbounded ZRANGEBYSCORE after an outage could return every
-- driver at once and block Redis for the whole cluster while it ran; a bounded batch
-- that runs again in five seconds drains the backlog without a stall. The caller
-- keeps invoking until it returns fewer than the batch size.
--
-- KEYS[1] drivers:lastseen         zset, score = last ping epoch ms
-- KEYS[2] drivers:available:BIKE   geo
-- KEYS[3] drivers:available:AUTO   geo
-- KEYS[4] drivers:available:CAR    geo
--
-- ARGV[1] cutoffMs   anything scored at or before this is stale
-- ARGV[2] batchSize
--
-- Returns a flat list: driverId1, lastSeenMs1, driverId2, lastSeenMs2, ...

local lastSeenKey = KEYS[1]
local cutoffMs    = tonumber(ARGV[1])
local batchSize   = tonumber(ARGV[2])

local stale = redis.call('ZRANGEBYSCORE', lastSeenKey,
    '-inf', cutoffMs, 'WITHSCORES', 'LIMIT', 0, batchSize)

if #stale == 0 then
  return {}
end

local removed = {}
for i = 1, #stale, 2 do
  local driverId  = stale[i]
  local lastSeen  = stale[i + 1]

  -- Out of every availability set first, so a driver can never be offered a trip
  -- after being declared stale, and only then out of lastseen. The other order
  -- would leave a window where the driver is untracked but still offerable.
  redis.call('ZREM', KEYS[2], driverId)
  redis.call('ZREM', KEYS[3], driverId)
  redis.call('ZREM', KEYS[4], driverId)
  redis.call('ZREM', lastSeenKey, driverId)

  -- The state hash is left alone. It carries its own 24 hour TTL, and a driver who
  -- comes back within it keeps their status and vehicle type rather than needing the
  -- driver module to tell us again.
  table.insert(removed, driverId)
  table.insert(removed, lastSeen)
end

return removed
