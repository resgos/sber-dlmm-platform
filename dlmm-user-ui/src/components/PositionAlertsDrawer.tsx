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
  Tabs,
} from 'antd'
import {
  BellOutlined,
  PlusOutlined,
  DeleteOutlined,
  WarningFilled,
  ExperimentOutlined,
  HistoryOutlined,
} from '@ant-design/icons'
import {
  positionAlertsStore,
  alertHistoryStore,
  type AlertType,
  type PositionAlert,
} from '@/store/positionAlertsStore'
import { fireTestAlert } from '@/lib/usePositionAlertWatcher'
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
  const history = useSyncExternalStore(
    alertHistoryStore.subscribe,
    alertHistoryStore.list,
    () => [] as ReturnType<typeof alertHistoryStore.list>,
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

  // Sprint 10 wave 3 — when permission is denied or unavailable, we
  // still want the user to be able to FIX it. The Notification
  // browser API doesn't let pages re-prompt after denial — only the
  // user can grant it via site settings. We render an actionable
  // banner with a step-by-step hint instead of just a passive warning.
  const permissionBanner = notifPermission === 'denied' ? (
    <Alert
      type="warning"
      showIcon
      icon={<WarningFilled />}
      message="Браузерные уведомления заблокированы"
      description={
        <Space direction="vertical" size={4}>
          <Text style={{ fontSize: 'var(--text-xs)' }}>
            Алерты всё равно покажутся внутри страницы (правый верхний угол), но звуковых браузерных
            уведомлений не будет даже если вкладка скрыта.
          </Text>
          <Text style={{ fontSize: 'var(--text-xs)' }} type="secondary">
            Чтобы включить: нажмите 🔒 / ⓘ слева от адресной строки → «Уведомления» → «Разрешить» → обновите страницу.
          </Text>
        </Space>
      }
    />
  ) : notifPermission === 'default' ? (
    <Alert
      type="info"
      showIcon
      message="Разрешение на уведомления не запрошено"
      description="Браузер спросит разрешение в момент первого срабатывания алерта. Можно нажать «Тест» рядом с любым правилом, чтобы спросить заранее."
    />
  ) : null

  return (
    <Drawer
      title={<Space><BellOutlined /><Text strong>Оповещения по позициям</Text></Space>}
      open={open}
      onClose={onClose}
      width={Math.min(window.innerWidth * 0.9, 480)}
      destroyOnClose
    >
      <Tabs
        defaultActiveKey="rules"
        items={[
          {
            key: 'rules',
            label: <Space size={6}><BellOutlined />Правила {alerts.length > 0 && <Tag style={{ marginInlineStart: 0, borderRadius: 'var(--radius-pill)' }}>{alerts.length}</Tag>}</Space>,
            children: (
              <Space direction="vertical" size={16} style={{ width: '100%' }}>
                {permissionBanner}

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
                    borderRadius: 'var(--radius-sm)',
                    background: a.active ? 'var(--bg-card)' : 'var(--surface-1)',
                    opacity: stale ? 0.55 : 1,
                  }}
                >
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', gap: 8 }}>
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <Space size={6} wrap>
                        <Tag color={a.active ? 'green' : 'default'} style={{ borderRadius: 'var(--radius-pill)' }}>{opt?.label}</Tag>
                        {stale && <Tag color="default" style={{ borderRadius: 'var(--radius-pill)' }}>позиция закрыта</Tag>}
                      </Space>
                      <div style={{ marginTop: 4, fontSize: 'var(--text-sm)', fontWeight: 500 }}>{a.label}</div>
                      <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginTop: 2 }}>
                        {positionLabel(a.positionId)}
                      </div>
                      {a.lastFiredAt && (
                        <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                          Последнее срабатывание: {new Date(a.lastFiredAt).toLocaleString('ru-RU')}
                        </Text>
                      )}
                    </div>
                    <Space size={6}>
                      {/* Sprint 10 wave 3 — Test button. Fires a fake
                          alert so the user can verify the notification
                          + permission flow without waiting for a real
                          event. */}
                      <Tooltip title="Тестовое срабатывание — поможет убедиться, что уведомления работают">
                        <Button
                          size="small"
                          type="text"
                          icon={<ExperimentOutlined />}
                          aria-label="Тестовое срабатывание"
                          onClick={() => fireTestAlert(a)}
                        />
                      </Tooltip>
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
          <div style={{ padding: 14, border: '1px solid var(--border-light)', borderRadius: 'var(--radius-sm)' }}>
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
                      <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>{o.help}</Text>
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
            ),
          },
          {
            // Sprint 10 wave 3 — single "what fired and when" view
            // across all rules. The per-rule `lastFiredAt` shown
            // in-line on Rules tab is fine for "is this rule alive?";
            // this tab answers "what happened recently?".
            key: 'history',
            label: <Space size={6}><HistoryOutlined />История {history.length > 0 && <Tag style={{ marginInlineStart: 0, borderRadius: 'var(--radius-pill)' }}>{history.length}</Tag>}</Space>,
            children: (
              <Space direction="vertical" size={10} style={{ width: '100%' }}>
                {history.length === 0 ? (
                  <Empty
                    description="Срабатываний пока не было"
                    image={Empty.PRESENTED_IMAGE_SIMPLE}
                  />
                ) : (
                  <>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                      <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                        Последние {history.length} срабатываний (хранятся в памяти вкладки)
                      </Text>
                      <Button size="small" type="text" onClick={() => alertHistoryStore.clear()}>
                        Очистить
                      </Button>
                    </div>
                    {history.map((h, i) => (
                      <div
                        key={i}
                        style={{
                          padding: 10,
                          border: '1px solid var(--border-light)',
                          borderRadius: 'var(--radius-sm)',
                          background: 'var(--bg-card)',
                        }}
                      >
                        <Space size={6} wrap style={{ marginBottom: 4 }}>
                          <Tag color="orange" style={{ borderRadius: 'var(--radius-pill)' }}>{h.alertType}</Tag>
                          <Tag style={{ borderRadius: 'var(--radius-pill)' }}>{h.delivery === 'browser' ? 'браузер' : 'в приложении'}</Tag>
                          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
                            {new Date(h.firedAt).toLocaleString('ru-RU')}
                          </Text>
                        </Space>
                        <div style={{ fontSize: 'var(--text-sm)', fontWeight: 500 }}>{h.alertLabel}</div>
                        <div style={{ fontSize: 'var(--text-xs)', color: 'var(--text-secondary)', marginTop: 2 }}>
                          {h.message}
                        </div>
                      </div>
                    ))}
                  </>
                )}
              </Space>
            ),
          },
        ]}
      />
    </Drawer>
  )
}
