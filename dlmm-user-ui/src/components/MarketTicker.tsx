import { Card, Empty, Tag } from 'antd'
import { ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import TokenIcon from './TokenIcon'
import type { TokenPrice } from '@/api/types'

/**
 * «Биржевые цены» — real exchange/reference prices for the platform's tokenised
 * assets, fetched server-side from free public APIs (CoinGecko crypto, Bank of
 * Russia FX + precious metals) by dlmm-price-oracle. Reads the same
 * oracle.getPrices feed the dashboard already loads.
 */
const MARKET_ASSETS: { symbol: string; name: string; unit?: string }[] = [
  { symbol: 'SBTC', name: 'Bitcoin' },
  { symbol: 'SETH', name: 'Ethereum' },
  { symbol: 'SGOLD', name: 'Золото', unit: ' / г' },
  { symbol: 'SSILV', name: 'Серебро', unit: ' / г' },
  { symbol: 'SPLAT', name: 'Платина', unit: ' / г' },
  { symbol: 'SPALD', name: 'Палладий', unit: ' / г' },
  { symbol: 'SUSDT', name: 'Доллар США' },
  { symbol: 'SEUR', name: 'Евро' },
  { symbol: 'SCNY', name: 'Юань' },
]

const SOURCE_LABEL: Record<string, string> = {
  COINGECKO: 'CoinGecko',
  'CBR-FX': 'ЦБ РФ',
  'CBR-METALS': 'ЦБ РФ',
}

function fmtRub(v: number): string {
  const digits = v >= 1000 ? 0 : 2
  return v.toLocaleString('ru-RU', { minimumFractionDigits: digits, maximumFractionDigits: digits })
}

export default function MarketTicker({ prices }: { prices?: TokenPrice[] }) {
  const bySym = new Map((prices ?? []).map((p) => [p.symbol, p]))
  const rows = MARKET_ASSETS
    .map((a) => ({ a, p: bySym.get(a.symbol) }))
    .filter((x) => x.p != null && (x.p.price ?? 0) > 0)

  return (
    <Card
      title="Биржевые цены"
      className="sber-card"
      styles={{ body: { padding: 'var(--space-3)' } }}
      extra={
        <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
          обновление ~каждые 2 мин
        </span>
      }
    >
      {rows.length === 0 ? (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Котировки загружаются…" />
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
                  <span className="sber-market-ticker__name">{a.name}</span>
                  <Tag className="sber-market-ticker__src" bordered={false}>
                    {SOURCE_LABEL[price.source ?? ''] ?? 'биржа'}
                  </Tag>
                </div>
                <div className="sber-market-ticker__px">
                  <span className="sber-market-ticker__price">
                    {fmtRub(price.price)} ₽{a.unit ?? ''}
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
