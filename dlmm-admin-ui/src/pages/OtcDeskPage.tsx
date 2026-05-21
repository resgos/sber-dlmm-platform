import { useMemo, useState } from 'react'
import {
  Table, Space, Tag, Typography, Card, Button, Modal, Form, Input,
  InputNumber, DatePicker, Select, message, Row, Col, Empty,
} from 'antd'
import {
  PlusOutlined, CheckCircleOutlined, CloseCircleOutlined,
  DollarOutlined, StopOutlined, FileDoneOutlined,
  ApiOutlined, ClockCircleOutlined, FundOutlined, LineChartOutlined,
  SwapOutlined, RiseOutlined,
} from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import type { ColumnsType } from 'antd/es/table'
import dayjs, { Dayjs } from 'dayjs'
import { otc, tokens as tokensApi, users as usersApi } from '@/api/services'
import type { OtcBlockTrade, OtcStatus } from '@/api/otc'
import type { Token, User } from '@/api/types'

const { Title, Text } = Typography
const { TextArea } = Input

// Sprint 9 (post-DS-handoff) — UI patterns lifted from the Claude
// Design OTC desk mockup at docs/design/otc-desk-claude-design-DSv1/.
// The mockup gave us: a session-info header strip with the gateway-
// connected pill, a 4-up KPI row computed from the live deal list,
// a sectioned RFQ-inbox table grouped by status (instead of a flat
// list with a status filter), and a selected-row detail pane on the
// right. The backend workflow + modals are unchanged — we still use
// the same /api/v1/otc/** endpoints. The mockup also showed a
// spread-vs-mid track viz and tick-down expiry countdowns — those
// require mid-market price data we don't pipe into this page yet,
// so they're scaffolded with a TODO and a compact placeholder.

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
  // Sprint 9 — selected row drives the right-pane detail card so the
  // operator can scan the inbox and read all the trade context without
  // opening a modal for every glance. Action modals still own writes.
  const [selectedId, setSelectedId] = useState<string | null>(null)

  const { data, isLoading } = useQuery({
    queryKey: ['otc', statusFilter],
    queryFn: () => otc.list({ status: statusFilter, size: 100 }),
    refetchInterval: 30000,
  })

  // Token symbols, joined client-side so we can render "BTC → USDT"
  // instead of the truncated IDs the previous flat table used.
  const { data: tokenPage } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokensApi.getTokens(0, 200),
  })
  const symbolByTokenId = useMemo(() => {
    const m = new Map<string, string>()
    for (const t of tokenPage?.content ?? []) m.set(t.id, t.symbol)
    return m
  }, [tokenPage])

  const allTrades = data?.content ?? []
  const selectedTrade = allTrades.find((t) => t.id === selectedId) ?? null

  // ── KPI strip — computed from real data. The mockup also showed a
  // gateway-status pill ("Соединение с биржевым шлюзом установлено")
  // and a session header (today's trading hours + duty operator);
  // those need a /admin/session endpoint we haven't built, so the
  // pill below is a static "live" badge derived from a successful
  // /otc list — if the query is loading or errored, the badge flips
  // to "Связь с шлюзом потеряна".
  const today = dayjs().startOf('day')
  const pendingCount = allTrades.filter((t) => t.status === 'REQUESTED').length
  const quotedTodayCount = allTrades.filter((t) =>
    t.status === 'QUOTED' && dayjs(t.quotedAt ?? t.createdAt).isAfter(today),
  ).length
  const settledTodayVolume = allTrades
    .filter((t) => t.status === 'SETTLED' && dayjs(t.settledAt ?? t.updatedAt).isAfter(today))
    .reduce((sum, t) => sum + (t.amountIn ?? 0), 0)
  const acceptedAwaitingCount = allTrades.filter((t) => t.status === 'ACCEPTED').length

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
      title: 'Пара',
      key: 'tokens',
      render: (_, r) => {
        // Sprint 9 — join via tokensApi so the operator reads
        // "BTC → USDT" instead of two 8-char ID prefixes.
        const inSym = symbolByTokenId.get(r.tokenInId)
        const outSym = symbolByTokenId.get(r.tokenOutId)
        if (inSym && outSym) {
          return (
            <Space size={6}>
              <Text strong style={{ fontSize: 13 }}>{inSym}</Text>
              <SwapOutlined style={{ color: 'var(--text-muted, #9CA3AF)', fontSize: 11 }} />
              <Text strong style={{ fontSize: 13 }}>{outSym}</Text>
            </Space>
          )
        }
        return (
          <Text style={{ fontSize: 11, color: 'var(--text-secondary)' }}>
            {r.tokenInId.slice(0, 8)}… → {r.tokenOutId.slice(0, 8)}…
          </Text>
        )
      },
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
    // Sprint 9 — "Действия" column removed. Per-row buttons cluttered
    // every line in the inbox even though only the selected deal
    // actually needs actions; the new right-pane DealDetailPane is the
    // single home for the stage-aware action buttons. Selecting a row
    // surfaces the relevant set there.
  ]

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {/* ── Top header — title + gateway-status pill + session info + actions ── */}
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-end', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
            <Title level={4} className="sber-page-title" style={{ margin: 0 }}>OTC desk</Title>
            <Tag
              icon={<ApiOutlined />}
              color={isLoading ? 'default' : 'success'}
              style={{ borderRadius: 999, padding: '2px 12px', fontWeight: 500 }}
            >
              {isLoading ? 'Связь с биржевым шлюзом…' : 'Соединение с биржевым шлюзом установлено'}
            </Tag>
          </div>
          <Text type="secondary" style={{ fontSize: 13 }}>
            Институциональные блок-сделки вне общего AMM-роутинга. Сессия:{' '}
            <Text style={{ fontSize: 13, fontFamily: 'JetBrains Mono, monospace' }}>
              {dayjs().format('DD.MM.YYYY · 09:30 — 18:30 MSK')}
            </Text>
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

      {/* ── 4-up KPI row — live counts from the same /otc list call. ── */}
      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <KpiTile
            label="Ожидают котировки"
            value={pendingCount}
            sub={pendingCount > 0 ? `${pendingCount === 1 ? 'требует' : 'требуют'} ответа` : 'инбокс пуст'}
            icon={<ClockCircleOutlined style={{ color: '#D14D00' }} />}
            accent={pendingCount > 0 ? '#D14D00' : undefined}
          />
        </Col>
        <Col xs={12} lg={6}>
          <KpiTile
            label="Котировок сегодня"
            value={quotedTodayCount}
            sub={`в ожидании подтверждения ${allTrades.filter(t => t.status === 'QUOTED').length}`}
            icon={<DollarOutlined style={{ color: 'var(--sber-green)' }} />}
          />
        </Col>
        <Col xs={12} lg={6}>
          <KpiTile
            label="Объём расчётов 24ч"
            value={settledTodayVolume.toLocaleString('ru-RU', { maximumFractionDigits: 0 })}
            sub={`исполнено ${allTrades.filter(t => t.status === 'SETTLED' && dayjs(t.settledAt ?? t.updatedAt).isAfter(today)).length} ед.`}
            icon={<FundOutlined style={{ color: 'var(--sber-green)' }} />}
          />
        </Col>
        <Col xs={12} lg={6}>
          <KpiTile
            label="К расчёту"
            value={acceptedAwaitingCount}
            sub={acceptedAwaitingCount > 0 ? 'ожидают settlement TX' : 'нет открытых'}
            icon={<LineChartOutlined style={{ color: '#296AE3' }} />}
            accent={acceptedAwaitingCount > 0 ? '#296AE3' : undefined}
          />
        </Col>
      </Row>

      {/* ── Main pane: left = RFQ-inbox sectioned by status, right = selected deal detail. ── */}
      <Row gutter={[16, 16]}>
        <Col xs={24} xl={selectedTrade ? 16 : 24}>
          <Card
            className="sber-card sber-table"
            style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
            styles={{ body: { padding: 0 } }}
          >
            <SectionedOtcTable
              trades={allTrades}
              loading={isLoading}
              columns={columns}
              selectedId={selectedId}
              onSelect={setSelectedId}
            />
          </Card>
        </Col>

        {selectedTrade && (
          <Col xs={24} xl={8}>
            <DealDetailPane
              trade={selectedTrade}
              symbolByTokenId={symbolByTokenId}
              onClose={() => setSelectedId(null)}
              onQuote={() => setQuoteFor(selectedTrade)}
              onAccept={() => acceptMut.mutate(selectedTrade.id)}
              onReject={() => setReasonFor({ trade: selectedTrade, action: 'reject' })}
              onSettle={() => setSettleFor(selectedTrade)}
              onCancel={() => setReasonFor({ trade: selectedTrade, action: 'cancel' })}
            />
          </Col>
        )}
      </Row>

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

  // Sprint 9-DS-r4 (P2-2) — pull users + tokens once for the
  // Select pickers. Both queries are catalog-scoped (small page size),
  // cached so the modal opens instantly on second open.
  const { data: userPage } = useQuery({
    queryKey: ['admin-users', 0, 200],
    queryFn: () => usersApi.getUsers(0, 200),
    enabled: open,
    staleTime: 60_000,
  })
  const { data: tokenPageForPicker } = useQuery({
    queryKey: ['admin-tokens-for-otc', 0, 200],
    queryFn: () => tokensApi.getTokens(0, 200),
    enabled: open,
    staleTime: 60_000,
  })

  const userOptions = useMemo(
    () => (userPage?.content ?? []).map((u: User) => ({
      value: u.id,
      label: `${u.fullName ?? u.email} (${u.email})`,
      // Sprint 9-DS-r4 (P2-2) — Select filterOption uses these search
      // hints so an operator can type the email OR the full name and
      // hit the right user without scrolling 200 rows.
      search: `${u.fullName ?? ''} ${u.email}`.toLowerCase(),
    })),
    [userPage],
  )
  const tokenOptions = useMemo(
    () => (tokenPageForPicker?.content ?? []).map((t: Token) => ({
      value: t.id,
      label: `${t.symbol} — ${t.name}`,
      search: `${t.symbol} ${t.name}`.toLowerCase(),
    })),
    [tokenPageForPicker],
  )

  // Sprint 9-DS-r4 (P2-2) — AntD Select filterOption signature: returns
  // true to keep the option visible. We match on a precomputed `search`
  // string so symbol + email + name all work as input.
  const filterByLabel = (input: string, option?: { search?: string }) =>
    !!option?.search?.includes(input.toLowerCase())

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
      {/* Sprint 9-DS-r4 (P2-2) — pasted-UUID Inputs replaced by
          symbol/email Select pickers. Operators had to copy UUIDs
          from another tab; selection by symbol/email is faster and
          eliminates the typo class of failure. */}
      <Form form={form} layout="vertical" requiredMark="optional">
        <Form.Item name="initiatorUserId" label="Инициатор"
          rules={[{ required: true, message: 'Выберите пользователя' }]}>
          <Select
            showSearch
            placeholder="Выберите по email или имени"
            options={userOptions}
            filterOption={filterByLabel}
          />
        </Form.Item>
        <Form.Item name="counterpartyUserId" label="Контрагент"
          rules={[{ required: true, message: 'Выберите контрагента' }]}>
          <Select
            showSearch
            placeholder="Выберите по email или имени"
            options={userOptions}
            filterOption={filterByLabel}
          />
        </Form.Item>
        <Form.Item name="tokenInId" label="Token IN"
          rules={[{ required: true, message: 'Выберите токен' }]}>
          <Select
            showSearch
            placeholder="Например: SRUB"
            options={tokenOptions}
            filterOption={filterByLabel}
          />
        </Form.Item>
        <Form.Item name="tokenOutId" label="Token OUT"
          rules={[{ required: true, message: 'Выберите токен' }]}>
          <Select
            showSearch
            placeholder="Например: SBTC"
            options={tokenOptions}
            filterOption={filterByLabel}
          />
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
        // Sprint 9-DS-r4 (P2-1) — default expiry bumped 30min → 24h.
        // Treasurer-side back-and-forth (review → call ops →
        // counter-quote) takes more than half an hour in practice;
        // the operator was always manually pushing the date forward.
        // 24h matches the OTC desk's typical quote-valid window.
        <Form form={form} layout="vertical" requiredMark="optional"
          initialValues={{ quoteExpiresAt: dayjs().add(24, 'hour') }}>
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

