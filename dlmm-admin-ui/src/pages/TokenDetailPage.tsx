import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Tag,
  Button,
  Space,
  Typography,
  Spin,
  Alert,
  Modal,
  Form,
  InputNumber,
  Input,
  message,
  Row,
  Col,
  Tooltip,
  Tabs,
} from 'antd'
import {
  ArrowLeftOutlined,
  PlusCircleOutlined,
  MinusCircleOutlined,
  CopyOutlined,
  GoldOutlined,
  StockOutlined,
  PieChartOutlined,
  DollarOutlined,
} from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { tokens as tokenService } from '@/api/services'
import type { Token, TokenType, MintBurnRequest } from '@/api/types'
import { KpiRow } from '@/components/sber'
import { formatCompact, formatTokenAmount } from '@/lib/format'

const { Text, Title } = Typography

/**
 * Sprint 9-DS — admin TokenDetailPage refactor.
 *
 * <p>Replaces the flat Descriptions table with:
 *  - hero card with token glyph (deterministic colour by symbol),
 *    name + symbol + type pill + status, copy-ID action
 *  - 4-up KpiRow: type / decimals / total emission / circulating
 *  - tabbed body: Профиль | Действия администратора | Поставка (graphical)
 *  - mint/burn modal unchanged in mechanics, just cosmetically aligned
 */

const tokenTypeColor: Record<TokenType, string> = {
  STABLE_TOKEN: 'blue',
  EQUITY_TOKEN: 'gold',
  LP_TOKEN: 'cyan',
  GOVERNANCE_TOKEN: 'purple',
  FIAT_BACKED: 'green',
  COMMODITY_BACKED: 'orange',
  UTILITY: 'geekblue',
  INDEX_TOKEN: 'magenta',
}

const tokenTypeLabel: Record<TokenType, string> = {
  STABLE_TOKEN: 'Стейблкоин',
  EQUITY_TOKEN: 'Акция',
  LP_TOKEN: 'LP-токен',
  GOVERNANCE_TOKEN: 'Управление',
  FIAT_BACKED: 'Валюта',
  COMMODITY_BACKED: 'Сырьё',
  UTILITY: 'Утилитарный',
  INDEX_TOKEN: 'Индекс',
}

function tokenAccent(symbol: string): string {
  // Same hash-to-palette as TokenPairChip, but returns a single accent.
  const palette = [
    '#21A038',
    '#F2994A',
    '#2E6BFF',
    '#9B59B6',
    '#16A085',
    '#E74C3C',
  ]
  let h = 0
  for (let i = 0; i < symbol.length; i++) h = (h * 31 + symbol.charCodeAt(i)) | 0
  return palette[Math.abs(h) % palette.length]
}

function iconForType(type: TokenType): React.ReactNode {
  switch (type) {
    case 'EQUITY_TOKEN':
      return <StockOutlined />
    case 'COMMODITY_BACKED':
    case 'INDEX_TOKEN':
      return <GoldOutlined />
    case 'FIAT_BACKED':
      return <DollarOutlined />
    default:
      return <PieChartOutlined />
  }
}

type ModalMode = 'mint' | 'burn' | null

