-- Seed accounts for local runs and manual testing.
-- MERGE keeps startup idempotent if the schema is re-initialised.
MERGE INTO accounts (id, balance, currency, version) KEY (id) VALUES (42, 1000.00, 'ZAR', 0);
MERGE INTO accounts (id, balance, currency, version) KEY (id) VALUES (43, 50.00, 'ZAR', 0);
MERGE INTO accounts (id, balance, currency, version) KEY (id) VALUES (44, 2500.00, 'USD', 0);
