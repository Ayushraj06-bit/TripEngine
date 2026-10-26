-- Apply a driver's status change from the driver module, unless a later one already has been.
--
-- DriverStatusChanged is handled on several listener threads, so two changes for one driver can
-- arrive in either order. Each carries the driver row's version after its change, and the stored
-- one only moves forward: an older change arriving last changes nothing. An equal version is
-- applied - a vehicle change publishes without changing the row. One script, so the check, the
-- write and the TTL cannot be split (PROJECT_SPEC section 7: every key has a TTL).
--
-- KEYS[1] driver:state:{driverId}   KEYS[2..] every available set
-- ARGV[1] status   ARGV[2] vehicleType, or ''   ARGV[3] driverVersion   ARGV[4] ttlSeconds
-- ARGV[5] driverId   ARGV[6] the new vehicle type's available set, or ''
-- Returns 1 when applied, 0 when a later change was already there.

local stored = tonumber(redis.call('HGET', KEYS[1], 'statusVersion'))
if stored and stored > tonumber(ARGV[3]) then
  return 0
end

redis.call('HSET', KEYS[1], 'status', ARGV[1], 'statusVersion', ARGV[3])
if ARGV[2] ~= '' then
  redis.call('HSET', KEYS[1], 'vehicleType', ARGV[2])
end
redis.call('EXPIRE', KEYS[1], ARGV[4])

-- Off every set when not available; available, off every set but the new type's.
for i = 2, #KEYS do
  if ARGV[1] ~= 'AVAILABLE' or KEYS[i] ~= ARGV[6] then
    redis.call('ZREM', KEYS[i], ARGV[5])
  end
end
return 1
