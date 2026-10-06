-- Record which trip a driver is on, and the state hash's TTL, in one step.
--
-- As two calls, a crash between them on a hash that did not exist yet - after a
-- Redis failover, say - would leave a key that never expires (PROJECT_SPEC section 7).
--
-- KEYS[1] driver:state:{driverId}
-- ARGV[1] ttlSeconds   ARGV[2..] field, value, field, value, ...

redis.call('HSET', KEYS[1], unpack(ARGV, 2))
redis.call('EXPIRE', KEYS[1], ARGV[1])
return 1
