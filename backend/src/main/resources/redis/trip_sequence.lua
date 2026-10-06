-- The next sequence number for a trip's real-time messages.
--
-- INCR and the TTL in one step: as two calls, a crash between them would leave a
-- counter that never expires. The TTL is renewed with every message, so it runs from
-- the trip's last one: a counter that expired under a long trip would start again
-- at 1, and clients would drop everything after.
--
-- KEYS[1] trip:seq:{tripId}   ARGV[1] ttlMs (section 7: 24 hours)
-- Returns the new value: 1 for the trip's first message.

local next = redis.call('INCR', KEYS[1])
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return next
