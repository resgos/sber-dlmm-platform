import { useState, useSyncExternalStore } from 'react'
import {
  Card,
  Space,
  Typography,
  Table,
  Tag,
  Button,
  Modal,
  Form,
  Input,
  Select,
  Tooltip,
  Empty,
  Alert,
  Popconfirm,
  message,
} from 'antd'
import {
  TeamOutlined,
  UserAddOutlined,
  DeleteOutlined,
  CheckCircleOutlined,
} from '@ant-design/icons'
import { teamStore, ROLE_LABELS, ROLE_HINTS, canPerform, type TeamMember, type TeamRole } from '@/store/teamStore'
import { authStore } from '@/store/authStore'

const { Title, Text, Paragraph } = Typography

/**
 * Sprint 11 G-21 — Team / multi-user management.
 *
 * Dmitry-driven (medium-business): три фин-менеджера, audit log с
 * user-attribution требует регулятор, общий аккаунт неприемлем.
 *
 * Frontend MVP scope: создание организации, приглашения, role
 * management, audit-friendly member list. Backend swap-in Sprint 12:
 *   - POST /api/v1/orgs/{id}/members (invite)
 *   - DELETE /api/v1/orgs/{id}/members/{userId} (remove)
 *   - PATCH /api/v1/orgs/{id}/members/{userId}/role (change)
 *   - Gateway permission middleware reads X-Member-Role header
 *     populated from JWT и проверяет vs route policy.
 */
