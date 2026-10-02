-- Release a driver's lock, but only if it is still the lock this caller took.
--
-- A plain DEL could delete a lock that expired and was taken by another offer in
-- the meantime, leaving that driver free to receive a second offer at once.
--
-- KEYS[1] driver:lock:{driverId}   ARGV[1] the offer id the lock was taken for
-- Returns 1 if released, 0 if the lock was gone or belonged to another offer.

if redis.call('GET', KEYS[1]) == ARGV[1] then
  return redis.call('DEL', KEYS[1])
end
return 0
