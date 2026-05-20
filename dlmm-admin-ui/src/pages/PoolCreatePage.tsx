import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  Form,
  Input,
  InputNumber,
  Button,
  Card,
  Typography,
  Space,
  message,
  Row,
  Col,
  Steps,
  Select,
  Tag,
  Tooltip,
} from 'antd'
import {
  ArrowLeftOutlined,
  FundOutlined,
  ArrowRightOutlined,
  CheckCircleOutlined,
  InfoCircleOutlined,
  ThunderboltFilled,
} from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import { pools as poolService, tokens as tokensApi } from '@/api/services'
import type { CreatePoolRequest, Token } from '@/api/types'
import { PageHeader, TokenPairChip } from '@/components/sber'
import { bpsToPercent } from '@/utils/format'

const { Text } = Typography

/**
 * Sprint 9-DS — admin PoolCreatePage refactor.
 *
 * <p>Was one tall form with dividers. Now a 3-step wizard:
 *  1. Токенная пара — symbol-aware Select (drops UUID copy-paste)
 *  2. Параметры — bin step + base fee + initial price, with preset
 *     buttons for typical pool kinds (stable, volatile, exotic)
 *  3. Подтверждение — token-pair hero + parameter review + Create
 *
 * <p>Token Select replaces the two raw "UUID токена X / Y" inputs with
 * a searchable list joined against the token catalogue — the operator
 * picks "GAZP" + "SRUB" and the wizard resolves the UUIDs server-side.
 */

interface PoolPreset {
  key: string
  label: string
  description: string
  binStep: number
  baseFeeBps: number
  maxVariableFeeBps: number
}

const PRESETS: PoolPreset[] = [
  {
    key: 'stable',
    label: 'Стабильная пара',
    description: 'SRUB/SUSDT, SRUB/SEUR — узкий шаг, низкая комиссия',
    binStep: 5,
    baseFeeBps: 10,
    maxVariableFeeBps: 50,
  },
  {
    key: 'major',
    label: 'Акция / индекс',
    description: 'GAZP/SRUB, SBER/SRUB — средний шаг, базовая комиссия',
    binStep: 10,
    baseFeeBps: 25,
    maxVariableFeeBps: 150,
  },
  {
    key: 'exotic',
    label: 'Волатильная пара',
    description: 'SBTC/SRUB, SETH/SRUB — широкий шаг, высокая комиссия',
    binStep: 25,
    baseFeeBps: 30,
    maxVariableFeeBps: 300,
  },
]

