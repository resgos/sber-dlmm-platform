import { Card, Tabs } from 'antd'
import { PlusOutlined, SwapOutlined, AimOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { useTranslation } from 'react-i18next'
import type { Pool } from '@/api/types'
import PoolAddLiquidityPanel from './PoolAddLiquidityPanel'
import PoolSwapPanel from './PoolSwapPanel'
import LimitOrdersPanel from './LimitOrdersPanel'
import PoolZapPanel from './PoolZapPanel'

/**
 * Sprint 9-DS-r4 — Meteora-style tabbed action panel for the pool
 * page. Right rail of the pool "Dynamic Terminal": Add Liquidity /
 * Swap as sibling tabs so the LP never leaves the page to perform
 * any action.
 *
 * <p>Meteora's Dynamic Terminal has three tabs (Create Position / Limit
 * Order / Swap); Sprint 16 brought us to parity — Add Liquidity / Limit
 * Order / Swap.
 */
interface PoolActionTabsProps {
  pool: Pool
  /**
   * Which tab to open first. Add-liquidity is the primary LP action,
   * but pages that came from "Обмен" CTA can deep-link to swap.
   */
  defaultTab?: 'add' | 'swap' | 'orders' | 'zap'
  /**
   * OB-01 — optional controlled mode. When provided, the active tab is
   * driven by the parent (PoolDetailPage uses this so a click on an
   * order-book row jumps to the Swap tab). `onTabChange` is fired on
   * every user-driven switch so the parent state stays in sync.
   */
  activeTab?: 'add' | 'swap' | 'orders' | 'zap'
  onTabChange?: (tab: 'add' | 'swap' | 'orders' | 'zap') => void
  /**
   * Sprint 9-DS-r4 (P1-2) — forwarded to {@link PoolAddLiquidityPanel}
   * so the parent page (PoolDetailPage) can mirror the user's
   * pending strategy + range onto the bin chart as a live preview.
   * Null = no pending add; non-null = the user is composing.
   */
  onPreviewChange?: (preview: {
    binMin: number
    binMax: number
    strategy: import('@/api/types').LiquidityStrategy
  } | null) => void
  /**
   * OB-01 — a price level the user picked from the order book. Passed
   * through to {@link PoolSwapPanel} which surfaces it as a reference.
   */
  pickedPrice?: number | null
  /**
   * Sprint 16 (Meteora parity) — a bin range the user dragged on the chart;
   * forwarded to {@link PoolAddLiquidityPanel} which applies it to its bin inputs.
   */
  externalRange?: { binMin: number; binMax: number } | null
}

export default function PoolActionTabs({
  pool,
  defaultTab = 'add',
  activeTab,
  onTabChange,
  onPreviewChange,
  pickedPrice,
  externalRange,
}: PoolActionTabsProps) {
  const { t } = useTranslation()
  const controlled = activeTab != null
  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 'var(--radius-md)', border: '1px solid var(--border-light)' }}
      styles={{ body: { padding: '8px 16px 16px' } }}
    >
      <Tabs
        // Controlled when the parent supplies activeTab (OB-01 row-click
        // → Swap); otherwise uncontrolled with the defaultTab.
        {...(controlled ? { activeKey: activeTab } : { defaultActiveKey: defaultTab })}
        // Sprint 9-DS-r4 (P1-2) — when the user switches OFF the
        // Add tab, clear the preview so the chart returns to its
        // resting "your existing positions only" overlay.
        onChange={(key) => {
          if (key !== 'add') onPreviewChange?.(null)
          onTabChange?.(key as 'add' | 'swap' | 'orders' | 'zap')
        }}
        items={[
          {
            key: 'add',
            label: (
              <span>
                <PlusOutlined style={{ marginRight: 6 }} />
                {t('poolDetail.tabs.add')}
              </span>
            ),
            children: (
              <PoolAddLiquidityPanel pool={pool} onPreviewChange={onPreviewChange} externalRange={externalRange} />
            ),
          },
          {
            key: 'swap',
            label: (
              <span>
                <SwapOutlined style={{ marginRight: 6 }} />
                {t('poolDetail.tabs.swap')}
              </span>
            ),
            children: <PoolSwapPanel pool={pool} embedded pickedPrice={pickedPrice} />,
          },
          {
            key: 'orders',
            label: (
              <span>
                <AimOutlined style={{ marginRight: 6 }} />
                {t('poolDetail.tabs.limit')}
              </span>
            ),
            children: <LimitOrdersPanel pool={pool} />,
          },
          {
            key: 'zap',
            label: (
              <span>
                <ThunderboltOutlined style={{ marginRight: 6 }} />
                {t('poolDetail.tabs.zap')}
              </span>
            ),
            children: <PoolZapPanel pool={pool} />,
          },
        ]}
      />
    </Card>
  )
}