export default function TeamPage() {
  const state = useSyncExternalStore(
    teamStore.subscribe,
    teamStore.get,
    () => ({ orgName: '', members: [], ownerId: '', createdAt: '' }),
  )

  const me = authStore.getUser()
  const myMember = state.members.find((m) => m.email === me?.email)
  const myRole: TeamRole = myMember?.role ?? 'OWNER' // first-time visitor will be the OWNER on creation

  const [createOpen, setCreateOpen] = useState(false)
  const [inviteOpen, setInviteOpen] = useState(false)

  // No org yet → onboarding card.
  if (!state.orgName) {
    return (
      <Space direction="vertical" size={24} style={{ width: '100%' }}>
        <div>
          <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>
            <TeamOutlined style={{ marginRight: 8, color: 'var(--sber-green)' }} />
            Команда
          </Title>
          <Text type="secondary">
            Пригласите фин-менеджеров, бухгалтеров и аудиторов работать с одним юр-лицом.
            Каждое действие приписывается конкретному пользователю — audit log готов к регуляторной проверке.
          </Text>
        </div>

        <Card className="sber-card">
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <Space direction="vertical" size={6} align="center">
                <Text strong>У вас пока нет организации</Text>
                <Text type="secondary" style={{ fontSize: 13 }}>
                  Создайте — и сможете приглашать членов команды с разными ролями.
                </Text>
              </Space>
            }
          >
            <Button type="primary" icon={<TeamOutlined />} onClick={() => setCreateOpen(true)}>
              Создать организацию
            </Button>
          </Empty>
        </Card>

        <CreateOrgModal
          open={createOpen}
          onClose={() => setCreateOpen(false)}
          defaultEmail={me?.email ?? ''}
        />
      </Space>
    )
  }

  const columns = [
    {
      title: 'Имя',
      dataIndex: 'name',
      render: (n: string, r: TeamMember) => (
        <Space size={6}>
          <Text strong>{n}</Text>
          {r.id === state.ownerId && <Tag color="gold" style={{ borderRadius: 999 }}>OWNER</Tag>}
          {r.email === me?.email && <Tag color="blue" style={{ borderRadius: 999 }}>это вы</Tag>}
        </Space>
      ),
    },
    {
      title: 'Email',
      dataIndex: 'email',
      render: (e: string) => <Text style={{ fontFamily: 'JetBrains Mono, monospace', fontSize: 12 }}>{e}</Text>,
    },
    {
      title: 'Роль',
      key: 'role',
      render: (_: unknown, r: TeamMember) => {
        const isOwner = r.id === state.ownerId
        if (isOwner || !canPerform(myRole, 'CHANGE_ROLE')) {
          return (
            <Tooltip title={ROLE_HINTS[r.role]}>
              <Tag color={roleColor(r.role)} style={{ borderRadius: 999 }}>
                {ROLE_LABELS[r.role]}
              </Tag>
            </Tooltip>
          )
        }
        return (
          <Select
            size="small"
            value={r.role}
            style={{ minWidth: 180 }}
            options={(['FINANCE_MGR', 'ACCOUNTANT', 'AUDITOR', 'VIEWER'] as TeamRole[]).map((role) => ({
              value: role,
              label: <Space direction="vertical" size={0}>
                <Text>{ROLE_LABELS[role]}</Text>
                <Text type="secondary" style={{ fontSize: 10 }}>{ROLE_HINTS[role]}</Text>
              </Space>,
            }))}
            onChange={(v) => {
              if (teamStore.changeRole(r.id, v as TeamRole)) {
                message.success(`Роль ${r.name} изменена на «${ROLE_LABELS[v as TeamRole]}»`)
              }
            }}
          />
        )
      },
    },
    {
      title: 'Статус',
      dataIndex: 'status',
      render: (s: TeamMember['status'], r: TeamMember) => {
        if (s === 'ACTIVE') return <Tag color="green" icon={<CheckCircleOutlined />} style={{ borderRadius: 999 }}>активен</Tag>
        return (
          <Space size={4}>
            <Tag color="default" style={{ borderRadius: 999 }}>ожидает</Tag>
            <Button
              size="small"
              type="link"
              onClick={() => {
                teamStore.acceptInvite(r.id)
                message.info(`(имитация) ${r.name} принял приглашение`)
              }}
            >
              Имитировать принятие
            </Button>
          </Space>
        )
      },
    },
    {
      title: 'Присоединился',
      dataIndex: 'joinedAt',
      render: (d: string) => (
        <Text type="secondary" style={{ fontSize: 12 }}>
          {new Date(d).toLocaleDateString('ru-RU')}
        </Text>
      ),
    },
    {
      title: '',
      key: 'actions',
      width: 60,
      render: (_: unknown, r: TeamMember) => {
        if (r.role === 'OWNER' || !canPerform(myRole, 'REMOVE_MEMBER')) return null
        return (
          <Popconfirm
            title={`Удалить ${r.name} из организации?`}
            description="После удаления пользователь потеряет доступ ко всем операциям организации. Audit log по его предыдущим действиям сохраняется."
            okText="Удалить"
            okButtonProps={{ danger: true }}
            cancelText="Отмена"
            onConfirm={() => {
              if (teamStore.remove(r.id)) {
                message.success(`${r.name} удалён`)
              }
            }}
          >
            <Button danger type="text" size="small" icon={<DeleteOutlined />} aria-label="Удалить" />
          </Popconfirm>
        )
      },
    },
  ]

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>
            <TeamOutlined style={{ marginRight: 8, color: 'var(--sber-green)' }} />
            Команда «{state.orgName}»
          </Title>
          <Text type="secondary">
            {state.members.length} {state.members.length === 1 ? 'участник' : state.members.length < 5 ? 'участника' : 'участников'}
            {' · '}создано {new Date(state.createdAt).toLocaleDateString('ru-RU')}
          </Text>
        </div>
        {canPerform(myRole, 'INVITE_MEMBER') && (
          <Button type="primary" icon={<UserAddOutlined />} onClick={() => setInviteOpen(true)}>
            Пригласить
          </Button>
        )}
      </div>

      <Alert
        type="info"
        showIcon
        message="Audit log с user-attribution"
        description="Каждое действие любого члена команды (открытие позиции, своп, изменение настроек) фиксируется с указанием конкретного пользователя. Это видно в Транзакциях и используется для compliance-проверок."
      />

      <Card className="sber-card">
        <Table
          dataSource={state.members}
          columns={columns}
          rowKey="id"
          pagination={false}
          size="middle"
        />
      </Card>

      <Card className="sber-card" size="small">
        <Text type="secondary" style={{ fontSize: 12 }}>
          <strong>Frontend MVP (Sprint 11):</strong> состояние команды хранится локально в браузере владельца.
          Backend-версия (Sprint 12) — POST /api/v1/orgs/{`{id}`}/members + permission middleware на gateway —
          разнесёт состояние между всеми членами команды и обеспечит cross-device sync.
        </Text>
      </Card>

      <InviteMemberModal open={inviteOpen} onClose={() => setInviteOpen(false)} />
    </Space>
  )
}

