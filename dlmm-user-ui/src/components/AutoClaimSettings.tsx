import { useSyncExternalStore } from 'react'
import { useQuery } from '@tanstack/react-query'
import {
  Card,
  Switch,
  InputNumber,
  Space,
  Typography,
  Alert,
  Tag,
  Divider,
  Button,
  Popover,
  List,
} from 'antd'
import { ThunderboltFilled, ClockCircleOutlined, EyeOutlined, StopOutlined } from '@ant-design/icons'
import { autoClaimStore, type AutoClaimPolicy } from '@/store/autoClaimStore'
import { previewFireable } from '@/lib/useAutoClaimWatcher'
import { pools as poolsApi } from '@/api/services'
import type { Pool, Position, TokenBalance } from '@/api/types'
import { balances } from '@/api/services'
import { formatRub } from '@/components/StatCard'

const { Text } = Typography

/**
 * Sprint 10 (new feature) + wave 3 polish — auto-claim settings card.
 *
 * Lives on Profile right rail. Wave 3 adds:
 *   - Live preview of "what would fire right now"
 *   - Daily cap (rolling 24h, hard safety limit)
 *   - ₽-equivalent hint next to the base-units threshold input
 *   - Per-pool exception list ("never auto-claim THIS pool")
 *
 * Persistence: localStorage via autoClaimStore. Backend swap-in path
 * unchanged (Sprint 11: POST /api/v1/users/auto-claim-policy).
 */
