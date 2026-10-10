-- Count one ride request towards surge (PROJECT_SPEC section 8, ADR 0014).
--
-- Once per request: the marker and the count go together, so a redelivered
-- TripRequested finds its marker and counts nothing. The cell is noted as active
-- with the minute of its latest request, so the surge tick finds the cells with
-- demand without scanning the keyspace.
--
-- KEYS[1] surge:demand:seen:{requestId}   KEYS[2] surge:demand:{cell}:{epochMinute}
-- KEYS[3] surge:demand:cells
-- ARGV[1] ttlMs (section 7: 10 minutes)   ARGV[2] epochMinute   ARGV[3] cell
--
-- Returns 1 if counted, 0 for a request already counted.

if not redis.call('SET', KEYS[1], '1', 'NX', 'PX', ARGV[1]) then
  return 0
end
redis.call('INCR', KEYS[2])
redis.call('PEXPIRE', KEYS[2], ARGV[1])
-- GT: a late, older request never pulls the cell's latest minute back.
redis.call('ZADD', KEYS[3], 'GT', ARGV[2], ARGV[3])
return 1
