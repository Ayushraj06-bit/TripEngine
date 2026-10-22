\pset pager off
-- Spec section 2's invariants, over everything the load runs left in the database.
SELECT count(*) AS trips,
       count(*) FILTER (WHERE status = 'COMPLETED') AS completed,
       count(*) FILTER (WHERE assigned_at IS NOT NULL) AS ever_assigned,
       count(DISTINCT driver_id) AS drivers
FROM trips;

-- Double assignment: two trips of one driver whose time with the driver overlaps.
SELECT count(*) AS overlapping_assignments
FROM trips a JOIN trips b ON a.driver_id = b.driver_id AND a.id < b.id
WHERE a.assigned_at IS NOT NULL AND b.assigned_at IS NOT NULL
  AND tstzrange(a.assigned_at, coalesce(a.completed_at, a.cancelled_at, 'infinity'))
   && tstzrange(b.assigned_at, coalesce(b.completed_at, b.cancelled_at, 'infinity'));

-- Drivers holding more than one active trip right now.
SELECT count(*) AS drivers_with_two_active FROM (
  SELECT driver_id FROM trips
  WHERE status IN ('DRIVER_ASSIGNED', 'DRIVER_ARRIVED', 'IN_PROGRESS')
  GROUP BY driver_id HAVING count(*) > 1) d;

-- The ledger: every transaction balances, all entries sum to zero, cached balances agree.
SELECT count(*) AS transactions, (SELECT count(*) FROM ledger_entries) AS entries FROM ledger_transactions;
SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END), 0)
       AS all_entries_sum_paise FROM ledger_entries;
SELECT count(*) AS unbalanced_transactions FROM (
  SELECT transaction_id FROM ledger_entries GROUP BY transaction_id
  HAVING sum(CASE direction WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END) <> 0) t;
SELECT coalesce(sum(balance_paise), 0) AS all_balances_sum_paise,
       count(*) FILTER (WHERE balance_paise <> coalesce(e.net, 0)) AS balances_not_matching_entries
FROM ledger_accounts a LEFT JOIN (
  SELECT account_id, sum(CASE direction WHEN 'CREDIT' THEN amount_paise ELSE -amount_paise END) AS net
  FROM ledger_entries GROUP BY account_id) e ON e.account_id = a.id;