export default function TokenDetailPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [messageApi, contextHolder] = message.useMessage()
  const [modalMode, setModalMode] = useState<ModalMode>(null)
  const [modalForm] = Form.useForm<MintBurnRequest>()

  const { data: token, isLoading, error } = useQuery({
    queryKey: ['token', id],
    queryFn: () => tokenService.getToken(id!),
    enabled: !!id,
  })

  const mintMutation = useMutation({
    mutationFn: (data: MintBurnRequest) => tokenService.mint(id!, data),
    onSuccess: () => {
      messageApi.success('Токены успешно выпущены')
      queryClient.invalidateQueries({ queryKey: ['token', id] })
      setModalMode(null)
      modalForm.resetFields()
    },
    onError: () => messageApi.error('Не удалось выпустить токены'),
  })

  const burnMutation = useMutation({
    mutationFn: (data: MintBurnRequest) => tokenService.burn(id!, data),
    onSuccess: () => {
      messageApi.success('Токены успешно сожжены')
      queryClient.invalidateQueries({ queryKey: ['token', id] })
      setModalMode(null)
      modalForm.resetFields()
    },
    onError: () => messageApi.error('Не удалось сжечь токены'),
  })

  const handleModalSubmit = (values: MintBurnRequest) => {
    if (modalMode === 'mint') mintMutation.mutate(values)
    else if (modalMode === 'burn') burnMutation.mutate(values)
  }

  const handleModalClose = () => {
    setModalMode(null)
    modalForm.resetFields()
  }

  const copyId = () => {
    if (!token?.id) return
    navigator.clipboard.writeText(token.id).then(
      () => messageApi.success('ID скопирован в буфер обмена'),
      () => messageApi.error('Не удалось скопировать'),
    )
  }

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '80px' }}>
        <Spin size="large" />
      </div>
    )
  }

  if (error || !token) {
    return (
      <Alert
        message="Не удалось загрузить токен"
        type="error"
        showIcon
        style={{ borderRadius: 8 }}
        action={<Button onClick={() => navigate('/tokens')}>К токенам</Button>}
      />
    )
  }

  const isMutating = mintMutation.isPending || burnMutation.isPending
  const accent = tokenAccent(token.symbol)
  const safeTotal = token.totalSupply ?? 0
  const safeCirc = token.circulatingSupply ?? 0
  const supplyPct = token.maxSupply && token.maxSupply > 0
    ? Math.min(100, (safeTotal / token.maxSupply) * 100)
    : null
  // Guard against (a) totalSupply=0 → divide-by-zero NaN, and (b)
  // circulatingSupply missing from the API response — both surfaced
  // as "NaN%" before the safe defaults were added.
  const circulatingPct = safeTotal > 0
    ? (safeCirc / safeTotal) * 100
    : 0

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/tokens')}
          style={{ padding: 0, color: 'var(--text-secondary)' }}
        >
          К каталогу токенов
        </Button>

        {/* Hero */}
        <Card
          className="sber-card"
          style={{ borderRadius: 16, border: '1px solid var(--border-light)', overflow: 'hidden' }}
          styles={{ body: { padding: 0 } }}
        >
          <div
            style={{
              padding: '24px 28px',
              background: `linear-gradient(135deg, ${accent}14 0%, ${accent}05 100%)`,
              borderBottom: '1px solid var(--border-light)',
            }}
          >
            <Row align="middle" gutter={[24, 16]}>
              <Col flex="none">
                <div
                  aria-hidden
                  style={{
                    width: 72, height: 72, borderRadius: 16,
                    background: accent, color: '#fff',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: 28, fontWeight: 700,
                    boxShadow: `0 4px 18px ${accent}40`,
                  }}
                >
                  {iconForType(token.tokenType as TokenType)}
                </div>
              </Col>
              <Col flex="auto" style={{ minWidth: 0 }}>
                <Space size={10} wrap align="baseline">
                  <Title level={4} className="sber-page-title" style={{ margin: 0 }}>
                    {token.symbol}
                  </Title>
                  <Text type="secondary" style={{ fontSize: 14 }}>{token.name}</Text>
                  <Tag color={tokenTypeColor[token.tokenType as TokenType]} style={{ marginInlineEnd: 0 }}>
                    {tokenTypeLabel[token.tokenType as TokenType] ?? token.tokenType}
                  </Tag>
                  <Tag color={token.active ? 'green' : 'red'} style={{ marginInlineEnd: 0 }}>
                    {token.active ? 'Активен' : 'Неактивен'}
                  </Tag>
                </Space>
                <div style={{ marginTop: 6 }}>
                  <Space size={6}>
                    <Tooltip title={token.id}>
                      <Text
                        style={{
                          fontSize: 12,
                          fontFamily: 'JetBrains Mono, monospace',
                          color: 'var(--text-secondary)',
                        }}
                      >
                        ID: …{token.id.slice(-16)}
                      </Text>
                    </Tooltip>
                    <Button
                      type="text"
                      size="small"
                      icon={<CopyOutlined />}
                      onClick={copyId}
                      aria-label="Скопировать ID"
                      style={{ padding: '0 4px', color: 'var(--text-muted)' }}
                    />
                  </Space>
                </div>
              </Col>
              <Col flex="none">
                <Space>
                  <Button
                    type="primary"
                    icon={<PlusCircleOutlined />}
                    onClick={() => setModalMode('mint')}
                    style={{ borderRadius: 8 }}
                  >
                    Выпустить
                  </Button>
                  <Button
                    danger
                    icon={<MinusCircleOutlined />}
                    onClick={() => setModalMode('burn')}
                    style={{ borderRadius: 8 }}
                  >
                    Сжечь
                  </Button>
                </Space>
              </Col>
            </Row>
          </div>
        </Card>

        <KpiRow
          tiles={[
            {
              label: 'Тип',
              value: tokenTypeLabel[token.tokenType as TokenType] ?? token.tokenType,
              sub: token.tokenType.toLowerCase().replace(/_/g, ' '),
              icon: iconForType(token.tokenType as TokenType),
            },
            {
              label: 'Десятичные',
              value: token.decimals,
              sub: `${Math.pow(10, token.decimals).toLocaleString('ru-RU')} ед. = 1 ${token.symbol}`,
              icon: <PieChartOutlined style={{ color: '#296AE3' }} />,
            },
            {
              label: 'Общая эмиссия',
              value: formatCompact(token.totalSupply ?? 0),
              sub: supplyPct !== null ? `${supplyPct.toFixed(1)}% от максимума` : 'без верхней границы',
              icon: <GoldOutlined style={{ color: '#F2994A' }} />,
              accent,
            },
            {
              label: 'В обращении',
              value: formatCompact(token.circulatingSupply ?? 0),
              sub: `${circulatingPct.toFixed(1)}% от эмиссии`,
              icon: <DollarOutlined style={{ color: 'var(--sber-green)' }} />,
            },
          ]}
        />

        <Card
          className="sber-card"
          style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
          styles={{ body: { padding: '8px 16px 16px' } }}
        >
          <Tabs
            defaultActiveKey="profile"
            items={[
              {
                key: 'profile',
                label: 'Профиль',
                children: (
                  <Row gutter={[16, 16]}>
                    <Col xs={24} md={12}>
                      <ProfileRow label="Символ" value={token.symbol} />
                      <ProfileRow label="Название" value={token.name} />
                      <ProfileRow
                        label="Тип"
                        value={tokenTypeLabel[token.tokenType as TokenType] ?? token.tokenType}
                      />
                    </Col>
                    <Col xs={24} md={12}>
                      <ProfileRow label="Десятичные" value={String(token.decimals)} mono />
                      <ProfileRow label="ID токена" value={token.id} mono small />
                      <ProfileRow
                        label="Статус"
                        value={token.active ? 'Активен' : 'Неактивен'}
                      />
                    </Col>
                  </Row>
                ),
              },
              {
                key: 'supply',
                label: 'Поставка',
                children: (
                  <Row gutter={[16, 16]}>
                    <Col xs={24} md={12}>
                      <SupplyBar
                        label="В обращении"
                        sub="circulatingSupply"
                        value={token.circulatingSupply ?? 0}
                        total={token.totalSupply ?? 0}
                        symbol={token.symbol}
                        colour="var(--sber-green)"
                      />
                      <SupplyBar
                        label="Эмитировано"
                        sub={token.maxSupply ? 'totalSupply / maxSupply' : 'без верхней границы'}
                        value={token.totalSupply ?? 0}
                        total={token.maxSupply ?? token.totalSupply ?? 0}
                        symbol={token.symbol}
                        colour={accent}
                      />
                    </Col>
                    <Col xs={24} md={12}>
                      <Card
                        size="small"
                        style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
                      >
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          Максимальная эмиссия
                        </Text>
                        <div style={{
                          fontSize: 22, fontWeight: 700, color: 'var(--text-primary)',
                          fontVariantNumeric: 'tabular-nums',
                        }}>
                          {token.maxSupply && token.maxSupply > 0
                            ? formatTokenAmount(token.maxSupply, token.symbol, { compact: true })
                            : '∞'}
                        </div>
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          {token.maxSupply
                            ? 'верхний предел контракта'
                            : 'мintable без ограничения — Treasury выпускает по запросу'}
                        </Text>
                      </Card>
                    </Col>
                  </Row>
                ),
              },
            ]}
          />
        </Card>
      </Space>

      <Modal
        title={modalMode === 'mint' ? `Выпуск ${token.symbol}` : `Сжигание ${token.symbol}`}
        open={modalMode !== null}
        onCancel={handleModalClose}
        footer={null}
        destroyOnClose
      >
        <Form form={modalForm} layout="vertical" onFinish={handleModalSubmit} size="large">
          <Form.Item
            name="amount"
            label={<span style={{ fontWeight: 500 }}>Количество ({token.symbol})</span>}
            rules={[
              { required: true, message: 'Количество обязательно' },
              { type: 'number', min: 1, message: 'Количество должно быть не менее 1' },
            ]}
          >
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              placeholder="Введите количество"
              formatter={(value) => (value ? Number(value).toLocaleString('ru-RU') : '')}
              parser={(value) => Number((value || '').toString().replace(/[\s,]/g, '')) as unknown as 1}
            />
          </Form.Item>

          <Form.Item
            name="userId"
            label={<span style={{ fontWeight: 500 }}>ID пользователя</span>}
            rules={[{ required: true, message: 'ID пользователя обязателен' }]}
            extra="UUID получателя выпуска / держателя для сжигания"
          >
            <Input placeholder="00000000-0000-0000-0000-000000000000" />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0 }}>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={isMutating}
                danger={modalMode === 'burn'}
                style={{ borderRadius: 8 }}
              >
                {modalMode === 'mint' ? 'Выпустить' : 'Сжечь'}
              </Button>
              <Button onClick={handleModalClose} style={{ borderRadius: 8 }}>Отмена</Button>
            </Space>
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}

