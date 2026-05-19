import { useState } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import {
  Card,
  Descriptions,
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
} from 'antd'
import { ArrowLeftOutlined, PlusCircleOutlined, MinusCircleOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { tokens as tokenService } from '@/api/services'
import type { Token, TokenType, MintBurnRequest } from '@/api/types'

const { Title } = Typography

// Sprint 9 — keep these maps aligned with TokensPage. Was missing the
// four Sprint 6 enum extensions which made TokenDetailPage refuse to
// type-check after we widened the TokenType union.
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
    if (modalMode === 'mint') {
      mintMutation.mutate(values)
    } else if (modalMode === 'burn') {
      burnMutation.mutate(values)
    }
  }

  const handleModalClose = () => {
    setModalMode(null)
    modalForm.resetFields()
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

  return (
    <>
      {contextHolder}
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tokens')} style={{ borderRadius: 8 }}>
            Назад
          </Button>
          <Title level={4} className="sber-page-title">
            Токен: {token.symbol}
          </Title>
          <Tag color={token.active ? 'green' : 'red'}>{token.active ? 'Активен' : 'Неактивен'}</Tag>
        </div>

        <Card
          className="sber-card"
          title={<span style={{ fontWeight: 600 }}>Информация о токене</span>}
          style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        >
          <Descriptions column={{ xs: 1, sm: 2, md: 3 }} bordered size="small">
            <Descriptions.Item label="ID" span={3}>
              {token.id}
            </Descriptions.Item>
            <Descriptions.Item label="Символ">
              <strong>{token.symbol}</strong>
            </Descriptions.Item>
            <Descriptions.Item label="Название">{token.name}</Descriptions.Item>
            <Descriptions.Item label="Тип токена">
              <Tag color={tokenTypeColor[token.tokenType as TokenType]}>
                {tokenTypeLabel[token.tokenType as TokenType] || token.tokenType}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="Десятичные">{token.decimals}</Descriptions.Item>
            <Descriptions.Item label="Общая эмиссия">
              {token.totalSupply.toLocaleString('ru-RU')}
            </Descriptions.Item>
            <Descriptions.Item label="В обращении">
              {token.circulatingSupply.toLocaleString('ru-RU')}
            </Descriptions.Item>
            <Descriptions.Item label="Статус">
              <Tag color={token.active ? 'green' : 'red'}>
                {token.active ? 'Активен' : 'Неактивен'}
              </Tag>
            </Descriptions.Item>
          </Descriptions>
        </Card>

        <Card
          className="sber-card"
          title={<span style={{ fontWeight: 600 }}>Действия администратора</span>}
          style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        >
          <Space size={12}>
            <Button
              type="primary"
              icon={<PlusCircleOutlined />}
              onClick={() => setModalMode('mint')}
              style={{ background: '#21A038', borderColor: '#21A038', borderRadius: 8 }}
            >
              Выпустить токены
            </Button>
            <Button
              danger
              icon={<MinusCircleOutlined />}
              onClick={() => setModalMode('burn')}
              style={{ borderRadius: 8 }}
            >
              Сжечь токены
            </Button>
          </Space>
        </Card>
      </Space>

      <Modal
        title={modalMode === 'mint' ? 'Выпуск токенов' : 'Сжигание токенов'}
        open={modalMode !== null}
        onCancel={handleModalClose}
        footer={null}
        destroyOnClose
      >
        <Form
          form={modalForm}
          layout="vertical"
          onFinish={handleModalSubmit}
          size="large"
        >
          <Form.Item
            name="amount"
            label={<span style={{ fontWeight: 500 }}>Количество</span>}
            rules={[
              { required: true, message: 'Количество обязательно' },
              { type: 'number', min: 1, message: 'Количество должно быть не менее 1' },
            ]}
          >
            <InputNumber
              min={1}
              style={{ width: '100%' }}
              placeholder="Введите количество"
              formatter={(value) => `${value}`.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}
              parser={(value) => Number(value!.replace(/,/g, '')) as unknown as 1}
            />
          </Form.Item>

          <Form.Item
            name="userId"
            label={<span style={{ fontWeight: 500 }}>ID пользователя</span>}
            rules={[{ required: true, message: 'ID пользователя обязателен' }]}
          >
            <Input placeholder="Введите ID пользователя" />
          </Form.Item>

          <Form.Item style={{ marginBottom: 0 }}>
            <Space>
              <Button
                type="primary"
                htmlType="submit"
                loading={isMutating}
                danger={modalMode === 'burn'}
                style={
                  modalMode === 'mint'
                    ? { background: '#21A038', borderColor: '#21A038', borderRadius: 8 }
                    : { borderRadius: 8 }
                }
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