export default function AutoClaimSettings() {
  const policy = useSyncExternalStore(
    autoClaimStore.subscribe,
    autoClaimStore.get,
    () => ({ enabled: false, threshold: 1000, dailyCap: 20, skipPoolIds: [] } as AutoClaimPolicy),
  )
  const history = useSyncExternalStore(
    autoClaimStore.subscribe,
    autoClaimStore.history,
    () => [] as ReturnType<typeof autoClaimStore.history>,
  )

  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: poolsApi.getMyPositions,
  })
  const { data: poolList } = useQuery({
    queryKey: ['pools', 0, 100],
    queryFn: () => poolsApi.getPools(0, 100),
  })
  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const activePositions: Position[] = (myPositions ?? []).filter((p) => p.isActive)
  const poolById = new Map<string, Pool>((poolList?.content ?? []).map((p: Pool) => [p.id, p]))

  // Sprint 10 wave 3 — preview which positions would fire on the next
  // watcher tick. Pure-function call; cheap.
  const fireable = previewFireable(activePositions, policy.threshold, policy.skipPoolIds)

  // ₽-equivalent hint: convert the threshold (base units) into a
  // human-readable ₽ value using the SRUB-anchored balance map.
  // Imperfect since the threshold is sum(X+Y) in base units across
  // tokens of different decimals — we show the closest SRUB-equivalent
  // approximation so the user has a sanity check.
  const balanceBySymbol = new Map<string, TokenBalance>(
    (myBalances ?? []).map((b: TokenBalance) => [b.symbol, b]),
  )
  // If SRUB exists in the user's balance, assume threshold is in SRUB
  // base units (most common in seed pools). Otherwise show the raw
  // number unchanged.
  const thresholdRubHint = balanceBySymbol.has('SRUB')
    ? formatRub(policy.threshold)
    : null

  const last24h = autoClaimStore.countLast24h()
  const capped = policy.dailyCap > 0 && last24h >= policy.dailyCap

  // List of pools the user has currently muted (with friendly symbols).
  const skippedPools = policy.skipPoolIds
    .map((id) => poolById.get(id))
    .filter((p): p is Pool => !!p)
  // Pools that have at least one active position — used for the
  // "add exception" dropdown.
  const candidatePools = activePositions
    .map((p) => poolById.get(p.poolId))
    .filter((p): p is Pool => !!p && !policy.skipPoolIds.includes(p.id))
  // dedupe by id
  const uniqueCandidates = Array.from(new Map(candidatePools.map((p) => [p.id, p])).values())

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
        {capped && (
          <Alert
            type="warning"
            showIcon
            message={`Достигнут дневной лимит ${policy.dailyCap} срабатываний`}
            description="Следующие срабатывания будут пропущены до сброса 24-часового окна."
          />
        )}
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
              {thresholdRubHint && (
                <> · <Text type="secondary" style={{ fontSize: 11 }}>~{thresholdRubHint} в SRUB</Text></>
              )}
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

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
          <Space direction="vertical" size={0}>
            <Text strong>Дневной лимит</Text>
            <Text type="secondary" style={{ fontSize: 11 }}>
              Сколько срабатываний в сутки максимум · сейчас {last24h} из {policy.dailyCap || '∞'}
            </Text>
          </Space>
          <InputNumber
            min={0}
            max={500}
            step={1}
            disabled={!policy.enabled}
            value={policy.dailyCap}
            onChange={(v) => autoClaimStore.set({ ...policy, dailyCap: Math.max(0, Number(v) || 0) })}
            style={{ width: 140 }}
          />
        </div>

        {/* Sprint 10 wave 3 — live preview button. Pops a list of
            positions that would fire on the next watcher tick. Lets the
            user sanity-check before enabling. */}
        <Popover
          trigger="click"
          placement="top"
          content={
            <div style={{ minWidth: 280, maxWidth: 360 }}>
              <Text strong style={{ display: 'block', marginBottom: 8 }}>
                {fireable.length === 0
                  ? 'Сейчас ни одна позиция не превышает порог'
                  : `${fireable.length} позиций превысили порог`}
              </Text>
              {fireable.length === 0 ? (
                <Text type="secondary" style={{ fontSize: 12 }}>
                  Когда у активной позиции комиссии (X+Y) превысят {policy.threshold.toLocaleString('ru-RU')}, она появится здесь и будет автоматически забрана при включённом авто-сборе.
                </Text>
              ) : (
                <List
                  dataSource={fireable}
                  renderItem={(p) => (
                    <List.Item style={{ padding: '6px 0' }}>
                      <Space>
                        <Tag color="green" style={{ borderRadius: 999, marginInlineEnd: 0 }}>
                          {p.tokenXSymbol}/{p.tokenYSymbol}
                        </Tag>
                        <Text style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums' }}>
                          {(p.unclaimedFeeX + p.unclaimedFeeY).toLocaleString('ru-RU')}
                        </Text>
                      </Space>
                    </List.Item>
                  )}
                />
              )}
            </div>
          }
        >
          <Button icon={<EyeOutlined />} block>
            Предпросмотр: что сработает сейчас
            {fireable.length > 0 && <Tag color="orange" style={{ marginInlineStart: 8, borderRadius: 999 }}>{fireable.length}</Tag>}
          </Button>
        </Popover>

        {/* Per-pool exception list */}
        <div>
          <Text strong style={{ display: 'block', marginBottom: 6 }}>Исключения по пулам</Text>
          <Text type="secondary" style={{ fontSize: 11, display: 'block', marginBottom: 8 }}>
            Позиции из этих пулов никогда не забираются авто-сбором
          </Text>
          {skippedPools.length === 0 && (
            <Text type="secondary" style={{ fontSize: 12, display: 'block', marginBottom: 6 }}>
              Исключений нет.
            </Text>
          )}
          {skippedPools.length > 0 && (
            <Space size={[6, 6]} wrap style={{ marginBottom: 8 }}>
              {skippedPools.map((p) => (
                <Tag
                  key={p.id}
                  closable
                  onClose={() => autoClaimStore.toggleSkipPool(p.id)}
                  icon={<StopOutlined />}
                  style={{ borderRadius: 999 }}
                >
                  {p.tokenXSymbol}/{p.tokenYSymbol}
                </Tag>
              ))}
            </Space>
          )}
          {uniqueCandidates.length > 0 && (
            <Popover
              trigger="click"
              placement="top"
              content={
                <List
                  dataSource={uniqueCandidates}
                  renderItem={(p) => (
                    <List.Item
                      style={{ padding: '6px 0', cursor: 'pointer' }}
                      onClick={() => autoClaimStore.toggleSkipPool(p.id)}
                    >
                      <Space>
                        <StopOutlined />
                        <Text>{p.tokenXSymbol}/{p.tokenYSymbol}</Text>
                      </Space>
                    </List.Item>
                  )}
                />
              }
            >
              <Button size="small" type="dashed">+ Добавить пул в исключения</Button>
            </Popover>
          )}
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
