import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Form,
  Input,
  InputNumber,
  Select,
  Button,
  Card,
  Typography,
  Space,
  message,
  Steps,
  Row,
  Col,
  Tag,
  Tooltip,
} from 'antd'
import {
  ArrowLeftOutlined,
  BankOutlined,
  ArrowRightOutlined,
  CheckCircleOutlined,
  InfoCircleOutlined,
  GoldOutlined,
  StockOutlined,
  PieChartOutlined,
  DollarOutlined,
} from '@ant-design/icons'
import { useMutation } from '@tanstack/react-query'
import { tokens as tokenService } from '@/api/services'
import type { CreateTokenRequest, TokenType } from '@/api/types'
import { PageHeader } from '@/components/sber'
import { formatCompact } from '@/lib/format'

const { Text } = Typography

/**
 * Sprint 9-DS — admin TokenCreatePage refactor.
 *
 * <p>Was a single tall form with no progress indicator. Now a 3-step
 * wizard:
 *  1. Идентификация — symbol + name + type (with type-card picker)
 *  2. Параметры эмиссии — decimals + initial supply with helper text
 *  3. Подтверждение — preview card + Create button
 *
 * Mechanics unchanged: same `createToken` POST, same payload shape;
 * just better hand-holding through the form so an operator doesn't
 * fat-finger a decimals=18 token when they meant a fiat-backed 2.
 */

interface TokenTypeOption {
  value: TokenType
  label: string
  description: string
  icon: React.ReactNode
  recommended?: boolean
}

const TOKEN_TYPE_OPTIONS: TokenTypeOption[] = [
  {
    value: 'FIAT_BACKED',
    label: 'Валюта (FIAT_BACKED)',
    description: 'Привязка к фиатной валюте 1:1, обеспеченная резервом',
    icon: <DollarOutlined />,
    recommended: true,
  },
  {
    value: 'STABLE_TOKEN',
    label: 'Стейблкоин',
    description: 'Алгоритмический стейблкоин без явной привязки',
    icon: <DollarOutlined />,
  },
  {
    value: 'EQUITY_TOKEN',
    label: 'Акция',
    description: 'Токенизированная корпоративная акция (MOEX-style)',
    icon: <StockOutlined />,
  },
  {
    value: 'COMMODITY_BACKED',
    label: 'Сырьё',
    description: 'Обеспечен физическим активом (золото, нефть, газ)',
    icon: <GoldOutlined />,
  },
  {
    value: 'INDEX_TOKEN',
    label: 'Индекс',
    description: 'Корзина базовых активов с фиксированным весом',
    icon: <PieChartOutlined />,
  },
  {
    value: 'UTILITY',
    label: 'Утилитарный',
    description: 'Доступ к платформенной услуге (например, газ за операции)',
    icon: <PieChartOutlined />,
  },
  {
    value: 'LP_TOKEN',
    label: 'LP-токен',
    description: 'Внутренний токен LP-позиции (обычно автоматически)',
    icon: <PieChartOutlined />,
  },
  {
    value: 'GOVERNANCE_TOKEN',
    label: 'Управление',
    description: 'Право голоса в DAO платформы',
    icon: <PieChartOutlined />,
  },
]

