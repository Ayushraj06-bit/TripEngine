-- Append one point to a trip's recorded route (PROJECT_SPEC section 7: trip:route:{tripId}).
--
-- Capped with LTRIM so a trip that never ends cannot grow without bound, and given a
-- TTL so a route nobody completes still goes away. One step, one round trip.
--
-- KEYS[1] trip:route:{tripId}
-- ARGV[1] the point, "lat,lng,epochMs"   ARGV[2] most points kept   ARGV[3] ttlMs

redis.call('RPUSH', KEYS[1], ARGV[1])
redis.call('LTRIM', KEYS[1], -tonumber(ARGV[2]), -1)
redis.call('PEXPIRE', KEYS[1], ARGV[3])
return 1
