-- Lock a driver and record an offer to them, in one step.
--
-- A driver has at most one live offer. The lock and the offer are written
-- together: taken separately, there is a moment when the lock exists and its offer
-- does not yet, and another trip would take that lock for an orphan and offer the
-- same driver a second trip. Written together, a lock whose offer is missing or
-- closed can only have been left by a crash or by lost state, and is taken over
-- rather than waited out - nothing would ever release it.
--
-- A trip has at most one offer out at a time. The caller says which offer it is
-- replacing (none, or the one that was just rejected, expired or withdrawn), and
-- the offer is written only if that is still the trip's current offer. Two
-- instances advancing the same trip at once - a duplicate event, or the recovery
-- sweep racing live matching - therefore cannot both send an offer: the second
-- finds the current offer changed and backs off.
--
-- KEYS[1] match:{tripId}   KEYS[2] match:{tripId}:tried   KEYS[3] offer:{offerId}
-- KEYS[4] offers:expiry    KEYS[5] match:retry      KEYS[6] driver:lock:{driverId}
-- ARGV[1] offerId   ARGV[2] the offer being replaced, or ''   ARGV[3] tripId
-- ARGV[4] driverId  ARGV[5] expiresAtMs (from the injected Clock)
-- ARGV[6] ttlMs     ARGV[7] radiusKm searched   ARGV[8] lockTtlMs
--
-- Returns 'created', 'superseded' (someone else advanced the trip), 'no_match'
-- (matching ended: the trip was assigned, cancelled or given up on) or
-- 'driver_busy' (another trip's offer holds the driver).

if redis.call('EXISTS', KEYS[1]) == 0 then
  return 'no_match'
end
local current = redis.call('HGET', KEYS[1], 'currentOffer') or ''
if current ~= ARGV[2] then
  return 'superseded'
end

-- ponytail: reads offer:{holder}, a key not passed in KEYS; fine on one Redis node,
-- needs hash tags before Redis Cluster.
local holder = redis.call('GET', KEYS[6])
if holder then
  local status = redis.call('HGET', 'offer:' .. holder, 'status')
  if status == 'PENDING' or status == 'ACCEPTED' then
    return 'driver_busy'
  end
end
redis.call('SET', KEYS[6], ARGV[1], 'PX', ARGV[8])

redis.call('HSET', KEYS[3], 'tripId', ARGV[3], 'driverId', ARGV[4],
  'status', 'PENDING', 'expiresAt', ARGV[5])
redis.call('PEXPIRE', KEYS[3], ARGV[6])
redis.call('ZADD', KEYS[4], ARGV[5], ARGV[1])

redis.call('HSET', KEYS[1], 'currentOffer', ARGV[1], 'radiusKm', ARGV[7])
redis.call('PEXPIRE', KEYS[1], ARGV[6])
redis.call('SADD', KEYS[2], ARGV[4])
redis.call('PEXPIRE', KEYS[2], ARGV[6])
-- An offer is out, so any pending "search again" is moot.
redis.call('ZREM', KEYS[5], ARGV[3])
return 'created'