export default function TokenCreatePage() {
  const navigate = useNavigate()
  const [form] = Form.useForm<CreateTokenRequest>()
  const [messageApi, contextHolder] = message.useMessage()
  const [step, setStep] = useState(0)
  const [values, setValues] = useState<Partial<CreateTokenRequest>>({
    decimals: 2,
    initialSupply: 1000000,
  })

  const createMutation = useMutation({
    mutationFn: (data: CreateTokenRequest) => tokenService.createToken(data),
    onSuccess: (token) => {
      messageApi.success(`Токен "${token.symbol}" успешно создан`)
      navigate('/tokens')
    },
    onError: (err: unknown) => {
      const axiosError = err as { response?: { data?: { message?: string } } }
      messageApi.error(axiosError?.response?.data?.message || 'Не удалось создать токен')
    },
  })

  const goNext = async () => {
    const fieldsByStep: Record<number, (keyof CreateTokenRequest)[]> = {
      0: ['symbol', 'name', 'tokenType'],
      1: ['decimals', 'initialSupply'],
    }
    try {
      await form.validateFields(fieldsByStep[step])
      const snapshot = form.getFieldsValue(true)
      setValues((v) => ({ ...v, ...snapshot }))
      setStep((s) => s + 1)
    } catch {
      messageApi.warning('Заполните обязательные поля корректно')
    }
  }

  const goBack = () => setStep((s) => Math.max(0, s - 1))

  const handleSubmit = () => {
    const merged = { ...values, ...form.getFieldsValue(true) } as CreateTokenRequest
    createMutation.mutate(merged)
  }

  const typeMeta = TOKEN_TYPE_OPTIONS.find((o) => o.value === values.tokenType)
  const exampleAmount = values.initialSupply
    ? values.initialSupply / Math.pow(10, values.decimals ?? 0)
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

        <PageHeader
          title="Создание токена"
          subtitle="Эмиссия нового актива в каталог платформы — 3 шага: идентификация, параметры эмиссии, подтверждение"
        />

        <Card
          className="sber-card"
          style={{ borderRadius: 12, border: '1px solid var(--border-light)' }}
          styles={{ body: { padding: 28 } }}
        >
          <Steps
            current={step}
            style={{ marginBottom: 28 }}
            items={[
              { title: 'Идентификация', description: 'Символ и тип' },
              { title: 'Эмиссия', description: 'Decimals и supply' },
              { title: 'Подтверждение', description: 'Проверка и создание' },
            ]}
          />

          <Form
            form={form}
            layout="vertical"
            initialValues={values}
            size="large"
            preserve
          >
            {step === 0 && (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                <Row gutter={[16, 16]}>
                  <Col xs={24} md={8}>
                    <Form.Item
                      name="symbol"
                      label={<span style={{ fontWeight: 500 }}>Символ</span>}
                      rules={[
                        { required: true, message: 'Символ обязателен' },
                        { max: 10, message: 'Не более 10 символов' },
                        { pattern: /^[A-Z0-9]+$/, message: 'Только заглавные буквы и цифры' },
                      ]}
                      extra={<Text type="secondary" style={{ fontSize: 11 }}>Например: SRUB, SBTC, GAZP</Text>}
                    >
                      <Input placeholder="SRUB" style={{ textTransform: 'uppercase' }} />
                    </Form.Item>
                  </Col>
                  <Col xs={24} md={16}>
                    <Form.Item
                      name="name"
                      label={<span style={{ fontWeight: 500 }}>Полное название</span>}
                      rules={[
                        { required: true, message: 'Название обязательно' },
                        { max: 100, message: 'Не более 100 символов' },
                      ]}
                    >
                      <Input placeholder="Sber Russian Ruble" />
                    </Form.Item>
                  </Col>
                </Row>

                <div>
                  <div style={{ fontSize: 13, fontWeight: 500, marginBottom: 8 }}>
                    Тип токена <span style={{ color: '#ff4d4f' }}>*</span>
                  </div>
                  <Form.Item
                    name="tokenType"
                    noStyle
                    rules={[{ required: true, message: 'Выберите тип токена' }]}
                  >
                    <TokenTypePicker />
                  </Form.Item>
                </div>
              </Space>
            )}

            {step === 1 && (
              <Row gutter={[24, 16]}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="decimals"
                    label={
                      <Space>
                        <span style={{ fontWeight: 500 }}>Десятичные</span>
                        <Tooltip title="Сколько знаков после запятой поддерживает токен. 2 для валют (копейки), 6 для USDT-стиля, 18 для ETH-стиля.">
                          <InfoCircleOutlined style={{ color: 'var(--text-muted)' }} />
                        </Tooltip>
                      </Space>
                    }
                    rules={[
                      { required: true, message: 'Обязательно' },
                      { type: 'number', min: 0, max: 18, message: '0–18' },
                    ]}
                    extra={
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        Базовая единица = 10<sup>−decimals</sup> токена
                      </Text>
                    }
                  >
                    <InputNumber min={0} max={18} style={{ width: '100%' }} />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="initialSupply"
                    label={<span style={{ fontWeight: 500 }}>Начальная эмиссия (raw)</span>}
                    rules={[
                      { required: true, message: 'Обязательно' },
                      { type: 'number', min: 0, message: 'Не может быть отрицательной' },
                    ]}
                    extra={
                      <Text type="secondary" style={{ fontSize: 11 }}>
                        В единицах базы (10<sup>decimals</sup> = 1 токен)
                      </Text>
                    }
                  >
                    <InputNumber
                      min={0}
                      style={{ width: '100%' }}
                      formatter={(value) => (value ? Number(value).toLocaleString('ru-RU') : '')}
                      parser={(value) => Number((value || '').toString().replace(/[\s,]/g, '')) as unknown as 0}
                    />
                  </Form.Item>
                </Col>
                <Col xs={24}>
                  <Card
                    size="small"
                    style={{ borderRadius: 12, background: 'var(--surface-1, #FAFAFA)', border: '1px dashed var(--border-light)' }}
                  >
                    <Space>
                      <InfoCircleOutlined style={{ color: '#296AE3' }} />
                      <Text style={{ fontSize: 13 }}>
                        Будет выпущено{' '}
                        <strong>
                          {(form.getFieldValue('initialSupply') ?? 0).toLocaleString('ru-RU')}
                        </strong>{' '}
                        базовых единиц = ≈{' '}
                        <strong>
                          {formatCompact(
                            (form.getFieldValue('initialSupply') ?? 0) /
                              Math.pow(10, form.getFieldValue('decimals') ?? 0),
                          )}
                        </strong>{' '}
                        {form.getFieldValue('symbol') || 'токенов'}
                      </Text>
                    </Space>
                  </Card>
                </Col>
              </Row>
            )}

            {step === 2 && (
              <Card
                style={{
                  borderRadius: 16,
                  background: 'linear-gradient(135deg, rgba(33,160,56,0.08) 0%, rgba(33,160,56,0.02) 100%)',
                  border: '1px solid var(--sber-green-light)',
                }}
              >
                <Space size={16} align="start">
                  <div
                    aria-hidden
                    style={{
                      width: 56, height: 56, borderRadius: 14,
                      background: 'var(--sber-green)', color: '#fff',
                      display: 'flex', alignItems: 'center', justifyContent: 'center',
                      fontSize: 22, fontWeight: 700,
                    }}
                  >
                    {typeMeta?.icon ?? <BankOutlined />}
                  </div>
                  <div style={{ flex: 1 }}>
                    <Space size={8} align="baseline">
                      <Text strong style={{ fontSize: 18 }}>
                        {values.symbol || '—'}
                      </Text>
                      <Text type="secondary">{values.name || '—'}</Text>
                      {typeMeta && <Tag color="green">{typeMeta.label}</Tag>}
                    </Space>
                    <div style={{ marginTop: 12, display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12 }}>
                      <ReviewRow label="Decimals" value={String(values.decimals ?? 0)} />
                      <ReviewRow label="Базовая единица" value={`10⁻${values.decimals ?? 0}`} />
                      <ReviewRow
                        label="Начальная эмиссия (raw)"
                        value={(values.initialSupply ?? 0).toLocaleString('ru-RU')}
                      />
                      <ReviewRow
                        label="Эквивалент в токенах"
                        value={`≈ ${formatCompact(exampleAmount)} ${values.symbol ?? ''}`}
                      />
                    </div>
                  </div>
                </Space>
              </Card>
            )}

            <div
              style={{
                marginTop: 28,
                display: 'flex',
                justifyContent: 'space-between',
                gap: 8,
                flexWrap: 'wrap',
              }}
            >
              <Button onClick={() => navigate('/tokens')} style={{ borderRadius: 8 }}>
                Отмена
              </Button>
              <Space>
                {step > 0 && (
                  <Button onClick={goBack} style={{ borderRadius: 8 }}>
                    Назад
                  </Button>
                )}
                {step < 2 && (
                  <Button
                    type="primary"
                    onClick={goNext}
                    icon={<ArrowRightOutlined />}
                    iconPosition="end"
                    style={{ borderRadius: 8 }}
                  >
                    Далее
                  </Button>
                )}
                {step === 2 && (
                  <Button
                    type="primary"
                    onClick={handleSubmit}
                    loading={createMutation.isPending}
                    icon={<CheckCircleOutlined />}
                    style={{ borderRadius: 8 }}
                  >
                    Создать токен
                  </Button>
                )}
              </Space>
            </div>
          </Form>
        </Card>
      </Space>
    </>
  )
}

