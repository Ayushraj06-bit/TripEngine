-- Accept one driver position update, atomically.
--
-- PROJECT_SPEC section 8 asks for one Lua script per accepted update so the state
-- hash, the GEO set and drivers:lastseen all change in a single round trip. This
-- script goes further and performs the *stateful* validation here too - sequence
-- number, throttle, implied speed and the minimum-move rule.
--
-- That is deliberate. Those four checks all read the driver's previous position. Done
-- in Java they would be a read, then a separate write, and two updates from the same
-- driver (a reconnecting app, a duplicate client) could both pass the sequence check
-- before either wrote. At 670 updates a second that is not a theoretical race. Here
-- the check and the write are one operation, so the outcome the script reports is
-- also the outcome that happened - which is what makes the location_updates_total
-- counter honest.
--
-- Stateless checks - range, finiteness, accuracy, service area - stay in Java, where
-- they need no round trip and can be unit tested without Redis.
--
-- KEYS[1] driver:state:{driverId}      hash
-- KEYS[2] drivers:lastseen             zset, score = last ping epoch ms
-- KEYS[3] drivers:available:BIKE       geo
-- KEYS[4] drivers:available:AUTO       geo
-- KEYS[5] drivers:available:CAR        geo
--
-- ARGV[1]  driverId
-- ARGV[2]  lat
-- ARGV[3]  lng
-- ARGV[4]  seq
-- ARGV[5]  nowMs                       from the injected Clock, never redis TIME
-- ARGV[6]  minIntervalMs
-- ARGV[7]  minMoveMeters
-- ARGV[8]  forceWriteSeconds
-- ARGV[9]  maxSpeedKmph
-- ARGV[10] inServiceArea               1 or 0, decided in Java
-- ARGV[11] stateTtlSeconds
-- ARGV[12] forwardIntervalMs: how often a rider may be sent their driver's position
--
-- Returns { result, spoofStrikes, geoWritten }, and for a driver on a trip also
--   tripId, riderId, tripPhase, targetLat, targetLng, forward (1 or 0), vehicleType
--   result: accepted | ignored_out_of_order | throttled | rejected_spoof

local stateKey    = KEYS[1]
local lastSeenKey = KEYS[2]

local driverId          = ARGV[1]
local lat               = tonumber(ARGV[2])
local lng               = tonumber(ARGV[3])
local seq               = tonumber(ARGV[4])
local nowMs             = tonumber(ARGV[5])
local minIntervalMs     = tonumber(ARGV[6])
local minMoveMeters     = tonumber(ARGV[7])
local forceWriteMs      = tonumber(ARGV[8]) * 1000
local maxSpeedKmph      = tonumber(ARGV[9])
local inServiceArea     = ARGV[10] == '1'
local stateTtlSeconds   = tonumber(ARGV[11])
local forwardIntervalMs = tonumber(ARGV[12])

-- Great-circle distance in metres. Mirrors GeoPoint.distanceMetersTo so the two
-- never disagree about whether a driver moved.
local function haversineMeters(lat1, lon1, lat2, lon2)
  local earthRadius = 6371008.8
  local rad = math.pi / 180
  local dLat = (lat2 - lat1) * rad
  local dLon = (lon2 - lon1) * rad
  local sinHalfLat = math.sin(dLat / 2)
  local sinHalfLon = math.sin(dLon / 2)
  local a = sinHalfLat * sinHalfLat
      + math.cos(lat1 * rad) * math.cos(lat2 * rad) * sinHalfLon * sinHalfLon
  local c = 2 * math.asin(math.min(1, math.sqrt(a)))
  return earthRadius * c
end

local function geoKeyFor(vehicleType)
  if vehicleType == 'BIKE' then return KEYS[3] end
  if vehicleType == 'AUTO' then return KEYS[4] end
  if vehicleType == 'CAR'  then return KEYS[5] end
  return nil
end

local function removeFromEveryAvailableSet()
  redis.call('ZREM', KEYS[3], driverId)
  redis.call('ZREM', KEYS[4], driverId)
  redis.call('ZREM', KEYS[5], driverId)
end

-- Previous state. Absent on a driver's first ping after a Redis flush or a restart.
local stored = redis.call('HMGET', stateKey,
    'status', 'vehicleType', 'lat', 'lng', 'ts', 'seq', 'lastGeoWriteTs', 'spoofStrikes',
    'tripId', 'riderId', 'tripPhase', 'targetLat', 'targetLng', 'lastForwardTs')