function ProfileRow({
  label,
  value,
  mono,
  small,
}: {
  label: string
  value: string
  mono?: boolean
  small?: boolean
}) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        padding: '10px 0',
        borderBottom: '1px solid var(--border-light)',
        gap: 12,
      }}
    >
      <Text type="secondary" style={{ fontSize: 12 }}>{label}</Text>
      <Text
        style={{
          fontSize: small ? 11 : 13,
          fontWeight: 500,
          textAlign: 'right',
          fontFamily: mono ? 'JetBrains Mono, monospace' : undefined,
          maxWidth: '70%',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
        }}
      >
        {value}
      </Text>
    </div>
  )
}

function SupplyBar({
  label,
  sub,
  value,
  total,
  symbol,
  colour,
}: {
  label: string
  sub: string
  value: number
  total: number
  symbol: string
  colour: string
}) {
  const pct = total > 0 ? Math.min(100, (value / total) * 100) : 0
  return (
    <div style={{ marginBottom: 16 }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 4 }}>
        <div>
          <div style={{ fontSize: 13, fontWeight: 500, color: 'var(--text-primary)' }}>{label}</div>
          <Text type="secondary" style={{ fontSize: 11 }}>{sub}</Text>
        </div>
        <Tooltip title={`${value.toLocaleString('ru-RU')} ${symbol}`}>
          <div style={{
            fontSize: 16, fontWeight: 700, color: colour,
            fontVariantNumeric: 'tabular-nums', textAlign: 'right',
          }}>
            {formatTokenAmount(value, symbol, { compact: true })}
          </div>
        </Tooltip>
      </div>
      <div style={{
        height: 6, borderRadius: 4, background: 'var(--surface-1, #F3F4F6)', overflow: 'hidden',
      }}>
        <div style={{
          width: `${pct}%`, height: '100%', background: colour, transition: 'width 0.3s',
        }} />
      </div>
      <Text type="secondary" style={{ fontSize: 11 }}>
        {pct.toFixed(1)}% от {total > 0 ? formatCompact(total) : '∞'}
      </Text>
    </div>
  )
}
