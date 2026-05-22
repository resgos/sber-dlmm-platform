import { useState, useSyncExternalStore } from 'react'
import {
  Drawer,
  Button,
  Space,
  Typography,
  Empty,
  Tag,
  Tooltip,
  Form,
  Select,
  InputNumber,
  Input,
  Alert,
  Popconfirm,
  Switch,
} from 'antd'
import {
  BellOutlined,
  PlusOutlined,
  DeleteOutlined,
  WarningFilled,
} from '@ant-design/icons'
import {
  positionAlertsStore,
  type AlertType,
  type PositionAlert,
} from '@/store/positionAlertsStore'
import type { Position } from '@/api/types'

const { Text } = Typography

interface Props {
  open: boolean
  onClose(): void
  /** Positions the user can attach alerts to — restricted to the
   *  active ones from PositionsPage. Closed positions are excluded
   *  because there's nothing to watch. */
  positions: Position[]
}

const ALERT_TYPE_OPTIONS: Array<{ value: AlertType; label: string; help: string }> = [
  { value: 'OUT_OF_RANGE',  label: 'Выход из диапазона', help: 'Активный бин пула вышел за пределы вашей позиции' },
  { value: 'FEES_THRESHOLD', label: 'Накопились комиссии', help: 'Незабранные комиссии превысили порог' },
  { value: 'VALUE_DROP',    label: 'Падение стоимости',   help: 'Стоимость позиции упала на X% от начальной' },
]

/**
 * Sprint 10 (new feature) — drawer with the position-alerts CRUD.
 *
 * Opened from PositionsPage via a bell button. Lists existing rules
 * with on/off + delete; a "+" button opens the create form inline.
 *
 * Permission story: the browser asks for Notification permission only
 * when the first alert fires (lazy ask). The drawer surfaces the
 * permission state as a banner so the user knows whether their rule
 * will result in a visible alert.
 */