local status         = stored[1]
local vehicleType    = stored[2]
local prevLat        = tonumber(stored[3])
local prevLng        = tonumber(stored[4])
local prevTs         = tonumber(stored[5])
local prevSeq        = tonumber(stored[6])
local lastGeoWriteTs = tonumber(stored[7])
local spoofStrikes   = tonumber(stored[8]) or 0

-- 1. Out of order. A duplicate or a packet that overtook a newer one; the newer
--    position already stored is the better answer, so keep it.
if prevSeq ~= nil and seq <= prevSeq then
  return { 'ignored_out_of_order', spoofStrikes, 0 }
end

-- 2. Throttle. A client sending faster than min-interval-ms gains nothing and costs
--    a write, but its position still counts as a sign of life, so lastseen is
--    refreshed before returning.
if prevTs ~= nil and (nowMs - prevTs) < minIntervalMs then
  redis.call('ZADD', lastSeenKey, nowMs, driverId)
  return { 'throttled', spoofStrikes, 0 }
end

-- 3. Implied speed. Only meaningful with a previous fix and elapsed time - and only within one
--    session. A new session (its seq cleared on CONNECT, or the driver's first ever) has no
--    baseline: the app may have been off while the car moved, and judging its first fix against
--    the last one before would reject every position until enough time passed to excuse the
--    distance, striking the driver each time. The first fix of a session is taken as it is.
local movedMeters = 0
if prevLat ~= nil and prevLng ~= nil then
  movedMeters = haversineMeters(prevLat, prevLng, lat, lng)
  local elapsedMs = nowMs - (prevTs or nowMs)
  if elapsedMs > 0 and prevSeq ~= nil then
    local impliedKmph = (movedMeters / elapsedMs) * 3600
    if impliedKmph > maxSpeedKmph then
      spoofStrikes = spoofStrikes + 1
      redis.call('HSET', stateKey, 'spoofStrikes', spoofStrikes)
      redis.call('EXPIRE', stateKey, stateTtlSeconds)
      -- The position is discarded: trusting it would teleport the driver and make
      -- the next update's speed check meaningless too.
      return { 'rejected_spoof', spoofStrikes, 0 }
    end
  end
end

-- 4. Accepted. Record the position.
redis.call('HSET', stateKey, 'lat', lat, 'lng', lng, 'ts', nowMs, 'seq', seq)
redis.call('EXPIRE', stateKey, stateTtlSeconds)
redis.call('ZADD', lastSeenKey, nowMs, driverId)

-- 5. Availability. Only an AVAILABLE driver inside the service area belongs in a GEO
--    set. Everyone else is removed from all of them - a driver who went ON_TRIP or
--    drove out of the city must stop being offered work immediately, and removing
--    from all three also covers a vehicle type that changed while offline.
local geoWritten = 0
if status == 'AVAILABLE' and inServiceArea then
  local geoKey = geoKeyFor(vehicleType)
  if geoKey ~= nil then
    -- Skip the GEOADD for a driver who has barely moved, unless it has been long
    -- enough that the set would otherwise look stale. This is the write that costs
    -- the most, and a stationary driver at a rank produces nothing worth storing.
    local movedEnough = prevLat == nil or movedMeters >= minMoveMeters
    local overdue = lastGeoWriteTs == nil or (nowMs - lastGeoWriteTs) >= forceWriteMs
    if movedEnough or overdue then
      redis.call('GEOADD', geoKey, lng, lat, driverId)
      redis.call('HSET', stateKey, 'lastGeoWriteTs', nowMs)
      geoWritten = 1
    end
  end
else
  removeFromEveryAvailableSet()
end

-- 6. On a trip. Phase 6: the caller records the route and shows the rider where their
--    driver is - the latter at most every forwardIntervalMs, decided here, against the
--    same clock, so two instances cannot both decide to send.
local tripId = stored[9]
if tripId then
  local lastForwardTs = tonumber(stored[14])
  local forward = 0
  if lastForwardTs == nil or (nowMs - lastForwardTs) >= forwardIntervalMs then
    redis.call('HSET', stateKey, 'lastForwardTs', nowMs)
    forward = 1
  end
  return { 'accepted', spoofStrikes, geoWritten, tripId, stored[10] or '', stored[11] or '',
           stored[12] or '', stored[13] or '', forward, vehicleType or '' }
end

return { 'accepted', spoofStrikes, geoWritten }
