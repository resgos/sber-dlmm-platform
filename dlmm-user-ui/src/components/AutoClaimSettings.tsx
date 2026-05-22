import { useSyncExternalStore } from 'react'
import { Card, Switch, InputNumber, Space, Typography, Alert, Tag, Divider } from 'antd'
import { ThunderboltFilled, ClockCircleOutlined } from '@ant-design/icons'
import { autoClaimStore, type AutoClaimPolicy } from '@/store/autoClaimStore'

const { Text } = Typography

/**
 * Sprint 10 (new feature) — auto-claim settings card.
 *
 * Lives on the Profile page right rail. Toggle + threshold input;
 * recent firings shown below the form as an in-memory audit list
 * so the user can verify the watcher is working as expected.
 *
 * Persistence: localStorage via autoClaimStore. Per-tab only (the
 * watcher only fires when the tab is open with PositionsPage
 * mounted). Backend swap-in (Sprint 11): the same policy shape
 * lands as POST /api/v1/users/auto-claim-policy + scheduled checker.
 */
export default function AutoClaimSettings() {
  const policy = useSyncExternalStore(
    autoClaimStore.subscribe,
    autoClaimStore.get,
    () => ({ enabled: false, threshold: 1000 } as AutoClaimPolicy),
  )
  const history = useSyncExternalStore(
    autoClaimStore.subscribe,
    autoClaimStore.history,
    () => [] as ReadonlyArray<{ positionId: string; symbol: string; amount: number; firedAt: string }>,
  )

  return (
    <Card
      className="sber-card"
      title={
        <Space>
          <ThunderboltFilled style={{ color: 'var(--sber-amber)' }} />
          <Text strong>Авто-сбор комиссий</Text>
        </Space>
      }
    >
      <Space direction="vertical" size={14} style={{ width: '100%' }}>
        <Alert
          message={policy.enabled ? 'Авто-сбор включён' : 'Авто-сбор выключен'}
          description={
            policy.enabled
              ? 'Пока эта вкладка открыта, комиссии будут забираться автоматически, когда сумма по позиции превысит порог.'
              : 'Включите авто-сбор, чтобы платформа сама забирала накопленные комиссии и не давала им «копиться без дела».'
          }
          type={policy.enabled ? 'success' : 'info'}
          showIcon
        />

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16 }}>
          <Text strong>Включить</Text>
          <Switch
            checked={policy.enabled}
            onChange={(checked) => autoClaimStore.set({ ...policy, enabled: checked })}
          />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
          <Space direction="vertical" size={0}>
            <Text strong>Порог</Text>
            <Text type="secondary" style={{ fontSize: 11 }}>
              Минимальная накопленная комиссия (X+Y, базовые единицы) для запуска
            </Text>
          </Space>
          <InputNumber
            min={1}
            step={100}
            disabled={!policy.enabled}
            value={policy.threshold}
            onChange={(v) => autoClaimStore.set({ ...policy, threshold: Number(v) || 1 })}
            style={{ width: 140 }}
          />
        </div>

        <Text type="secondary" style={{ fontSize: 11 }}>
          <ClockCircleOutlined style={{ marginInlineEnd: 4 }} />
          На одну позицию срабатывает не чаще раза в час — так избегаем «двойного клика» при быстром обновлении данных.
          Backend-версия с серверным расписанием — Sprint 11.
        </Text>

        {history.length > 0 && (
          <>
            <Divider style={{ margin: '4px 0' }} />
            <Text strong style={{ fontSize: 12 }}>Последние срабатывания (с момента открытия вкладки)</Text>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              {history.slice(0, 8).map((h, i) => (
                <div key={i} style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12 }}>
                  <Space size={6}>
                    <Tag color="green" style={{ borderRadius: 999 }}>{h.symbol}</Tag>
                    <Text type="secondary">{h.amount.toLocaleString('ru-RU')}</Text>
                  </Space>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {new Date(h.firedAt).toLocaleTimeString('ru-RU')}
                  </Text>
                </div>
              ))}
            </Space>
          </>
        )}
      </Space>
    </Card>
  )
}