function TokenTypePicker({
  value,
  onChange,
}: {
  value?: TokenType
  onChange?: (v: TokenType) => void
}) {
  return (
    <Row gutter={[12, 12]}>
      {TOKEN_TYPE_OPTIONS.map((opt) => {
        const active = value === opt.value
        return (
          <Col xs={24} sm={12} md={8} key={opt.value}>
            <Card
              hoverable
              size="small"
              onClick={() => onChange?.(opt.value)}
              styles={{ body: { padding: 12 } }}
              style={{
                borderRadius: 12,
                border: `1px solid ${active ? 'var(--sber-green)' : 'var(--border-light)'}`,
                background: active ? 'var(--sber-green-light, rgba(33,160,56,0.08))' : '#fff',
                cursor: 'pointer',
                transition: 'all 0.15s',
              }}
            >
              <Space size={10} align="start" style={{ width: '100%' }}>
                <div
                  aria-hidden
                  style={{
                    width: 36, height: 36, borderRadius: 10,
                    background: active ? 'var(--sber-green)' : 'var(--surface-1, #F3F4F6)',
                    color: active ? '#fff' : 'var(--text-secondary)',
                    display: 'flex', alignItems: 'center', justifyContent: 'center',
                    fontSize: 16, flexShrink: 0,
                  }}
                >
                  {opt.icon}
                </div>
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-primary)' }}>
                    {opt.label}
                    {opt.recommended && (
                      <Tag color="green" style={{ marginLeft: 6, fontSize: 10, padding: '0 4px', lineHeight: '16px' }}>
                        реком.
                      </Tag>
                    )}
                  </div>
                  <Text type="secondary" style={{ fontSize: 11 }}>{opt.description}</Text>
                </div>
              </Space>
            </Card>
          </Col>
        )
      })}
    </Row>
  )
}

function ReviewRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>{label}</Text>
      <Text strong style={{ fontSize: 14, fontVariantNumeric: 'tabular-nums' }}>{value}</Text>
    </div>
  )
}
