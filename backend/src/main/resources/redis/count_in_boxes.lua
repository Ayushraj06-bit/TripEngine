-- Available drivers inside each of a list of boxes, for surge supply (ADR 0014).
--
-- One round trip for a batch of boxes; the caller keeps batches small, so Redis is
-- never held for long. Only the counts come back, never the members.
--
-- KEYS: every availability GEO set (one per vehicle type)
-- ARGV: lng, lat, widthMeters, heightMeters for each box, in order
--
-- Returns one count per box.

local counts = {}
for i = 1, #ARGV, 4 do
  local found = 0
  for _, key in ipairs(KEYS) do
    found = found + #redis.call('GEOSEARCH', key, 'FROMLONLAT', ARGV[i], ARGV[i + 1],
        'BYBOX', ARGV[i + 2], ARGV[i + 3], 'm')
  end
  counts[#counts + 1] = found
end
return counts
