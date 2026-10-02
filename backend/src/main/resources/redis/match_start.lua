-- Start matching a trip, unless it is already being matched.
--
-- Duplicate events are normal - the outbox delivers at least once - so starting
-- twice must do nothing the second time. Creating the state in one script makes
-- "is it running?" and "start it" a single step no other instance can split.
--
-- KEYS[1] match:{tripId}        hash
-- KEYS[2] match:{tripId}:tried  set of driver ids already offered this trip
-- ARGV[1] vehicleType   ARGV[2] pickupLat   ARGV[3] pickupLng   ARGV[4] farePaise
-- ARGV[5] startedAtMs   ARGV[6] deadlineMs  ARGV[7] ttlMs
-- ARGV[8] a driver never to offer it to (the one who just backed out), or ''
--
-- Returns 1 if this call started matching, 0 if it was already running.

if redis.call('EXISTS', KEYS[1]) == 1 then
  return 0
end

redis.call('HSET', KEYS[1],
  'vehicleType', ARGV[1], 'pickupLat', ARGV[2], 'pickupLng', ARGV[3],
  'farePaise', ARGV[4], 'startedAt', ARGV[5], 'deadline', ARGV[6], 'currentOffer', '')
redis.call('PEXPIRE', KEYS[1], ARGV[7])

if ARGV[8] ~= '' then
  redis.call('SADD', KEYS[2], ARGV[8])
  redis.call('PEXPIRE', KEYS[2], ARGV[7])
end
return 1