// ─────────────────────────────────────────────────────────────────
// Mockup-lifted components from docs/design/otc-desk-claude-design-DSv1/
// ─────────────────────────────────────────────────────────────────

function KpiTile({ label, value, sub, icon, accent }: {
  label: string
  value: number | string
  sub: string
  icon: React.ReactNode
  accent?: string
}) {
  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
      styles={{ body: { padding: '14px 16px' } }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12 }}>
        <div style={{
          width: 36, height: 36, borderRadius: 10,
          background: 'var(--surface-1, #FAFAFA)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          flexShrink: 0, fontSize: 16,
        }}>
          {icon}
        </div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{
            fontSize: 11, color: 'var(--text-secondary)', letterSpacing: '0.04em',
            textTransform: 'uppercase', fontWeight: 500, marginBottom: 4,
          }}>
            {label}
          </div>
          <div style={{
            fontSize: 22, fontWeight: 700, color: accent ?? 'var(--text-primary)',
            lineHeight: 1.1, fontVariantNumeric: 'tabular-nums',
          }}>
            {value}
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)', marginTop: 3 }}>
            {sub}
          </div>
        </div>
      </div>
    </Card>
  )
}

// Sections in operator-priority order. The terminal ones (SETTLED,
// REJECTED, EXPIRED, CANCELLED) collapse into a single "Архив" group
// so the live work stays visible.
const SECTION_ORDER: { status: OtcStatus; title: string; tone: string }[] = [
  { status: 'REQUESTED', title: 'Ожидают котировки',     tone: '#D14D00' },
  { status: 'QUOTED',    title: 'Котировка выставлена',  tone: 'var(--sber-green)' },
  { status: 'ACCEPTED',  title: 'Приняты к расчёту',     tone: '#296AE3' },
]

