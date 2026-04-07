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
  Divider,
} from 'antd'
import { ArrowLeftOutlined, FundOutlined } from '@ant-design/icons'
import { useMutation } from '@tanstack/react-query'
import { pools as poolService } from '@/api/services'
import type { CreatePoolRequest } from '@/api/types'

const { Title } = Typography

export default function PoolCreatePage() {
  const navigate = useNavigate()
  const [form] = Form.useForm<CreatePoolRequest>()
  const [messageApi, contextHolder] = message.useMessage()

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

  const handleSubmit = (values: CreatePoolRequest) => {
    createMutation.mutate(values)
  }

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/pools')} style={{ borderRadius: 8 }}>
            Назад
          </Button>
          <Title level={4} className="sber-page-title">
            Создать пул ликвидности
          </Title>
        </div>

        <Card
          className="sber-card"
          style={{
            borderRadius: 12,
            border: '1px solid #E5E7EB',
            maxWidth: 800,
          }}
          title={
            <Space>
              <FundOutlined style={{ color: '#21A038' }} />
              <span style={{ fontWeight: 600 }}>Конфигурация пула</span>
            </Space>
          }
        >
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            initialValues={{
              binStep: 10,
              baseFeeBps: 10,
              maxVariableFeeBps: 100,
              protocolFeePct: 20,
              decayPeriodSeconds: 3600,
            }}
            size="large"
          >
            <Divider orientation="left" style={{ color: '#6B7280' }}>Токенная пара</Divider>
            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="tokenXId"
                  label={<span style={{ fontWeight: 500 }}>ID токена X</span>}
                  rules={[{ required: true, message: 'ID токена X обязателен' }]}
                >
                  <Input placeholder="UUID токена X" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="tokenYId"
                  label={<span style={{ fontWeight: 500 }}>ID токена Y</span>}
                  rules={[{ required: true, message: 'ID токена Y обязателен' }]}
                >
                  <Input placeholder="UUID токена Y" />
                </Form.Item>
              </Col>
            </Row>

            <Divider orientation="left" style={{ color: '#6B7280' }}>Параметры пула</Divider>
            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="binStep"
                  label={<span style={{ fontWeight: 500 }}>Шаг бина (bps)</span>}
                  tooltip="Шаг цены между соседними бинами в базисных пунктах"
                  rules={[
                    { required: true, message: 'Шаг бина обязателен' },
                    { type: 'number', min: 1, max: 10000, message: 'Должно быть от 1 до 10000' },
                  ]}
                >
                  <InputNumber min={1} max={10000} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="baseFeeBps"
                  label={<span style={{ fontWeight: 500 }}>Базовая комиссия (bps)</span>}
                  tooltip="Базовая торговая комиссия в базисных пунктах"
                  rules={[
                    { required: true, message: 'Базовая комиссия обязательна' },
                    { type: 'number', min: 0, max: 10000, message: 'Должно быть от 0 до 10000' },
                  ]}
                >
                  <InputNumber min={0} max={10000} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="initialPrice"
                  label={<span style={{ fontWeight: 500 }}>Начальная цена</span>}
                  rules={[
                    { required: true, message: 'Начальная цена обязательна' },
                    { type: 'number', min: 0, message: 'Цена должна быть положительной' },
                  ]}
                >
                  <InputNumber
                    min={0}
                    style={{ width: '100%' }}
                    placeholder="например 1.0"
                    step={0.01}
                  />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="maxVariableFeeBps"
                  label={<span style={{ fontWeight: 500 }}>Макс. переменная комиссия (bps)</span>}
                  tooltip="Максимальная переменная комиссия в базисных пунктах"
                  rules={[
                    { required: true, message: 'Макс. переменная комиссия обязательна' },
                    { type: 'number', min: 0, max: 10000, message: 'Должно быть от 0 до 10000' },
                  ]}
                >
                  <InputNumber min={0} max={10000} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={16}>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="protocolFeePct"
                  label={<span style={{ fontWeight: 500 }}>Комиссия протокола (%)</span>}
                  tooltip="Процент комиссий, направляемых в протокол"
                  rules={[
                    { required: true, message: 'Процент комиссии протокола обязателен' },
                    {
                      type: 'number',
                      min: 0,
                      max: 100,
                      message: 'Должно быть от 0 до 100',
                    },
                  ]}
                >
                  <InputNumber min={0} max={100} style={{ width: '100%' }} suffix="%" />
                </Form.Item>
              </Col>
              <Col xs={24} sm={12}>
                <Form.Item
                  name="decayPeriodSeconds"
                  label={<span style={{ fontWeight: 500 }}>Период затухания (сек)</span>}
                  tooltip="Период затухания аккумулятора волатильности"
                  rules={[
                    { required: true, message: 'Период затухания обязателен' },
                    { type: 'number', min: 1, message: 'Должно быть не менее 1 секунды' },
                  ]}
                >
                  <InputNumber min={1} style={{ width: '100%' }} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item style={{ marginBottom: 0 }}>
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={createMutation.isPending}
                  icon={<FundOutlined />}
                  style={{ borderRadius: 8 }}
                >
                  Создать пул
                </Button>
                <Button onClick={() => navigate('/pools')} style={{ borderRadius: 8 }}>Отмена</Button>
              </Space>
            </Form.Item>
          </Form>
        </Card>
      </Space>
    </>
  )
}
