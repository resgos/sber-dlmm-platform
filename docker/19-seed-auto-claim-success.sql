-- 15-seed-auto-claim-success.sql
--
-- Demo data: a handful of SUCCESSFUL auto-claim runs for the demo user so the
-- "История авто-сбора" panel (Profile → Настройки → Авто-сбор комиссий) shows
-- the working happy path. The only pre-existing auto_claim_log rows are 12
-- historical FAILUREs from the 2026-05-25 token-service circuit-breaker
-- incident, which alone made auto-claim look broken on the investor demo.
--
-- Idempotent: fixed ids + ON CONFLICT (id) DO NOTHING (safe to re-run).
-- Amounts are raw x10^4 units (1 unit = 10^-4 token); the UI's scale.ts
-- converts to human (e.g. amount_y 950000 -> 95 SRUB). Positions/pools reuse
-- the demo user's real seeded positions so pool symbols resolve in the UI.

INSERT INTO auto_claim_log (id, user_id, position_id, pool_id, amount_x, amount_y, status, error_message, fired_at) VALUES
  ('ac000000-0000-0000-0000-000000000001', 'a0000000-0000-0000-0000-000000000002', '77000000-0000-0000-0000-000000000003', 'c0000000-0000-0000-0000-000000000101',  18000,  950000, 'SUCCESS', NULL, '2026-06-15 09:12:03'),
  ('ac000000-0000-0000-0000-000000000002', 'a0000000-0000-0000-0000-000000000002', '77000000-0000-0000-0000-000000000005', 'c0000000-0000-0000-0000-000000000111',      0,  640000, 'SUCCESS', NULL, '2026-06-15 06:40:11'),
  ('ac000000-0000-0000-0000-000000000003', 'a0000000-0000-0000-0000-000000000002', '77000000-0000-0000-0000-000000000001', 'c0000000-0000-0000-0000-000000000004', 120000,  310000, 'SUCCESS', NULL, '2026-06-14 18:05:47'),
  ('ac000000-0000-0000-0000-000000000004', 'a0000000-0000-0000-0000-000000000002', 'd0000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002',      0,  825000, 'SUCCESS', NULL, '2026-06-14 11:22:30')
ON CONFLICT (id) DO NOTHING;