export default function PoolCreatePage() {
  const navigate = useNavigate()
  const [form] = Form.useForm<CreatePoolRequest>()
  const [messageApi, contextHolder] = message.useMessage()
  const [step, setStep] = useState(0)
  const [values, setValues] = useState<Partial<CreatePoolRequest>>({
    binStep: 10,
    baseFeeBps: 10,
    maxVariableFeeBps: 100,
    protocolFeePct: 20,
    decayPeriodSeconds: 3600,
    initialPrice: 1.0,
  })

  const { data: tokenPage } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokensApi.getTokens(0, 200),
  })
  const tokenById = useMemo(() => {
    const m = new Map<string, Token>()
    for (const t of tokenPage?.content ?? []) m.set(t.id, t)
    return m
  }, [tokenPage])

  const tokenOptions = useMemo(
    () =>
      (tokenPage?.content ?? [])
        .filter((t) => t.active)
        .map((t) => ({
          label: `${t.symbol} — ${t.name}`,
          value: t.id,
        })),
    [tokenPage],
  )

  const createMutation = useMutation({
    mutationFn: (data: CreatePoolRequest) => poolService.createPool(data),
    onSuccess: (pool) => {
      messageApi.success(`Пул успешно создан (ID: ${pool.id})`)
      navigate('/pools')
    },
    onError: (err: unknown) => {
      const axiosError = err as { response?: { data?: { message?: string } } }
      messageApi.error(axiosError?.response?.data?.message || 'Не удалось создать пул')
    },
  })

  const goNext = async () => {
    const fieldsByStep: Record<number, (keyof CreatePoolRequest)[]> = {
      0: ['tokenXId', 'tokenYId'],
      1: ['binStep', 'baseFeeBps', 'maxVariableFeeBps', 'initialPrice', 'protocolFeePct', 'decayPeriodSeconds'],
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
    const merged = { ...values, ...form.getFieldsValue(true) } as CreatePoolRequest
    createMutation.mutate(merged)
  }

  const applyPreset = (preset: PoolPreset) => {
    form.setFieldsValue({
      binStep: preset.binStep,
      baseFeeBps: preset.baseFeeBps,
      maxVariableFeeBps: preset.maxVariableFeeBps,
    })
    setValues((v) => ({
      ...v,
      binStep: preset.binStep,
      baseFeeBps: preset.baseFeeBps,
      maxVariableFeeBps: preset.maxVariableFeeBps,
    }))
    messageApi.info(`Применён пресет: ${preset.label}`)
  }

  const tokenX = values.tokenXId ? tokenById.get(values.tokenXId) : null
  const tokenY = values.tokenYId ? tokenById.get(values.tokenYId) : null

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <Button
          type="text"
          icon={<ArrowLeftOutlined />}
          onClick={() => navigate('/pools')}
          style={{ padding: 0, color: 'var(--text-secondary)' }}
        >
          К списку пулов
        </Button>

        <PageHeader
          title="Создание пула ликвидности"
          subtitle="DLMM-пул с бин-ориентированной концентрированной ликвидностью — 3 шага: пара, параметры, подтверждение"
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
              { title: 'Токенная пара', description: 'X и Y' },
              { title: 'Параметры', description: 'Шаг и комиссии' },
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
              <Row gutter={[16, 16]}>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="tokenXId"
                    label={<span style={{ fontWeight: 500 }}>Токен X</span>}
                    rules={[{ required: true, message: 'Выберите токен X' }]}
                    extra={<Text type="secondary" style={{ fontSize: 11 }}>Базовый актив пары</Text>}
                  >
                    <Select
                      showSearch
                      placeholder="Например: GAZP"
                      options={tokenOptions}
                      filterOption={(input, option) =>
                        (option?.label as string).toLowerCase().includes(input.toLowerCase())
                      }
                    />
                  </Form.Item>
                </Col>
                <Col xs={24} md={12}>
                  <Form.Item
                    name="tokenYId"
                    label={<span style={{ fontWeight: 500 }}>Токен Y</span>}
                    rules={[{ required: true, message: 'Выберите токен Y' }]}
                    extra={<Text type="secondary" style={{ fontSize: 11 }}>Котировочный актив (обычно SRUB)</Text>}
                  >
                    <Select
                      showSearch
                      placeholder="Например: SRUB"
                      options={tokenOptions}
                      filterOption={(input, option) =>
                        (option?.label as string).toLowerCase().includes(input.toLowerCase())
                      }
                    />
                  </Form.Item>
                </Col>
                {tokenX && tokenY && (
                  <Col xs={24}>
                    <Card
                      size="small"
                      style={{
                        borderRadius: 12,
                        background: 'var(--surface-1, #FAFAFA)',
                        border: '1px dashed var(--border-light)',
                      }}
                    >
                      <Space>
                        <Text type="secondary">Пара:</Text>
                        <TokenPairChip x={tokenX.symbol} y={tokenY.symbol} size="md" />
                      </Space>
                    </Card>
                  </Col>
                )}
              </Row>
            )}

            {step === 1 && (
              <>
                <div style={{ marginBottom: 16 }}>
                  <Text strong style={{ fontSize: 13 }}>Пресеты:</Text>
                  <Space wrap style={{ marginLeft: 12 }}>
                    {PRESETS.map((p) => (
                      <Tooltip
                        key={p.key}
                        title={`${p.description} · bin ${bpsToPercent(p.binStep)} · fee ${bpsToPercent(p.baseFeeBps)}`}
                      >
                        <Button
                          size="small"
                          onClick={() => applyPreset(p)}
                          icon={<ThunderboltFilled />}
                          style={{ borderRadius: 8 }}
                        >
                          {p.label}
                        </Button>
                      </Tooltip>
                    ))}
                  </Space>
                </div>
                <Row gutter={[16, 16]}>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="binStep"
                      label={
                        <Space>
                          <span style={{ fontWeight: 500 }}>Шаг бина (bps)</span>
                          <Tooltip title="Шаг цены между соседними бинами. 5 bps = 0.05% (стабильная пара), 25 bps = 0.25% (волатильная).">
                            <InfoCircleOutlined style={{ color: 'var(--text-muted)' }} />
                          </Tooltip>
                        </Space>
                      }
                      rules={[
                        { required: true, message: 'Обязательно' },
                        { type: 'number', min: 1, max: 10000, message: '1–10000' },
                      ]}
                    >
                      <InputNumber min={1} max={10000} style={{ width: '100%' }} addonAfter="bps" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="baseFeeBps"
                      label={
                        <Space>
                          <span style={{ fontWeight: 500 }}>Базовая комиссия (bps)</span>
                          <Tooltip title="Минимальная торговая комиссия. 10 bps = 0.10%.">
                            <InfoCircleOutlined style={{ color: 'var(--text-muted)' }} />
                          </Tooltip>
                        </Space>
                      }
                      rules={[
                        { required: true, message: 'Обязательно' },
                        { type: 'number', min: 0, max: 10000, message: '0–10000' },
                      ]}
                    >
                      <InputNumber min={0} max={10000} style={{ width: '100%' }} addonAfter="bps" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="initialPrice"
                      label={<span style={{ fontWeight: 500 }}>Начальная цена</span>}
                      rules={[
                        { required: true, message: 'Обязательно' },
                        { type: 'number', min: 0, message: 'Должна быть положительной' },
                      ]}
                      extra={<Text type="secondary" style={{ fontSize: 11 }}>Y за 1 X</Text>}
                    >
                      <InputNumber min={0} step={0.0001} style={{ width: '100%' }} placeholder="1.0" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="maxVariableFeeBps"
                      label={
                        <Space>
                          <span style={{ fontWeight: 500 }}>Макс. переменная комиссия (bps)</span>
                          <Tooltip title="Надбавка к базовой при высокой волатильности.">
                            <InfoCircleOutlined style={{ color: 'var(--text-muted)' }} />
                          </Tooltip>
                        </Space>
                      }
                      rules={[
                        { required: true, message: 'Обязательно' },
                        { type: 'number', min: 0, max: 10000, message: '0–10000' },
                      ]}
                    >
                      <InputNumber min={0} max={10000} style={{ width: '100%' }} addonAfter="bps" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="protocolFeePct"
                      label={
                        <Space>
                          <span style={{ fontWeight: 500 }}>Комиссия протокола</span>
                          <Tooltip title="Доля собранных комиссий, направляемая в Treasury протокола.">
                            <InfoCircleOutlined style={{ color: 'var(--text-muted)' }} />
                          </Tooltip>
                        </Space>
                      }
                      rules={[
                        { required: true, message: 'Обязательно' },
                        { type: 'number', min: 0, max: 100, message: '0–100' },
                      ]}
                    >
                      <InputNumber min={0} max={100} style={{ width: '100%' }} addonAfter="%" />
                    </Form.Item>
                  </Col>
                  <Col xs={24} sm={12}>
                    <Form.Item
                      name="decayPeriodSeconds"
                      label={<span style={{ fontWeight: 500 }}>Период затухания (сек)</span>}
                      rules={[
                        { required: true, message: 'Обязательно' },
                        { type: 'number', min: 1, message: '≥ 1 сек' },
                      ]}
                      extra={<Text type="secondary" style={{ fontSize: 11 }}>3600 = 1 час — стандартный режим</Text>}
                    >
                      <InputNumber min={1} style={{ width: '100%' }} addonAfter="сек" />
                    </Form.Item>
                  </Col>
                </Row>
              </>
            )}

            {step === 2 && (
              <Card
                style={{
                  borderRadius: 16,
                  background: 'linear-gradient(135deg, rgba(33,160,56,0.08) 0%, rgba(33,160,56,0.02) 100%)',
                  border: '1px solid var(--sber-green-light)',
                }}
              >
                <Space direction="vertical" size={16} style={{ width: '100%' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
                    <TokenPairChip
                      x={tokenX?.symbol}
                      y={tokenY?.symbol}
                      size="lg"
                    />
                    <Tag color="green" style={{ padding: '4px 12px', borderRadius: 999 }}>
                      Готов к созданию
                    </Tag>
                  </div>

                  <Row gutter={[16, 12]}>
                    <Col xs={12} sm={8}><ReviewRow label="Шаг бина" value={bpsToPercent(values.binStep ?? 0)} /></Col>
                    <Col xs={12} sm={8}><ReviewRow label="Базовая комиссия" value={bpsToPercent(values.baseFeeBps ?? 0)} /></Col>
                    <Col xs={12} sm={8}><ReviewRow label="Макс. переменная" value={bpsToPercent(values.maxVariableFeeBps ?? 0)} /></Col>
                    <Col xs={12} sm={8}>
                      <ReviewRow
                        label="Начальная цена"
                        value={`${(values.initialPrice ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 6 })} ${tokenY?.symbol ?? 'Y'}/${tokenX?.symbol ?? 'X'}`}
                      />
                    </Col>
                    <Col xs={12} sm={8}><ReviewRow label="Комиссия протокола" value={`${values.protocolFeePct ?? 0}%`} /></Col>
                    <Col xs={12} sm={8}><ReviewRow label="Период затухания" value={`${values.decayPeriodSeconds ?? 0} сек`} /></Col>
                  </Row>
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
              <Button onClick={() => navigate('/pools')} style={{ borderRadius: 8 }}>
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
                    Создать пул
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

function ReviewRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <Text type="secondary" style={{ fontSize: 11, display: 'block' }}>{label}</Text>
      <Text strong style={{ fontSize: 14, fontVariantNumeric: 'tabular-nums' }}>{value}</Text>
    </div>
  )
}
