-- End matching for a trip, and say which offer was out when it ended.
--
-- Reading the current offer and deleting the state must be one step. Done as two,
-- another instance can write a new offer in between: that offer outlives the
-- match, its driver stays locked, and nobody withdraws it. Once this has run,
-- offer_create.lua finds no match and refuses, so the offer returned here is the
-- last one there will ever be - the caller withdraws it.
--
-- KEYS[1] match:{tripId}   KEYS[2] match:{tripId}:tried   KEYS[3] match:retry
-- ARGV[1] tripId
--
-- Returns the offer id that was current, or '' if there was none.

local current = redis.call('HGET', KEYS[1], 'currentOffer') or ''
redis.call('DEL', KEYS[1], KEYS[2])
redis.call('ZREM', KEYS[3], ARGV[1])
return current
