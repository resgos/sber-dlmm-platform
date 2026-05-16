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
} from 'antd'
import { ArrowLeftOutlined, BankOutlined } from '@ant-design/icons'
import { useMutation } from '@tanstack/react-query'
import { tokens as tokenService } from '@/api/services'
import type { CreateTokenRequest, TokenType } from '@/api/types'

const { Title } = Typography

const tokenTypeOptions: { value: TokenType; label: string }[] = [
  { value: 'STABLE_TOKEN', label: 'Стейблкоин' },
  { value: 'EQUITY_TOKEN', label: 'Товарный' },
  { value: 'LP_TOKEN', label: 'LP-токен' },
  { value: 'GOVERNANCE_TOKEN', label: 'Управление' },
]

export default function TokenCreatePage() {
  const navigate = useNavigate()
  const [form] = Form.useForm<CreateTokenRequest>()
  const [messageApi, contextHolder] = message.useMessage()

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

  const handleSubmit = (values: CreateTokenRequest) => {
    createMutation.mutate(values)
  }

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tokens')} style={{ borderRadius: 8 }}>
            Назад
          </Button>
          <Title level={4} className="sber-page-title">
            Создать токен
          </Title>
        </div>

        <Card
          className="sber-card"
          style={{
            borderRadius: 12,
            border: '1px solid #E5E7EB',
            maxWidth: 600,
          }}
          title={
            <Space>
              <BankOutlined style={{ color: '#21A038' }} />
              <span style={{ fontWeight: 600 }}>Параметры токена</span>
            </Space>
          }
        >
          <Form
            form={form}
            layout="vertical"
            onFinish={handleSubmit}
            initialValues={{ decimals: 2, initialSupply: 1000000 }}
            size="large"
          >
            <Form.Item
              name="symbol"
              label={<span style={{ fontWeight: 500 }}>Символ</span>}
              rules={[
                { required: true, message: 'Символ обязателен' },
                { max: 10, message: 'Символ не более 10 символов' },
                {
                  pattern: /^[A-Z0-9]+$/,
                  message: 'Символ должен содержать только заглавные буквы и цифры',
                },
              ]}
            >
              <Input placeholder="например RUBT" style={{ textTransform: 'uppercase' }} />
            </Form.Item>

            <Form.Item
              name="name"
              label={<span style={{ fontWeight: 500 }}>Название</span>}
              rules={[
                { required: true, message: 'Название обязательно' },
                { max: 100, message: 'Название не более 100 символов' },
              ]}
            >
              <Input placeholder="например Рублёвый токен" />
            </Form.Item>

            <Form.Item
              name="tokenType"
              label={<span style={{ fontWeight: 500 }}>Тип токена</span>}
              rules={[{ required: true, message: 'Выберите тип токена' }]}
            >
              <Select placeholder="Выберите тип токена" options={tokenTypeOptions} />
            </Form.Item>

            <Form.Item
              name="decimals"
              label={<span style={{ fontWeight: 500 }}>Десятичные</span>}
              rules={[
                { required: true, message: 'Десятичные обязательны' },
                { type: 'number', min: 0, max: 18, message: 'Десятичные должны быть от 0 до 18' },
              ]}
            >
              <InputNumber min={0} max={18} style={{ width: '100%' }} />
            </Form.Item>

            <Form.Item
              name="initialSupply"
              label={<span style={{ fontWeight: 500 }}>Начальная эмиссия</span>}
              rules={[
                { required: true, message: 'Начальная эмиссия обязательна' },
                { type: 'number', min: 0, message: 'Начальная эмиссия не может быть отрицательной' },
              ]}
            >
              <InputNumber
                min={0}
                style={{ width: '100%' }}
                formatter={(value) => `${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
                parser={(value) => Number(value!.replace(/,/g, '')) as unknown as 0}
              />
            </Form.Item>

            <Form.Item style={{ marginBottom: 0 }}>
              <Space>
                <Button
                  type="primary"
                  htmlType="submit"
                  loading={createMutation.isPending}
                  icon={<BankOutlined />}
                  style={{ borderRadius: 8 }}
                >
                  Создать токен
                </Button>
                <Button onClick={() => navigate('/tokens')} style={{ borderRadius: 8 }}>Отмена</Button>
              </Space>
            </Form.Item>
          </Form>
        </Card>
      </Space>
    </>
  )
}
