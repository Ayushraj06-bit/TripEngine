-- A driver accepts an offer.
--
-- Checks and the status change are one step, so accept cannot interleave with
-- the offer expiring, being withdrawn, or being rejected: whichever moves the
-- offer out of PENDING first wins, and the others see it already gone.
--
-- An offer past its expiry is reported 'expired' but left as it is. The poller
-- owns expiry: it releases the driver and moves the trip on. If accept closed the
-- offer itself, nobody would do either.
--
-- KEYS[1] offer:{offerId}   KEYS[2] offers:expiry
-- ARGV[1] offerId   ARGV[2] the accepting driver   ARGV[3] nowMs (injected Clock)
--
-- Returns { 'accepted', tripId, '1' } the first time, { 'accepted', tripId, '0' }
-- on a repeat, or { outcome }: missing | not_yours | expired | rejected | withdrawn

local offer = redis.call('HMGET', KEYS[1], 'driverId', 'status', 'expiresAt', 'tripId')
if not offer[1] then
  return { 'missing' }
end
if offer[1] ~= ARGV[2] then
  return { 'not_yours' }
end
if offer[2] == 'ACCEPTED' then
  return { 'accepted', offer[4], '0' }
end
if offer[2] ~= 'PENDING' then
  return { string.lower(offer[2]) }
end
if tonumber(ARGV[3]) >= tonumber(offer[3]) then
  return { 'expired' }
end

redis.call('HSET', KEYS[1], 'status', 'ACCEPTED')
redis.call('ZREM', KEYS[2], ARGV[1])
return { 'accepted', offer[4], '1' }
