-- Use a quote exactly once, and only if it belongs to the caller.
--
-- A plain GETDEL deletes first and checks afterwards, so a caller holding someone
-- else's quote id would destroy it: they get a 404, and the owner's booking fails
-- later with QUOTE_EXPIRED for no reason they can see. Here every check runs before
-- the delete, and a mismatch deletes nothing.
--
-- Atomic, so of any number of concurrent calls for one quote exactly one returns
-- 'consumed'.
--
-- Expiry is judged against the caller's clock (ARGV[3], from the injected Clock),
-- not the key's TTL. A test clock cannot move a Redis TTL, and the TTL is set a
-- little longer than the quote's life, so it is garbage collection only.
--
-- KEYS[1] quote:{quoteId}          string, JSON (PROJECT_SPEC section 7)
-- ARGV[1] riderId
-- ARGV[2] vehicleType
-- ARGV[3] nowMs
--
-- Returns { outcome } or { 'consumed', json }
--   outcome: missing | expired | wrong_rider | wrong_vehicle_type

local raw = redis.call('GET', KEYS[1])
if not raw then
  return { 'missing' }
end

local quote = cjson.decode(raw)

if tonumber(quote.expiresAtMs) <= tonumber(ARGV[3]) then
  redis.call('DEL', KEYS[1])
  return { 'expired' }
end

if quote.riderId ~= ARGV[1] then
  return { 'wrong_rider' }
end

if quote.vehicleType ~= ARGV[2] then
  return { 'wrong_vehicle_type' }
end

redis.call('DEL', KEYS[1])
return { 'consumed', raw }
