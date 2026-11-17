-- What the rider called the pickup and the drop when booking, for showing the trip later
-- (ADR 0028). Display only: pricing, matching and the fare go by the points. Null for trips
-- booked before, and for clients that do not send them.
ALTER TABLE trips
    ADD COLUMN pickup_name varchar(200),
    ADD COLUMN drop_name   varchar(200);
