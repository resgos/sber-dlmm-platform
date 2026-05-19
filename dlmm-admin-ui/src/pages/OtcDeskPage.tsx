import { useState } from 'react'
import {
  Table, Space, Tag, Typography, Card, Button, Modal, Form, Input,
  InputNumber, DatePicker, Select, message, Tooltip,
} from 'antd'
import {
  PlusOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DollarOutlined, StopOutlined, FileDoneOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import dayjs, { Dayjs } from 'dayjs'
import { otc } from '@/api/services'
import type { OtcBlockTrade, OtcStatus } from '@/api/otc'

const { Title, Text } = Typography
const { TextArea } = Input

/**
 * Sprint 9 #6.1 — OTC desk admin page.
 *
 * <p>Lists block trades + action modals for the 6 state transitions
 * (create / quote / accept / reject / settle / cancel). Mirrors the
 * server-side OtcDeskService state machine — each action button is
 * shown only when the trade is in the right status.
 */

const STATUS_COLORS: Record<OtcStatus, string> = {
  REQUESTED: 'blue',
  QUOTED: 'gold',
  ACCEPTED: 'cyan',
  SETTLED: 'green',
  REJECTED: 'red',
  EXPIRED: 'orange',
  CANCELLED: 'default',
}

const STATUS_LABELS: Record<OtcStatus, string> = {
  REQUESTED: 'Запрошена',
  QUOTED: 'Котировка',
  ACCEPTED: 'Принята',
  SETTLED: 'Расчёт выполнен',
  REJECTED: 'Отклонена',
  EXPIRED: 'Просрочена',
  CANCELLED: 'Отменена',
}

export default function OtcDeskPage() {
  const queryClient = useQueryClient()
  const [statusFilter, setStatusFilter] = useState<OtcStatus | undefined>(undefined)
  const [createOpen, setCreateOpen] = useState(false)
  const [quoteFor, setQuoteFor] = useState<OtcBlockTrade | null>(null)
  const [settleFor, setSettleFor] = useState<OtcBlockTrade | null>(null)
  const [reasonFor, setReasonFor] = useState<{ trade: OtcBlockTrade; action: 'reject' | 'cancel' } | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['otc', statusFilter],
    queryFn: () => otc.list({ status: statusFilter, size: 100 }),
  })

  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['otc'] })

  // ── mutations ──

  const createMut = useMutation({
    mutationFn: otc.create,
    onSuccess: () => { void message.success('Сделка создана'); setCreateOpen(false); invalidate() },
    onError: (e: unknown) => { void message.error(extractError(e)) },
  })

  const quoteMut = useMutation({
    mutationFn: ({ id, amountOut, quotedPriceMicro, quoteExpiresAt }:
      { id: string; amountOut: number; quotedPriceMicro: number; quoteExpiresAt: string }) =>
      otc.quote(id, { amountOut, quotedPriceMicro, quoteExpiresAt }),
    onSuccess: () => { void message.success('Котировка отправлена'); setQuoteFor(null); invalidate() },
    onError: (e: unknown) => { void message.error(extractError(e)) },
  })

  const acceptMut = useMutation({
    mutationFn: (id: string) => otc.accept(id),
    onSuccess: () => { void message.success('Сделка принята контрагентом'); invalidate() },
    onError: (e: unknown) => { void message.error(extractError(e)) },
  })

  const rejectMut = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => otc.reject(id, reason),
    onSuccess: () => { void message.success('Сделка отклонена'); setReasonFor(null); invalidate() },
    onError: (e: unknown) => { void message.error(extractError(e)) },
  })

  const cancelMut = useMutation({
    mutationFn: ({ id, reason }: { id: string; reason?: string }) => otc.cancel(id, reason),
    onSuccess: () => { void message.success('Сделка отменена'); setReasonFor(null); invalidate() },
    onError: (e: unknown) => { void message.error(extractError(e)) },
  })

  const settleMut = useMutation({
    mutationFn: ({ id, settlementTxId }: { id: string; settlementTxId: string }) =>
      otc.settle(id, { settlementTxId }),
    onSuccess: () => { void message.success('Расчёт выполнен'); setSettleFor(null); invalidate() },
    onError: (e: unknown) => { void message.error(extractError(e)) },
  })

  // ── columns ──

  const columns: ColumnsType<OtcBlockTrade> = [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      width: 110,
      render: (id: string) => <Text code style={{ fontSize: 11 }}>{id.slice(0, 8)}…</Text>,
    },
    {
      title: 'Инициатор → Контрагент',
      key: 'parties',
      render: (_, r) => (
        <Space direction="vertical" size={0}>
          <Text code style={{ fontSize: 11 }}>{r.initiatorUserId.slice(0, 8)}…</Text>
          <Text code style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
            → {r.counterpartyUserId.slice(0, 8)}…
          </Text>
        </Space>
      ),
    },
    {
      title: 'Tokens',
      key: 'tokens',
      render: (_, r) => (
        <Text>
          <Text code style={{ fontSize: 11 }}>{r.tokenInId.slice(0, 8)}…</Text>
          {' → '}
          <Text code style={{ fontSize: 11 }}>{r.tokenOutId.slice(0, 8)}…</Text>
        </Text>
      ),
    },
    {
      title: 'In',
      dataIndex: 'amountIn',
      align: 'right',
      render: (v: number) => v.toLocaleString('ru-RU'),
    },
    {
      title: 'Out',
      dataIndex: 'amountOut',
      align: 'right',
      render: (v: number | null) => v ? v.toLocaleString('ru-RU') : <Text type="secondary">—</Text>,
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      key: 'status',
      render: (status: OtcStatus) => (
        <Tag color={STATUS_COLORS[status]}>{STATUS_LABELS[status]}</Tag>
      ),
    },
    {
      title: 'Создана',
      dataIndex: 'createdAt',
      render: (d: string) => dayjs(d).format('DD.MM HH:mm'),
    },
    {
      title: 'Действия',
      key: 'actions',
      render: (_, r) => (
        <Space size={4} wrap>
          {r.status === 'REQUESTED' && (
            <>
              <Tooltip title="Привязать котировку">
                <Button size="small" icon={<DollarOutlined />} onClick={() => setQuoteFor(r)}>
                  Котировать
                </Button>
              </Tooltip>
              <Tooltip title="Отменить (REQUESTED → CANCELLED)">
                <Button
                  size="small"
                  icon={<StopOutlined />}
                  onClick={() => setReasonFor({ trade: r, action: 'cancel' })}
                />
              </Tooltip>
            </>
          )}
          {r.status === 'QUOTED' && (
            <>
              <Tooltip title="Принять (QUOTED → ACCEPTED)">
                <Button
                  size="small"
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  loading={acceptMut.isPending}
                  onClick={() => acceptMut.mutate(r.id)}
                >
                  Принять
                </Button>
              </Tooltip>
              <Tooltip title="Отклонить (QUOTED → REJECTED)">
                <Button
                  size="small"
                  danger
                  icon={<CloseCircleOutlined />}
                  onClick={() => setReasonFor({ trade: r, action: 'reject' })}
                />
              </Tooltip>
              <Tooltip title="Отменить">
                <Button
                  size="small"
                  icon={<StopOutlined />}
                  onClick={() => setReasonFor({ trade: r, action: 'cancel' })}
                />
              </Tooltip>
            </>
          )}
          {r.status === 'ACCEPTED' && (
            <Tooltip title="Зафиксировать расчёт (ACCEPTED → SETTLED)">
              <Button
                size="small"
                type="primary"
                icon={<FileDoneOutlined />}
                onClick={() => setSettleFor(r)}
              >
                Расчёт
              </Button>
            </Tooltip>
          )}
          {(r.status === 'SETTLED' || r.status === 'REJECTED'
            || r.status === 'EXPIRED' || r.status === 'CANCELLED') && (
            <Text type="secondary" style={{ fontSize: 11 }}>Терминальный</Text>
          )}
        </Space>
      ),
    },
  ]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <Title level={4} className="sber-page-title">OTC desk</Title>
          <Text type="secondary" style={{ fontSize: 13 }}>
            Институциональные блок-сделки вне общего AMM-роутинга. State machine:
            REQUESTED → QUOTED → ACCEPTED → SETTLED.
          </Text>
        </div>
        <Space>
          <Select<OtcStatus | 'ALL'>
            size="middle"
            style={{ minWidth: 180 }}
            value={statusFilter ?? 'ALL'}
            onChange={(v) => setStatusFilter(v === 'ALL' ? undefined : v)}
            aria-label="Фильтр по статусу"
            options={[
              { value: 'ALL', label: 'Все статусы' },
              ...(Object.keys(STATUS_LABELS) as OtcStatus[]).map((s) => ({
                value: s, label: STATUS_LABELS[s],
              })),
            ]}
          />
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setCreateOpen(true)}
          >
            Новая сделка
          </Button>
        </Space>
      </div>

      <Card
        className="sber-card sber-table"
        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
      >
        <Table<OtcBlockTrade>
          columns={columns}
          dataSource={data?.content}
          rowKey="id"
          loading={isLoading}
          pagination={{ pageSize: 20, showTotal: (n) => `Всего ${n}` }}
          size="middle"
          scroll={{ x: 1100 }}
        />
      </Card>

      <CreateModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onSubmit={(v) => createMut.mutate(v)}
        submitting={createMut.isPending}
      />

      <QuoteModal
        trade={quoteFor}
        onClose={() => setQuoteFor(null)}
        onSubmit={(v) => quoteFor && quoteMut.mutate({ id: quoteFor.id, ...v })}
        submitting={quoteMut.isPending}
      />

      <SettleModal
        trade={settleFor}
        onClose={() => setSettleFor(null)}
        onSubmit={(v) => settleFor && settleMut.mutate({ id: settleFor.id, ...v })}
        submitting={settleMut.isPending}
      />

      <ReasonModal
        rec={reasonFor}
        onClose={() => setReasonFor(null)}
        onSubmit={(reason) => {
          if (!reasonFor) return
          const fn = reasonFor.action === 'reject' ? rejectMut : cancelMut
          fn.mutate({ id: reasonFor.trade.id, reason })
        }}
        submitting={rejectMut.isPending || cancelMut.isPending}
      />
    </Space>
  )
}

