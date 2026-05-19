import {
  Card,
  Descriptions,
  Tag,
  Space,
  Typography,
  Alert,
  Divider,
  Row,
  Col,
  Statistic,
} from 'antd'
import {
  SettingOutlined,
  LockOutlined,
  GlobalOutlined,
  ClockCircleOutlined,
} from '@ant-design/icons'
import { authStore } from '@/store/authStore'

const { Title, Text } = Typography

export default function SettingsPage() {
  const user = authStore.getUser()
  const isSuperAdmin = user?.role === 'SUPER_ADMIN'

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Title level={4} className="sber-page-title">
        <SettingOutlined style={{ marginRight: 8, color: '#6B7280' }} />
        Настройки платформы
      </Title>

      {!isSuperAdmin && (
        <Alert
          message="Ограниченный доступ"
          description="Полная настройка платформы доступна только пользователям SUPER_ADMIN. Обратитесь к системному администратору для изменения настроек."
          type="warning"
          showIcon
          icon={<LockOutlined />}
          style={{ borderRadius: 8 }}
        />
      )}

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={12}>
          <Card
            className="sber-card"
            title={
              <Space>
                <GlobalOutlined style={{ color: '#21A038' }} />
                <span style={{ fontWeight: 600 }}>Информация о платформе</span>
              </Space>
            }
            style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
          >
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="Название платформы">
                Sber DLMM Platform
              </Descriptions.Item>
              <Descriptions.Item label="Версия">
                <Tag color="blue">v1.0.0</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Окружение">
                <Tag color="green">Продакшн</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Версия API">
                <Tag>v1</Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Блокчейн">
                Sber Private Chain
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>

        <Col xs={24} lg={12}>
          <Card
            className="sber-card"
            title={
              <Space>
                <LockOutlined style={{ color: '#21A038' }} />
                <span style={{ fontWeight: 600 }}>Ваш аккаунт</span>
              </Space>
            }
            style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
          >
            <Descriptions column={1} bordered size="small">
              <Descriptions.Item label="ID пользователя">
                <Text copyable style={{ fontFamily: 'monospace', fontSize: 12 }}>
                  {user?.userId || '—'}
                </Text>
              </Descriptions.Item>
              <Descriptions.Item label="Эл. почта">{user?.email || '—'}</Descriptions.Item>
              <Descriptions.Item label="Роль">
                <Tag
                  color={
                    user?.role === 'SUPER_ADMIN'
                      ? 'red'
                      : user?.role === 'ADMIN'
                      ? 'purple'
                      : user?.role === 'OPERATOR'
                      ? 'cyan'
                      : 'blue'
                  }
                >
                  {user?.role || 'USER'}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="Сессия">
                <Tag color="green">Активна</Tag>
              </Descriptions.Item>
            </Descriptions>
          </Card>
        </Col>
      </Row>

      <Card
        className="sber-card"
        title={
          <Space>
            <ClockCircleOutlined style={{ color: '#21A038' }} />
            <span style={{ fontWeight: 600 }}>Параметры платформы по умолчанию</span>
          </Space>
        }
        style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
        extra={!isSuperAdmin && <Tag color="orange">Только чтение</Tag>}
      >
        <Row gutter={[32, 16]}>
          <Col xs={12} sm={8} md={6}>
            <Statistic title="Мин. шаг между бинами" value="0.01%" />
          </Col>
          <Col xs={12} sm={8} md={6}>
            <Statistic title="Макс. шаг между бинами" value="100%" />
          </Col>
          <Col xs={12} sm={8} md={6}>
            <Statistic title="Макс. базовая комиссия" value="100%" />
          </Col>
          <Col xs={12} sm={8} md={6}>
            <Statistic title="Макс. комиссия протокола" value="100%" />
          </Col>
          <Col xs={12} sm={8} md={6}>
            <Statistic title="Затухание по умолч." value="60 мин" />
          </Col>
          <Col xs={12} sm={8} md={6}>
            <Statistic title="Макс. переменная комиссия" value="1%" />
          </Col>
        </Row>
      </Card>

      {isSuperAdmin && (
        <>
          <Divider />
          <Alert
            message="Конфигурация SUPER_ADMIN"
            description="Как SUPER_ADMIN, вы имеете полный доступ к изменению параметров платформы. Изменения затронут все пулы и операции. Действуйте с осторожностью."
            type="info"
            showIcon
            style={{ borderRadius: 8 }}
          />
          <Card
            className="sber-card"
            title={<span style={{ fontWeight: 600 }}>Расширенная конфигурация</span>}
            style={{ borderRadius: 12, border: '1px solid #E5E7EB' }}
            extra={<Tag color="red">Только SUPER_ADMIN</Tag>}
          >
            <Alert
              message="Интерфейс управления конфигурацией в разработке"
              description="Обратитесь к команде разработки для обновления параметров платформы через API."
              type="warning"
              showIcon
              style={{ borderRadius: 8 }}
            />
          </Card>
        </>
      )}
    </Space>
  )
}