export default function PositionAlertsDrawer({ open, onClose, positions }: Props) {
  const alerts = useSyncExternalStore(
    positionAlertsStore.subscribe,
    positionAlertsStore.list,
    () => [] as PositionAlert[],
  )

  const [showForm, setShowForm] = useState(false)
  const [form] = Form.useForm<{
    positionId: string
    type: AlertType
    threshold: number
    label: string
  }>()

  const positionLabel = (id: string): string => {
    const p = positions.find((x) => x.id === id)
    return p ? `${p.tokenXSymbol}/${p.tokenYSymbol} · бины ${p.binRangeMin}–${p.binRangeMax}` : `позиция ${id.slice(0, 6)}…`
  }

  const handleAdd = async (): Promise<void> => {
    const values = await form.validateFields()
    positionAlertsStore.add({
      positionId: values.positionId,
      type: values.type,
      threshold: values.threshold || 0,
      label: values.label || ALERT_TYPE_OPTIONS.find((o) => o.value === values.type)!.label,
    })
    form.resetFields()
    setShowForm(false)
  }

  const notifPermission = typeof Notification !== 'undefined' ? Notification.permission : 'denied'

  return (
    <Drawer
      title={<Space><BellOutlined /><Text strong>Оповещения по позициям</Text></Space>}
      open={open}
      onClose={onClose}
      width={Math.min(window.innerWidth * 0.9, 480)}
      destroyOnClose
    >
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {notifPermission === 'denied' && (
          <Alert
            type="warning"
            showIcon
            icon={<WarningFilled />}
            message="Уведомления заблокированы в браузере"
            description="Правила всё равно вычисляются, но всплывающие сообщения показаны не будут. Разрешите уведомления в настройках сайта, чтобы получать алерты."
          />
        )}

        {alerts.length === 0 && !showForm && (
          <Empty
            description="Алертов пока нет"
            image={Empty.PRESENTED_IMAGE_SIMPLE}
          >
            <Button type="primary" icon={<PlusOutlined />} onClick={() => setShowForm(true)}>
              Создать первый алерт
            </Button>
          </Empty>
        )}

        {alerts.length > 0 && (
          <Space direction="vertical" size={10} style={{ width: '100%' }}>
            {alerts.map((a) => {
              const opt = ALERT_TYPE_OPTIONS.find((o) => o.value === a.type)
              const stale = !positions.some((p) => p.id === a.positionId)
              return (
                <div
                  key={a.id}
                  style={{
                    padding: 12,
                    border: '1px solid var(--border-light)',
                    borderRadius: 8,
                    background: a.active ? 'var(--bg-card)' : 'var(--surface-1)',
                    opacity: stale ? 0.55 : 1,
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <Space size={6} wrap>
                        <Tag color={a.active ? 'green' : 'default'} style={{ borderRadius: 999 }}>{opt?.label}</Tag>
                        {stale && <Tag color="default" style={{ borderRadius: 999 }}>позиция закрыта</Tag>}
                      </Space>
                      <div style={{ marginTop: 4, fontSize: 13, fontWeight: 500 }}>{a.label}</div>
                      <div style={{ fontSize: 11, color: 'var(--text-secondary)', marginTop: 2 }}>
                        {positionLabel(a.positionId)}
                      </div>
                      {a.lastFiredAt && (
                        <Text type="secondary" style={{ fontSize: 11 }}>
                          Последнее срабатывание: {new Date(a.lastFiredAt).toLocaleString('ru-RU')}
                        </Text>
                      )}
                    </div>
                    <Space size={6}>
                      <Tooltip title={a.active ? 'Отключить' : 'Включить'}>
                        <Switch
                          size="small"
                          checked={a.active}
                          onChange={() => positionAlertsStore.toggle(a.id)}
                        />
                      </Tooltip>
                      <Popconfirm
                        title="Удалить алерт?"
                        onConfirm={() => positionAlertsStore.remove(a.id)}
                        okText="Удалить"
                        cancelText="Отмена"
                      >
                        <Button danger size="small" type="text" icon={<DeleteOutlined />} aria-label="Удалить алерт" />
                      </Popconfirm>
                    </Space>
                  </div>
                </div>
              )
            })}
            {!showForm && (
              <Button type="dashed" block icon={<PlusOutlined />} onClick={() => setShowForm(true)}>
                Добавить алерт
              </Button>
            )}
          </Space>
        )}

        {showForm && (
          <div style={{ padding: 14, border: '1px solid var(--border-light)', borderRadius: 8 }}>
            <Form form={form} layout="vertical" requiredMark={false}>
              <Form.Item
                name="positionId"
                label="Позиция"
                rules={[{ required: true, message: 'Выберите позицию' }]}
              >
                <Select
                  placeholder="Выберите позицию"
                  options={positions.map((p) => ({
                    value: p.id,
                    label: `${p.tokenXSymbol}/${p.tokenYSymbol} (бины ${p.binRangeMin}–${p.binRangeMax})`,
                  }))}
                />
              </Form.Item>
              <Form.Item
                name="type"
                label="Тип алерта"
                rules={[{ required: true, message: 'Выберите тип' }]}
                initialValue="OUT_OF_RANGE"
              >
                <Select
                  options={ALERT_TYPE_OPTIONS.map((o) => ({
                    value: o.value,
                    label: <Space direction="vertical" size={0}>
                      <Text>{o.label}</Text>
                      <Text type="secondary" style={{ fontSize: 11 }}>{o.help}</Text>
                    </Space>,
                  }))}
                  onChange={(v) => {
                    // OUT_OF_RANGE doesn't need a threshold, clear it.
                    if (v === 'OUT_OF_RANGE') form.setFieldValue('threshold', 0)
                  }}
                />
              </Form.Item>
              <Form.Item
                noStyle
                shouldUpdate={(prev, cur) => prev.type !== cur.type}
              >
                {() => {
                  const type = form.getFieldValue('type')
                  if (type === 'OUT_OF_RANGE') return null
                  const isPct = type === 'VALUE_DROP'
                  return (
                    <Form.Item
                      name="threshold"
                      label={isPct ? 'Порог падения, %' : 'Порог комиссий, ₽'}
                      rules={[{ required: true, message: 'Укажите порог' }]}
                    >
                      <InputNumber
                        style={{ width: '100%' }}
                        min={0}
                        max={isPct ? 100 : 1_000_000_000}
                        step={isPct ? 1 : 100}
                      />
                    </Form.Item>
                  )
                }}
              </Form.Item>
              <Form.Item name="label" label="Метка (необязательно)">
                <Input placeholder="Например, «Топ-позиция SBER»" maxLength={48} />
              </Form.Item>
              <Space>
                <Button type="primary" onClick={handleAdd}>Сохранить</Button>
                <Button onClick={() => { form.resetFields(); setShowForm(false) }}>Отмена</Button>
              </Space>
            </Form>
          </div>
        )}
      </Space>
    </Drawer>
  )
}