// ── modals ──

function CreateModal({ open, onClose, onSubmit, submitting }: {
  open: boolean
  onClose: () => void
  onSubmit: (v: { initiatorUserId: string; counterpartyUserId: string; tokenInId: string; tokenOutId: string; amountIn: number; notes?: string }) => void
  submitting: boolean
}) {
  const [form] = Form.useForm()
  return (
    <Modal
      title="Новая OTC-сделка"
      open={open}
      onCancel={onClose}
      onOk={() => form.validateFields().then((v) => onSubmit(v as Parameters<typeof onSubmit>[0]))}
      okText="Создать"
      cancelText="Отмена"
      confirmLoading={submitting}
      destroyOnClose
      width={520}
    >
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item name="initiatorUserId" label="Инициатор (UUID)"
          rules={[{ required: true, message: 'Введите UUID' }, { len: 36 }]}>
          <Input placeholder="00000000-0000-0000-0000-000000000001" />
        </Form.Item>
        <Form.Item name="counterpartyUserId" label="Контрагент (UUID)"
          rules={[{ required: true, message: 'Введите UUID' }, { len: 36 }]}>
          <Input placeholder="00000000-0000-0000-0000-000000000002" />
        </Form.Item>
        <Form.Item name="tokenInId" label="Token IN (UUID)"
          rules={[{ required: true }, { len: 36 }]}>
          <Input placeholder="b0000000-0000-0000-0000-000000000001" />
        </Form.Item>
        <Form.Item name="tokenOutId" label="Token OUT (UUID)"
          rules={[{ required: true }, { len: 36 }]}>
          <Input placeholder="b0000000-0000-0000-0000-000000000002" />
        </Form.Item>
        <Form.Item name="amountIn" label="Amount IN (smallest unit)"
          rules={[{ required: true, type: 'number', min: 1 }]}>
          <InputNumber style={{ width: '100%' }} placeholder="10000000" />
        </Form.Item>
        <Form.Item name="notes" label="Заметки">
          <TextArea rows={2} maxLength={1000} placeholder="Например: Sber Treasury → MOEX clearing, ref Q3-001" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function QuoteModal({ trade, onClose, onSubmit, submitting }: {
  trade: OtcBlockTrade | null
  onClose: () => void
  onSubmit: (v: { amountOut: number; quotedPriceMicro: number; quoteExpiresAt: string }) => void
  submitting: boolean
}) {
  const [form] = Form.useForm()
  return (
    <Modal
      title={trade && `Котировка для сделки ${trade.id.slice(0, 8)}…`}
      open={!!trade}
      onCancel={onClose}
      onOk={() => form.validateFields().then((v) => {
        const expiresAt: Dayjs = v.quoteExpiresAt as Dayjs
        onSubmit({
          amountOut: v.amountOut as number,
          quotedPriceMicro: v.quotedPriceMicro as number,
          quoteExpiresAt: expiresAt.toISOString(),
        })
      })}
      okText="Отправить котировку"
      cancelText="Отмена"
      confirmLoading={submitting}
      destroyOnClose
    >
      {trade && (
        <Form form={form} layout="vertical" requiredMark="optional"
          initialValues={{ quoteExpiresAt: dayjs().add(30, 'minute') }}>
          <Form.Item label="Amount IN (контекст)">
            <Input disabled value={trade.amountIn.toLocaleString('ru-RU')} />
          </Form.Item>
          <Form.Item name="amountOut" label="Amount OUT"
            rules={[{ required: true, type: 'number', min: 1 }]}>
            <InputNumber style={{ width: '100%' }} placeholder="95000" />
          </Form.Item>
          <Form.Item name="quotedPriceMicro" label="Цена (микро)"
            rules={[{ required: true, type: 'number', min: 1 }]}
            tooltip="Token_out per 1 token_in × 10^6">
            <InputNumber style={{ width: '100%' }} placeholder="9500000" />
          </Form.Item>
          <Form.Item name="quoteExpiresAt" label="Срок котировки"
            rules={[{ required: true }]}>
            <DatePicker showTime style={{ width: '100%' }} format="DD.MM.YYYY HH:mm" />
          </Form.Item>
        </Form>
      )}
    </Modal>
  )
}

function SettleModal({ trade, onClose, onSubmit, submitting }: {
  trade: OtcBlockTrade | null
  onClose: () => void
  onSubmit: (v: { settlementTxId: string }) => void
  submitting: boolean
}) {
  const [form] = Form.useForm()
  return (
    <Modal
      title={trade && `Расчёт ${trade.id.slice(0, 8)}…`}
      open={!!trade}
      onCancel={onClose}
      onOk={() => form.validateFields().then((v) => onSubmit({ settlementTxId: v.settlementTxId as string }))}
      okText="Зафиксировать расчёт"
      cancelText="Отмена"
      confirmLoading={submitting}
      destroyOnClose
    >
      <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        Привяжите UUID транзакции, исполнившей этот OTC-расчёт
        (запись в таблице <Text code>transactions</Text>).
      </Text>
      <Form form={form} layout="vertical">
        <Form.Item name="settlementTxId" label="Settlement transaction UUID"
          rules={[{ required: true, message: 'Введите UUID транзакции' }, { len: 36 }]}>
          <Input placeholder="00000000-0000-0000-0000-000000000000" />
        </Form.Item>
      </Form>
    </Modal>
  )
}

function ReasonModal({ rec, onClose, onSubmit, submitting }: {
  rec: { trade: OtcBlockTrade; action: 'reject' | 'cancel' } | null
  onClose: () => void
  onSubmit: (reason: string | undefined) => void
  submitting: boolean
}) {
  const [reason, setReason] = useState('')
  return (
    <Modal
      title={rec && (rec.action === 'reject'
        ? `Отклонить сделку ${rec.trade.id.slice(0, 8)}…`
        : `Отменить сделку ${rec.trade.id.slice(0, 8)}…`)}
      open={!!rec}
      onCancel={() => { onClose(); setReason('') }}
      onOk={() => { onSubmit(reason.trim() || undefined); setReason('') }}
      okText={rec?.action === 'reject' ? 'Отклонить' : 'Отменить'}
      okButtonProps={{ danger: true }}
      cancelText="Назад"
      confirmLoading={submitting}
      destroyOnClose
    >
      <Text type="secondary" style={{ display: 'block', marginBottom: 12 }}>
        Причина будет записана в notes сделки (для compliance-проверки).
      </Text>
      <TextArea
        rows={3}
        maxLength={500}
        value={reason}
        onChange={(e) => setReason(e.target.value)}
        placeholder="Например: контрагент сменил решение"
        aria-label="Причина"
      />
    </Modal>
  )
}

function extractError(e: unknown): string {
  const axiosErr = e as { response?: { data?: { message?: string } }; message?: string }
  return axiosErr.response?.data?.message ?? axiosErr.message ?? 'Ошибка операции'
}