function roleColor(role: TeamRole): string {
  return {
    OWNER: 'gold',
    FINANCE_MGR: 'green',
    ACCOUNTANT: 'blue',
    AUDITOR: 'purple',
    VIEWER: 'default',
  }[role]
}

function CreateOrgModal({ open, onClose, defaultEmail }: { open: boolean; onClose: () => void; defaultEmail: string }) {
  const [form] = Form.useForm<{ orgName: string; ownerName: string }>()
  return (
    <Modal
      title="Создание организации"
      open={open}
      onCancel={onClose}
      onOk={async () => {
        const v = await form.validateFields()
        teamStore.create(v.orgName, defaultEmail, v.ownerName)
        message.success(`Организация «${v.orgName}» создана. Вы — Владелец.`)
        form.resetFields()
        onClose()
      }}
      okText="Создать"
      cancelText="Отмена"
      destroyOnClose
    >
      <Form form={form} layout="vertical">
        <Form.Item
          name="orgName"
          label="Название организации"
          rules={[{ required: true, message: 'Введите название' }, { max: 80 }]}
        >
          <Input placeholder="Транспортхолдинг, ИП Иванов, ООО «Контур»…" />
        </Form.Item>
        <Form.Item
          name="ownerName"
          label="Ваше имя"
          rules={[{ required: true, message: 'Введите ваше имя' }]}
        >
          <Input placeholder="Имя Фамилия" />
        </Form.Item>
        <Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
          Email-владельца: <Text code>{defaultEmail}</Text> (взят из вашего профиля)
        </Paragraph>
      </Form>
    </Modal>
  )
}

function InviteMemberModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [form] = Form.useForm<{ email: string; name: string; role: TeamRole }>()
  return (
    <Modal
      title="Пригласить участника"
      open={open}
      onCancel={onClose}
      onOk={async () => {
        const v = await form.validateFields()
        const m = teamStore.invite(v.email, v.name, v.role)
        if (!m) {
          message.error('Пользователь с этим email уже в команде')
          return
        }
        message.success(`${v.name} приглашён как «${ROLE_LABELS[v.role]}». Письмо отправлено (имитация).`)
        form.resetFields()
        onClose()
      }}
      okText="Отправить приглашение"
      cancelText="Отмена"
      destroyOnClose
    >
      <Form form={form} layout="vertical" initialValues={{ role: 'FINANCE_MGR' }}>
        <Form.Item name="name" label="Имя" rules={[{ required: true }]}>
          <Input placeholder="Иван Петров" />
        </Form.Item>
        <Form.Item
          name="email"
          label="Email"
          rules={[{ required: true }, { type: 'email', message: 'Невалидный email' }]}
        >
          <Input placeholder="ivan@company.ru" />
        </Form.Item>
        <Form.Item name="role" label="Роль" rules={[{ required: true }]}>
          <Select
            options={(['FINANCE_MGR', 'ACCOUNTANT', 'AUDITOR', 'VIEWER'] as TeamRole[]).map((role) => ({
              value: role,
              label: <Space direction="vertical" size={0}>
                <Text>{ROLE_LABELS[role]}</Text>
                <Text type="secondary" style={{ fontSize: 11 }}>{ROLE_HINTS[role]}</Text>
              </Space>,
            }))}
          />
        </Form.Item>
      </Form>
    </Modal>
  )
}
