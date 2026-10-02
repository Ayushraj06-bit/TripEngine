-- Move an offer from one status to another: rejected, expired, withdrawn.
--
-- Only from the status the caller expects, so of everything that can end an
-- offer at the same moment exactly one succeeds - and only that caller goes on
-- to release the driver and move the trip along.
--
-- KEYS[1] offer:{offerId}   KEYS[2] offers:expiry
-- ARGV[1] offerId   ARGV[2] new status   ARGV[3] the status it must be in
-- ARGV[4] the driver it must belong to, or '' for any
--
-- Returns { 'closed', tripId, driverId }, { 'not_open', currentStatus },
-- { 'missing' } or { 'not_yours' }

local offer = redis.call('HMGET', KEYS[1], 'driverId', 'status', 'tripId')
if not offer[1] then
  return { 'missing' }
end
if ARGV[4] ~= '' and offer[1] ~= ARGV[4] then
  return { 'not_yours' }
end
if offer[2] ~= ARGV[3] then
  return { 'not_open', offer[2] }
end

redis.call('HSET', KEYS[1], 'status', ARGV[2])
redis.call('ZREM', KEYS[2], ARGV[1])
return { 'closed', offer[3], offer[1] }
