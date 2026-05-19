import { useState, useMemo } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card, Typography, Space, Button, InputNumber, Tabs, Table, Tag, Slider, Modal,
  Alert, Spin, Divider, message,
} from 'antd'
import { ArrowLeftOutlined, PlusOutlined, DeleteOutlined, DollarOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { pools, balances, fees } from '@/api/services'
import type { Position, LiquidityStrategy } from '@/api/types'
import StrategySelector from '@/components/StrategySelector'
import BinLiquidityChart from '@/components/BinLiquidityChart'

const { Title, Text } = Typography

export default function LiquidityPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const [strategy, setStrategy] = useState<LiquidityStrategy>('SPOT')
  const [binMin, setBinMin] = useState<number | null>(null)
  const [binMax, setBinMax] = useState<number | null>(null)
  const [amountX, setAmountX] = useState<number | null>(null)
  const [amountY, setAmountY] = useState<number | null>(null)
  const [removeModalPos, setRemoveModalPos] = useState<Position | null>(null)
  const [removePercent, setRemovePercent] = useState(100)

  const { data: pool, isLoading } = useQuery({
    queryKey: ['poolDetail', id],
    queryFn: () => pools.getPool(id!),
    enabled: !!id,
  })

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const { data: myPositions } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  const poolPositions = useMemo(
    () => (myPositions || []).filter((p: Position) => p.poolId === id && p.isActive),
    [myPositions, id],
  )

  const balanceX = myBalances?.find((b) => b.symbol === pool?.tokenXSymbol)
  const balanceY = myBalances?.find((b) => b.symbol === pool?.tokenYSymbol)

  const addMutation = useMutation({
    mutationFn: () =>
      pools.addLiquidity({
        poolId: id!,
        amountX: amountX!,
        amountY: amountY!,
        binRangeMin: binMin!,
        binRangeMax: binMax!,
        strategy,
        idempotencyKey: crypto.randomUUID(),
      }),
    onSuccess: () => {
      message.success('Ликвидность успешно добавлена')
      setAmountX(null)
      setAmountY(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: (err: any) => {
      message.error(err?.response?.data?.message || 'Ошибка при добавлении ликвидности')
    },
  })

  const removeMutation = useMutation({
    mutationFn: (positionId: string) =>
      pools.removeLiquidity({
        positionId,
        percentage: removePercent,
        idempotencyKey: crypto.randomUUID(),
      }),
    onSuccess: () => {
      message.success('Ликвидность удалена')
      setRemoveModalPos(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['poolDetail', id] })
    },
    onError: (err: any) => {
      message.error(err?.response?.data?.message || 'Ошибка при удалении ликвидности')
    },
  })

  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
    onSuccess: () => {
      message.success('Комиссии забраны')
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: (err: any) => {
      message.error(err?.response?.data?.message || 'Ошибка')
    },
  })

  if (isLoading) return <div style={{ textAlign: 'center', padding: '80px 0' }}><Spin size="large" /></div>
  if (!pool) return <Alert message="Пул не найден" type="error" showIcon />

  const canAdd = amountX && amountY && binMin != null && binMax != null && binMin < binMax

  const tabItems = [
    {
      key: 'add',
      label: 'Добавить ликвидность',
      children: (
        <Space direction="vertical" size={20} style={{ width: '100%' }}>
          <div>
            <Text strong style={{ display: 'block', marginBottom: 12 }}>Стратегия распределения</Text>
            <StrategySelector value={strategy} onChange={setStrategy} />
          </div>

          <div>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>Диапазон бинов</Text>
            <Space>
              <InputNumber
                placeholder="Мин бин"
                value={binMin}
                onChange={(v) => setBinMin(v)}
                style={{ width: 140 }}
              />
              <Text type="secondary">—</Text>
              <InputNumber
                placeholder="Макс бин"
                value={binMax}
                onChange={(v) => setBinMax(v)}
                style={{ width: 140 }}
              />
            </Space>
            <div style={{ fontSize: 12, color: '#9CA3AF', marginTop: 4 }}>
              Текущая цена в бине #{pool.activeBinId}. Рекомендуемый диапазон концентрации:
              {' '}±10 бинов вокруг текущей цены (узкий диапазон = выше комиссии, но риск выхода из диапазона).
            </div>
          </div>

          <div>
            <Text strong style={{ display: 'block', marginBottom: 8 }}>Суммы токенов</Text>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <Text>{pool.tokenXSymbol}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    Доступно: {balanceX?.available.toLocaleString('ru-RU') ?? 0}
                    {balanceX && (
                      <Button type="link" size="small" style={{ padding: '0 4px', fontSize: 12 }}
                        onClick={() => setAmountX(balanceX.available)}>MAX</Button>
                    )}
                  </Text>
                </div>
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="0.00"
                  value={amountX}
                  onChange={(v) => setAmountX(v)}
                  min={0}
                  size="large"
                  controls={false}
                />
              </div>
              <div>
                <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
                  <Text>{pool.tokenYSymbol}</Text>
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    Доступно: {balanceY?.available.toLocaleString('ru-RU') ?? 0}
                    {balanceY && (
                      <Button type="link" size="small" style={{ padding: '0 4px', fontSize: 12 }}
                        onClick={() => setAmountY(balanceY.available)}>MAX</Button>
                    )}
                  </Text>
                </div>
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder="0.00"
                  value={amountY}
                  onChange={(v) => setAmountY(v)}
                  min={0}
                  size="large"
                  controls={false}
                />
              </div>
            </Space>
          </div>

          <Button
            type="primary"
            block
            size="large"
            icon={<PlusOutlined />}
            disabled={!canAdd}
            loading={addMutation.isPending}
            onClick={() => addMutation.mutate()}
            style={{ height: 48, fontSize: 15, fontWeight: 600, borderRadius: 10 }}
          >
            Добавить ликвидность
          </Button>
        </Space>
      ),
    },
    {
      key: 'positions',
      label: `Мои позиции (${poolPositions.length})`,
      children: poolPositions.length === 0 ? (
        <Text type="secondary">У вас нет позиций в этом пуле</Text>
      ) : (
        <Table
          className="sber-table"
          dataSource={poolPositions}
          rowKey="id"
          pagination={false}
          size="middle"
          columns={[
            { title: 'Стратегия', dataIndex: 'strategy', render: (s: string) => <Tag color="blue">{s}</Tag> },
            { title: 'Диапазон', key: 'range', render: (_: unknown, r: Position) => `${r.binRangeMin} — ${r.binRangeMax}` },
            { title: 'Незабранные X', dataIndex: 'unclaimedFeeX', align: 'right' as const, render: (v: number) => v.toLocaleString('ru-RU') },
            { title: 'Незабранные Y', dataIndex: 'unclaimedFeeY', align: 'right' as const, render: (v: number) => v.toLocaleString('ru-RU') },
            {
              title: 'Действия',
              key: 'actions',
              render: (_: unknown, r: Position) => (
                <Space>
                  <Button size="small" icon={<DollarOutlined />} onClick={() => claimMutation.mutate(r.id)}
                    loading={claimMutation.isPending}
                    disabled={r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0}>
                    Забрать
                  </Button>
                  <Button size="small" danger icon={<DeleteOutlined />} onClick={() => setRemoveModalPos(r)}>
                    Удалить
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      ),
    },
  ]

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Space>
        <Button icon={<ArrowLeftOutlined />} type="text" onClick={() => navigate(`/pools/${id}`)} />
        <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
          Ликвидность: {pool.tokenXSymbol}/{pool.tokenYSymbol}
        </Title>
      </Space>

      <Card className="sber-card" title={<Text strong>Распределение ликвидности</Text>}>
        <BinLiquidityChart poolId={pool.id} />
      </Card>

      <Card className="sber-card">
        <Tabs items={tabItems} />
      </Card>

      <Modal
        title="Удаление ликвидности"
        open={!!removeModalPos}
        onCancel={() => setRemoveModalPos(null)}
        onOk={() => removeModalPos && removeMutation.mutate(removeModalPos.id)}
        confirmLoading={removeMutation.isPending}
        okText="Удалить"
        cancelText="Отмена"
        okButtonProps={{ danger: true }}
      >
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Text>Какой процент ликвидности удалить?</Text>
          <Slider
            min={1}
            max={100}
            value={removePercent}
            onChange={setRemovePercent}
            marks={{ 25: '25%', 50: '50%', 75: '75%', 100: '100%' }}
          />
          <Text strong style={{ textAlign: 'center', display: 'block', fontSize: 24, color: '#EF4444' }}>
            {removePercent}%
          </Text>
        </Space>
      </Modal>
    </Space>
  )
}
