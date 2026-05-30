// External real-world price references.
//
// The platform's tokenised assets mirror real markets, so we surface a LIVE
// real-world price next to our internal pool price as an «ориентир». Source is
// CoinGecko's public API, which is CORS-enabled (verified from the browser) —
// so this runs client-side, no backend proxy.
//
// NB: Russian equities (SBER/GAZP/LKOH/…) and MOEX indices are NOT on CoinGecko,
// and MOEX ISS is not CORS-enabled (neither fetch nor JSONP works from the
// browser) — real references for those need a backend proxy in price-oracle
// (PC-01). Tracked as a follow-up; this module covers the crypto/commodity/FX
// tokens that have a clean CoinGecko analog.

import { useQuery } from '@tanstack/react-query'

/** Platform symbol → CoinGecko coin id. Only assets with a clean 1:1 real-world
 *  analog are mapped; unmapped symbols simply get no external reference. */
export const CG_ID_BY_SYMBOL: Record<string, string> = {
  SBTC: 'bitcoin',
  SETH: 'ethereum',
  SUSDT: 'tether',
  SGOLD: 'pax-gold',
  STON: 'the-open-network',
  SBNB: 'binancecoin',
}

const CG_PRICE_URL = 'https://api.coingecko.com/api/v3/simple/price'

export interface ExternalRef {
  symbol: string
  coinId: string
  priceRub: number
  source: 'CoinGecko'
}

async function fetchExternalRefs(): Promise<Record<string, ExternalRef>> {
  const ids = [...new Set(Object.values(CG_ID_BY_SYMBOL))].join(',')
  const res = await fetch(`${CG_PRICE_URL}?ids=${ids}&vs_currencies=rub`)
  if (!res.ok) throw new Error(`CoinGecko ${res.status}`)
  const data = (await res.json()) as Record<string, { rub?: number }>
  const out: Record<string, ExternalRef> = {}
  for (const [symbol, coinId] of Object.entries(CG_ID_BY_SYMBOL)) {
    const p = data[coinId]?.rub
    if (typeof p === 'number' && p > 0) {
      out[symbol] = { symbol, coinId, priceRub: p, source: 'CoinGecko' }
    }
  }
  return out
}

/** All available external references, keyed by platform symbol. One network
 *  call covers every mapped asset; cached 60s, refreshed every 2 min (well
 *  within CoinGecko's free-tier rate limit). */
export function useExternalRefs() {
  return useQuery({
    queryKey: ['externalRefs'],
    queryFn: fetchExternalRefs,
    staleTime: 60_000,
    refetchInterval: 120_000,
    retry: 1,
  })
}

/** External reference for a single platform symbol, or null if unmapped /
 *  unavailable. */
export function useExternalRef(symbol: string | null | undefined): ExternalRef | null {
  const { data } = useExternalRefs()
  if (!symbol) return null
  return data?.[symbol] ?? null
}