function SectionedOtcTable({ trades, loading, columns, selectedId, onSelect }: {
  trades: OtcBlockTrade[]
  loading: boolean
  columns: ColumnsType<OtcBlockTrade>
  selectedId: string | null
  onSelect: (id: string | null) => void
}) {
  if (!loading && trades.length === 0) {
    return (
      <div style={{ padding: 32 }}>
        <Empty description="Нет блок-сделок — инбокс пуст" />
      </div>
    )
  }

  const sections = SECTION_ORDER
    .map((s) => ({ ...s, items: trades.filter((t) => t.status === s.status) }))
    .filter((s) => s.items.length > 0)

  const archive = trades.filter((t) =>
    t.status === 'SETTLED' || t.status === 'REJECTED'
    || t.status === 'EXPIRED' || t.status === 'CANCELLED',
  )
  if (archive.length > 0) {
    sections.push({ status: 'SETTLED', title: 'Архив сегодня', tone: 'var(--text-secondary)', items: archive })
  }

  return (
    <div>
      {sections.map((sec, idx) => (
        <div key={sec.status + idx}>
          <div style={{
            padding: '10px 18px',
            background: 'var(--surface-1, #FAFAFA)',
            borderTop: idx === 0 ? 'none' : '1px solid var(--border-light)',
            borderBottom: '1px solid var(--border-light)',
            display: 'flex', alignItems: 'center', gap: 10,
          }}>
            <span style={{
              width: 8, height: 8, borderRadius: '50%',
              background: sec.tone, flexShrink: 0,
            }} />
            <Text strong style={{ fontSize: 12, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
              {sec.title}
            </Text>
            <Text type="secondary" style={{ fontSize: 12 }}>{sec.items.length}</Text>
          </div>
          <Table<OtcBlockTrade>
            columns={columns}
            dataSource={sec.items}
            rowKey="id"
            loading={loading && idx === 0}
            pagination={false}
            size="middle"
            showHeader={idx === 0}
            scroll={{ x: 1100 }}
            rowClassName={(r) => r.id === selectedId ? 'sber-otc-row-selected' : ''}
            onRow={(record) => ({
              onClick: () => onSelect(record.id === selectedId ? null : record.id),
              style: { cursor: 'pointer' },
            })}
          />
        </div>
      ))}
    </div>
  )
}

function DealDetailPane({ trade, symbolByTokenId, onClose,
  onQuote, onAccept, onReject, onSettle, onCancel }: {
  trade: OtcBlockTrade
  symbolByTokenId: Map<string, string>
  onClose: () => void
  onQuote: () => void
  onAccept: () => void
  onReject: () => void
  onSettle: () => void
  onCancel: () => void
}) {
  const inSym = symbolByTokenId.get(trade.tokenInId) ?? trade.tokenInId.slice(0, 6) + '…'
  const outSym = symbolByTokenId.get(trade.tokenOutId) ?? trade.tokenOutId.slice(0, 6) + '…'

  // Sprint 9 — spread-vs-mid track viz from the mockup needs a
  // mid-market reference price. The backend doesn't pipe one into
  // the trade DTO yet, so we render a placeholder strip with the
  // quoted price only. When mid-market data lands (probably via a
  // /admin/otc/{id}/spread endpoint or a price-oracle join), this
  // converts to the real ±15 bps track with mid + quote markers.
  const hasQuote = trade.amountOut != null && trade.amountIn > 0
  const impliedRate = hasQuote ? (trade.amountOut! / trade.amountIn) : null

  // Countdown to quote expiry (from quoteExpiresAt), shown only if
  // the trade is in REQUESTED/QUOTED with a future deadline.
  const expiresAt = trade.quoteExpiresAt ? dayjs(trade.quoteExpiresAt) : null
  const secondsLeft = expiresAt ? Math.max(0, expiresAt.diff(dayjs(), 'second')) : null
  const expiryUrgent = secondsLeft != null && secondsLeft < 120

  return (
    <Card
      className="sber-card"
      style={{ borderRadius: 12, border: '1px solid var(--border-light)', position: 'sticky', top: 12 }}
      styles={{ body: { padding: 0 } }}
    >
      {/* Header */}
      <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border-light)' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
          <div style={{ minWidth: 0, flex: 1 }}>
            <Text style={{ fontSize: 11, color: 'var(--text-muted)', fontFamily: 'JetBrains Mono, monospace' }}>
              {trade.id}
            </Text>
            <div style={{ marginTop: 4 }}>
              <Tag color={STATUS_COLORS[trade.status]} style={{ borderRadius: 999, marginInlineEnd: 8 }}>
                {STATUS_LABELS[trade.status]}
              </Tag>
              <Space size={6}>
                <Text strong style={{ fontSize: 15 }}>{inSym}</Text>
                <SwapOutlined style={{ color: 'var(--text-muted)', fontSize: 12 }} />
                <Text strong style={{ fontSize: 15 }}>{outSym}</Text>
              </Space>
            </div>
          </div>
          <Button type="text" size="small" onClick={onClose} aria-label="Закрыть">×</Button>
        </div>
      </div>

      {/* Parties */}
      <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border-light)' }}>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
          Контрагенты
        </Text>
        <Row gutter={12} style={{ marginTop: 6 }}>
          <Col span={11}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Инициатор</div>
            <Text style={{ fontSize: 12, fontFamily: 'JetBrains Mono, monospace' }}>
              …{trade.initiatorUserId.slice(-12)}
            </Text>
          </Col>
          <Col span={2} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>→</Col>
          <Col span={11}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Контрагент</div>
            <Text style={{ fontSize: 12, fontFamily: 'JetBrains Mono, monospace' }}>
              …{trade.counterpartyUserId.slice(-12)}
            </Text>
          </Col>
        </Row>
      </div>

      {/* Amounts + implied rate */}
      <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border-light)' }}>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
          Котировка
        </Text>
        <Row gutter={12} style={{ marginTop: 6 }}>
          <Col span={12}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Запрос</div>
            <div style={{ fontSize: 18, fontWeight: 600, fontVariantNumeric: 'tabular-nums' }}>
              {trade.amountIn.toLocaleString('ru-RU')}
              <Text type="secondary" style={{ fontSize: 12, marginLeft: 4 }}>{inSym}</Text>
            </div>
          </Col>
          <Col span={12}>
            <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>Наша котировка</div>
            <div style={{
              fontSize: 18, fontWeight: 600, fontVariantNumeric: 'tabular-nums',
              color: hasQuote ? 'var(--sber-green)' : 'var(--text-muted)',
            }}>
              {hasQuote ? trade.amountOut!.toLocaleString('ru-RU') : '—'}
              {hasQuote && <Text type="secondary" style={{ fontSize: 12, marginLeft: 4 }}>{outSym}</Text>}
            </div>
          </Col>
        </Row>
        {impliedRate != null && (
          <div style={{ marginTop: 8, fontSize: 12, color: 'var(--text-secondary)' }}>
            <RiseOutlined style={{ marginRight: 4 }} />
            Эфф. курс 1 {inSym} ={' '}
            <Text strong style={{ fontVariantNumeric: 'tabular-nums' }}>
              {impliedRate.toLocaleString('ru-RU', { maximumFractionDigits: 6 })}
            </Text>
            {' '}{outSym}
          </div>
        )}
        {/* TODO Sprint 10 — render spread-vs-mid track when the backend
            exposes a mid-market reference. Currently placeholder. */}
        <div style={{
          marginTop: 10, padding: '8px 10px', borderRadius: 8,
          background: 'var(--surface-1, #FAFAFA)',
          fontSize: 11, color: 'var(--text-muted)',
        }}>
          Спред vs mid-market — нужны данные оракула; интегрируется в Sprint 10.
        </div>
      </div>

      {/* Expiry countdown */}
      {secondsLeft != null && (trade.status === 'REQUESTED' || trade.status === 'QUOTED') && (
        <div style={{
          padding: '12px 18px', borderBottom: '1px solid var(--border-light)',
          display: 'flex', justifyContent: 'space-between', alignItems: 'center',
        }}>
          <div>
            <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
              До истечения
            </Text>
            <div style={{
              fontSize: 18, fontWeight: 700, fontVariantNumeric: 'tabular-nums',
              color: expiryUrgent ? '#DC2626' : 'var(--text-primary)',
              fontFamily: 'JetBrains Mono, monospace',
            }}>
              {Math.floor(secondsLeft / 60).toString().padStart(2, '0')}:{(secondsLeft % 60).toString().padStart(2, '0')}
            </div>
          </div>
          {expiryUrgent && (
            <Tag color="error" style={{ borderRadius: 999 }}>срочно</Tag>
          )}
        </div>
      )}

      {/* Audit trail */}
      <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--border-light)' }}>
        <Text type="secondary" style={{ fontSize: 11, letterSpacing: '0.04em', textTransform: 'uppercase' }}>
          История
        </Text>
        <div style={{ marginTop: 8 }}>
          <AuditRow label="Запрос получен" ts={trade.createdAt} done />
          {trade.quotedAt && <AuditRow label="Котировка выставлена" ts={trade.quotedAt} done />}
          {(trade.status === 'ACCEPTED' || trade.status === 'SETTLED') && <AuditRow label="Принята контрагентом" ts={trade.updatedAt} done />}
          {trade.settledAt && <AuditRow label="Расчёт исполнен" ts={trade.settledAt} done />}
          {trade.status === 'REJECTED' && <AuditRow label="Отклонена" ts={trade.updatedAt} error />}
          {trade.status === 'CANCELLED' && <AuditRow label="Отменена" ts={trade.updatedAt} error />}
          {trade.notes && (
            <div style={{
              marginTop: 8, padding: '8px 10px', borderRadius: 8,
              background: 'var(--surface-1, #FAFAFA)', fontSize: 12,
            }}>
              <Text type="secondary" style={{ fontSize: 11 }}>Заметка:</Text>{' '}
              <Text>{trade.notes}</Text>
            </div>
          )}
        </div>
      </div>

      {/* Stage-aware actions */}
      <div style={{ padding: '14px 18px' }}>
        <Space wrap>
          {trade.status === 'REQUESTED' && (
            <>
              <Button type="primary" icon={<DollarOutlined />} onClick={onQuote}>
                Выставить котировку
              </Button>
              <Button icon={<StopOutlined />} onClick={onCancel}>Отменить</Button>
            </>
          )}
          {trade.status === 'QUOTED' && (
            <>
              <Button type="primary" icon={<CheckCircleOutlined />} onClick={onAccept}>
                Принять
              </Button>
              <Button danger icon={<CloseCircleOutlined />} onClick={onReject}>Отклонить</Button>
              <Button icon={<StopOutlined />} onClick={onCancel}>Отменить</Button>
            </>
          )}
          {trade.status === 'ACCEPTED' && (
            <Button type="primary" icon={<FileDoneOutlined />} onClick={onSettle}>
              Зафиксировать расчёт
            </Button>
          )}
          {(trade.status === 'SETTLED' || trade.status === 'REJECTED'
            || trade.status === 'EXPIRED' || trade.status === 'CANCELLED') && (
            <Text type="secondary" style={{ fontSize: 12 }}>Сделка в терминальном статусе</Text>
          )}
        </Space>
      </div>
    </Card>
  )
}

function AuditRow({ label, ts, done, error }: {
  label: string; ts: string; done?: boolean; error?: boolean
}) {
  const color = error ? '#DC2626' : done ? 'var(--sber-green)' : 'var(--text-muted)'
  return (
    <div style={{
      display: 'flex', alignItems: 'center', gap: 10,
      padding: '4px 0', fontSize: 12,
    }}>
      <span style={{
        width: 8, height: 8, borderRadius: '50%',
        background: color, flexShrink: 0,
      }} />
      <span style={{ flex: 1 }}>{label}</span>
      <Text type="secondary" style={{ fontSize: 11, fontFamily: 'JetBrains Mono, monospace' }}>
        {dayjs(ts).format('HH:mm:ss')}
      </Text>
    </div>
  )
}
