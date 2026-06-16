import { Card, Empty, Tag } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import TokenIcon from './TokenIcon'
import type { TokenPrice } from '@/api/types'

/**
 * Market prices ticker — real exchange/reference prices for the platform's
 * tokenised assets, fetched server-side from free public APIs (CoinGecko
 * crypto, Bank of Russia FX + precious metals) by dlmm-price-oracle. Reads the
 * same oracle.getPrices feed the dashboard already loads.
 *
 * Display names ({@code marketTicker.assets.*}), the per-gram unit, the source
 * labels and the card chrome are all resolved through i18n so an English user
 * sees an English header ticker (Золото → Gold, ЦБ РФ → CBR) instead of
 * Cyrillic. Bitcoin/Ethereum read identically in both locales.
 */
const MARKET_ASSETS: { symbol: string; gram?: boolean }[] = [
  { symbol: 'SBTC' },
  { symbol: 'SETH' },
  { symbol: 'SGOLD', gram: true },
  { symbol: 'SSILV', gram: true },
  { symbol: 'SPLAT', gram: true },
  { symbol: 'SPALD', gram: true },
  { symbol: 'SUSDT' },
  { symbol: 'SEUR' },
  { symbol: 'SCNY' },
]

// CBR-FX and CBR-METALS both surface as the "Bank of Russia" source label;
// COINGECKO keeps its brand name; anything else falls back to "exchange".
const CBR_SOURCES = new Set(['CBR-FX', 'CBR-METALS'])

export default function MarketTicker({ prices }: { prices?: TokenPrice[] }) {
  const { t, i18n } = useTranslation()
  const numLocale = i18n.language?.startsWith('en') ? 'en-US' : 'ru-RU'

  const fmtRub = (v: number): string => {
    const digits = v >= 1000 ? 0 : 2
    return v.toLocaleString(numLocale, { minimumFractionDigits: digits, maximumFractionDigits: digits })
  }

  const sourceLabel = (source?: string): string => {
    if (source === 'COINGECKO') return 'CoinGecko'
    if (source && CBR_SOURCES.has(source)) return t('marketTicker.source.cbr')
    return t('marketTicker.source.exchange')
  }

  const bySym = new Map((prices ?? []).map((p) => [p.symbol, p]))
  const rows = MARKET_ASSETS
    .map((a) => ({ a, p: bySym.get(a.symbol) }))
    .filter((x) => x.p != null && (x.p.price ?? 0) > 0)

  return (
    <Card
      title={t('marketTicker.title')}
      className="sber-card"
      styles={{ body: { padding: 'var(--space-3)' } }}
      extra={
        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
          {t('marketTicker.updateInterval')}
        </span>
      }
    >
      {rows.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={t('marketTicker.loading')} />
      ) : (
        <div className="sber-market-ticker">
          {rows.map(({ a, p }) => {
            const price = p as TokenPrice
            const chg = price.change24h ?? 0
            const up = chg >= 0
            return (
              <div key={a.symbol} className="sber-market-ticker__item">
                <TokenIcon symbol={a.symbol} size={30} decorative />
                <div className="sber-market-ticker__meta">
                  <span className="sber-market-ticker__name">
                    {t(`marketTicker.assets.${a.symbol}`, { defaultValue: a.symbol })}
                  </span>
                  <Tag className="sber-market-ticker__src" bordered={false}>
                    {sourceLabel(price.source)}
                  </Tag>
                </div>
                <div className="sber-market-ticker__px">
                  <span className="sber-market-ticker__price">
                    {fmtRub(price.price)} ₽{a.gram ? t('marketTicker.perGram') : ''}
                  </span>
                  <span className={`sber-market-ticker__chg ${up ? 'is-up' : 'is-down'}`}>
                    {up ? <ArrowUpOutlined /> : <ArrowDownOutlined />}
                    {Math.abs(chg).toFixed(2)}%
                  </span>
                </div>
              </div>
            )
          })}
        </div>
      )}
    </Card>
  )
}
