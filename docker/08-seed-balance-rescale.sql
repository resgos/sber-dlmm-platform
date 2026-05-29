-- Demo review finding (2026-05-27) — companion to 06-seed-tvl-rescale.sql.
--
-- 06 rescaled pool TVL /10000 (163 трлн → ~15 млрд) but left user token
-- balances untouched, so Dashboard hero showed "ВАШ ПОРТФЕЛЬ 8.66 квд ₽"
-- (8.66 quadrillion roubles — ~100× Russia GDP). On the FIRST demo screen
-- that's an instant credibility killer.
--
-- Root cause: seed mixed two scales — some tokens seeded by round
-- nominal-VALUE (→ billions/trillions of base units), others by round
-- unit COUNT (→ hundreds/thousands). The big-unit ones (16 balances ≥ 1B)
-- dominate the portfolio sum.
--
-- Fix: divide balances ≥ 1,000,000 units by 1,000,000. Brings the hero
-- portfolio to ~8.66 млрд ₽ — comparable to a single pool's TVL (~15 млрд),
-- i.e. ivanov reads as a meaningful-but-not-absurd pilot treasury. Small
-- balances (< 1M units, e.g. SEUR 48757, LKOH 860) stay untouched so the
-- token table keeps a realistic mix of large + boutique holdings.
--
-- Math safety: balances are independent scalars (not part of any ratio
-- the engine computes); rescaling them can't break swap/add-liq/claim
-- logic — those operate on whatever magnitude is present. Verified live:
-- swap + add-liquidity + claim all still atomic post-rescale.
--
-- Runs on container start after 06/07.

UPDATE user_balances
SET available = available / 1000000,
    locked    = locked / 1000000
WHERE available >= 1000000 OR locked >= 1000000;

DO $$
DECLARE
  max_bal bigint;
BEGIN
  SELECT MAX(available) INTO max_bal FROM user_balances;
  RAISE NOTICE 'Balance rescale done. Max balance now: %', max_bal;
END $$;
