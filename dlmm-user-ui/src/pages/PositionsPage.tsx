import { useState } from 'react'
import { Table, Tag, Typography, Space, Button, Card, Modal, Slider, message, Row, Col } from 'antd'
import { DollarOutlined, DeleteOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { pools, fees } from '@/api/services'
import type { Position, FeeHistoryEntry } from '@/api/types'
import StatCard, { formatRub } from '@/components/StatCard'
import { PieChartOutlined, TrophyOutlined, WalletOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'

const { Title, Text } = Typography

export default function PositionsPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [removeModalPos, setRemoveModalPos] = useState<Position | null>(null)
  const [removePercent, setRemovePercent] = useState(100)

  const { data: myPositions, isLoading } = useQuery({
    queryKey: ['myPositions'],
    queryFn: pools.getMyPositions,
  })

  const { data: feeSummary } = useQuery({
    queryKey: ['myFeeSummary'],
    queryFn: fees.getMyFeeSummary,
  })

  const { data: feeHistory } = useQuery({
    queryKey: ['myFeeHistory'],
    queryFn: () => fees.getMyFeeHistory(0, 20),
  })

  const claimMutation = useMutation({
    mutationFn: (positionId: string) => fees.claimFees({ positionId }),
    onSuccess: () => {
      message.success('Комиссии забраны')
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeSummary'] })
      queryClient.invalidateQueries({ queryKey: ['myFeeHistory'] })
    },
    onError: (err: any) => message.error(err?.response?.data?.message || 'Ошибка'),
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
      queryClient.invalidateQueries({ queryKey: ['myPositions'] })
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
    },
    onError: (err: any) => message.error(err?.response?.data?.message || 'Ошибка'),
  })

  const activePositions = (myPositions || []).filter((p: Position) => p.isActive)

  const totalUnclaimedX = activePositions.reduce((s: number, p: Position) => s + p.unclaimedFeeX, 0)
  const totalUnclaimedY = activePositions.reduce((s: number, p: Position) => s + p.unclaimedFeeY, 0)

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <Title level={4} className="sber-page-title">Мои позиции</Title>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}>
          <StatCard title="Активные позиции" value={activePositions.length}
            icon={<PieChartOutlined />} iconBg="#F3E8FF" iconColor="#8B5CF6" />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard title="Незабранные комиссии" value={feeSummary?.totalUnclaimed ?? 0}
            icon={<WalletOutlined />} iconBg="#E0F2FE" iconColor="#0EA5E9" formatter={formatRub} />
        </Col>
        <Col xs={24} sm={8}>
          <StatCard title="Всего заработано" value={feeSummary?.totalClaimed ?? 0}
            icon={<TrophyOutlined />} iconBg="#E8F5E9" iconColor="#21A038" formatter={formatRub} />
        </Col>
      </Row>

      <Card className="sber-card" title={<Text strong>Позиции</Text>}>
        <Table
          className="sber-table"
          loading={isLoading}
          dataSource={activePositions}
          rowKey="id"
          pagination={false}
          size="middle"
          onRow={(record) => ({
            style: { cursor: 'pointer' },
            onClick: () => navigate(`/pools/${record.poolId}`),
          })}
          columns={[
            {
              title: 'Пул',
              key: 'pool',
              render: (_: unknown, r: Position) => <Text strong>{r.tokenXSymbol}/{r.tokenYSymbol}</Text>,
            },
            { title: 'Стратегия', dataIndex: 'strategy', render: (s: string) => <Tag color="blue">{s}</Tag> },
            {
              title: 'Диапазон бинов',
              key: 'range',
              render: (_: unknown, r: Position) => `${r.binRangeMin} — ${r.binRangeMax}`,
            },
            {
              title: 'Незабранные X',
              dataIndex: 'unclaimedFeeX',
              align: 'right' as const,
              render: (v: number) => v > 0 ? <Text type="success">{v.toLocaleString('ru-RU')}</Text> : '—',
            },
            {
              title: 'Незабранные Y',
              dataIndex: 'unclaimedFeeY',
              align: 'right' as const,
              render: (v: number) => v > 0 ? <Text type="success">{v.toLocaleString('ru-RU')}</Text> : '—',
            },
            {
              title: 'Создана',
              dataIndex: 'createdAt',
              render: (d: string) => dayjs(d).format('DD.MM.YYYY'),
            },
            {
              title: 'Действия',
              key: 'actions',
              render: (_: unknown, r: Position) => (
                <Space onClick={(e) => e.stopPropagation()}>
                  <Button size="small" type="primary" ghost icon={<DollarOutlined />}
                    onClick={() => claimMutation.mutate(r.id)}
                    loading={claimMutation.isPending}
                    disabled={r.unclaimedFeeX === 0 && r.unclaimedFeeY === 0}>
                    Забрать
                  </Button>
                  <Button size="small" danger icon={<DeleteOutlined />}
                    onClick={() => { setRemoveModalPos(r); setRemovePercent(100) }}>
                    Удалить
                  </Button>
                </Space>
              ),
            },
          ]}
        />
      </Card>

      {/* Fee history */}
      {feeHistory?.content && feeHistory.content.length > 0 && (
        <Card className="sber-card" title={<Text strong>История комиссий</Text>}>
          <Table
            className="sber-table"
            dataSource={feeHistory.content}
            rowKey="id"
            pagination={false}
            size="small"
            columns={[
              {
                title: 'Дата',
                dataIndex: 'accruedAt',
                render: (d: string) => dayjs(d).format('DD.MM.YYYY HH:mm'),
              },
              { title: 'Сумма', dataIndex: 'amount', align: 'right' as const, render: (v: number) => v.toLocaleString('ru-RU') },
              {
                title: 'Статус',
                dataIndex: 'claimed',
                render: (v: boolean) => <Tag color={v ? 'success' : 'processing'}>{v ? 'Забрано' : 'Начислено'}</Tag>,
              },
              {
                title: 'Дата выплаты',
                dataIndex: 'claimedAt',
                render: (d: string | null) => d ? dayjs(d).format('DD.MM.YYYY HH:mm') : '—',
              },
            ]}
          />
        </Card>
      )}

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
          <Text>Какой процент ликвидности удалить из позиции {removeModalPos?.tokenXSymbol}/{removeModalPos?.tokenYSymbol}?</Text>
          <Slider min={1} max={100} value={removePercent} onChange={setRemovePercent}
            marks={{ 25: '25%', 50: '50%', 75: '75%', 100: '100%' }} />
          <Text strong style={{ textAlign: 'center', display: 'block', fontSize: 24, color: '#EF4444' }}>
            {removePercent}%
          </Text>
        </Space>
      </Modal>
    </Space>
  )
}
