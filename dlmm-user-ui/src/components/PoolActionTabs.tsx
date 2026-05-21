import { Card, Tabs } from 'antd'
import { PlusOutlined, SwapOutlined } from '@ant-design/icons'
import type { Pool } from '@/api/types'
import PoolAddLiquidityPanel from './PoolAddLiquidityPanel'
import PoolSwapPanel from './PoolSwapPanel'

/**
 * Sprint 9-DS-r4 — Meteora-style tabbed action panel for the pool
 * page. Right rail of the pool "Dynamic Terminal": Add Liquidity /
 * Swap as sibling tabs so the LP never leaves the page to perform
 * any action.
 *
 * <p>Meteora itself has three tabs (Create Position / Limit Order /
 * Swap). We don't have limit orders yet, so two tabs for now.
 */
interface PoolActionTabsProps {
  pool: Pool
  /**
   * Which tab to open first. Add-liquidity is the primary LP action,
   * but pages that came from "Обмен" CTA can deep-link to swap.
   */
  defaultTab?: 'add' | 'swap'
}

export default function PoolActionTabs({ pool, defaultTab = 'add' }: PoolActionTabsProps) {
  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 16, border: '1px solid var(--border-light)' }}
      styles={{ body: { padding: '8px 16px 16px' } }}
    >
      <Tabs
        defaultActiveKey={defaultTab}
        items={[
          {
            key: 'add',
            label: (
              <span>
                <PlusOutlined style={{ marginRight: 6 }} />
                Добавить ликвидность
              </span>
            ),
            children: <PoolAddLiquidityPanel pool={pool} />,
          },
          {
            key: 'swap',
            label: (
              <span>
                <SwapOutlined style={{ marginRight: 6 }} />
                Обменять
              </span>
            ),
            children: <PoolSwapPanel pool={pool} embedded />,
          },
        ]}
      />
    </Card>
  )
}
