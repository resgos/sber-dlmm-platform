import { useState, useSyncExternalStore } from 'react'
import {
  Card,
  Space,
  Typography,
  Button,
  Alert,
  Steps,
  Input,
  Tag,
  Popconfirm,
  message,
  Modal,
} from 'antd'
import {
  SafetyOutlined,
  LockOutlined,
  CheckCircleFilled,
  ReloadOutlined,
  CopyOutlined,
} from '@ant-design/icons'
import { twoFactorStore, buildOtpauthUri, type TwoFactorState } from '@/store/twoFactorStore'
import { authStore } from '@/store/authStore'

const { Text, Paragraph } = Typography

/**
 * Sprint 11 G-20 — 2FA settings card on Profile page.
 *
 * Three states:
 *   1. Off → "Включить 2FA" button → opens setup wizard
 *   2. Setup wizard (3 steps: QR → verify code → recovery codes shown)
 *   3. On → status card + "Generate new recovery codes" + "Disable"
 *
 * Setup wizard is a Modal — keeps Profile card compact. Recovery codes
 * shown ONCE during setup; user must explicitly confirm they've saved
 * them before we close the modal (forced acknowledgement, защищает от
 * user'а который закроет тab не сохранив).
 */
export default function TwoFactorSettings() {
  const state = useSyncExternalStore(
    twoFactorStore.subscribe,
    twoFactorStore.get,
    () => ({ enabled: false, secret: null, enabledAt: null, recoveryCodes: [], recoveryCodesUsed: 0 } as TwoFactorState),
  )

  const [setupOpen, setSetupOpen] = useState(false)

  if (state.enabled) {
    return <EnabledCard state={state} />
  }

  return (
    <>
      <Card
        className="sber-card"
        title={
          <Space>
            <SafetyOutlined style={{ color: 'var(--text-secondary)' }} />
            <Text strong>Двухфакторная аутентификация (2FA)</Text>
          </Space>
        }
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="2FA не включена"
            description="Добавьте дополнительный слой защиты — приложение-аутентификатор (Google Authenticator, Microsoft Authenticator, Я.Ключ, и т.д.) будет генерировать одноразовый код для входа."
          />
          <Button type="primary" icon={<LockOutlined />} onClick={() => setSetupOpen(true)}>
            Включить 2FA
          </Button>
        </Space>
      </Card>

      <SetupWizard open={setupOpen} onClose={() => setSetupOpen(false)} />
    </>
  )
}

// ---------------------------------------------------------------------
// Enabled state — status + manage
// ---------------------------------------------------------------------

function EnabledCard({ state }: { state: TwoFactorState }) {
  const [regenOpen, setRegenOpen] = useState(false)
  const [newCodes, setNewCodes] = useState<string[]>([])

  const remaining = state.recoveryCodes.length - state.recoveryCodesUsed

  const handleRegenerate = () => {
    const codes = twoFactorStore.regenerateRecoveryCodes()
    setNewCodes(codes)
    setRegenOpen(true)
  }

  return (
    <Card
      className="sber-card"
      title={
        <Space>
          <SafetyOutlined style={{ color: 'var(--sber-green)' }} />
          <Text strong>Двухфакторная аутентификация (2FA)</Text>
        </Space>
      }
      extra={<Tag color="green" icon={<CheckCircleFilled />} style={{ borderRadius: 999 }}>включена</Tag>}
    >
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          type="success"
          showIcon
          message="2FA активна"
          description={
            <Space direction="vertical" size={2}>
              <Text style={{ fontSize: 13 }}>
                Активировано {state.enabledAt ? new Date(state.enabledAt).toLocaleString('ru-RU') : '—'}
              </Text>
              <Text style={{ fontSize: 13 }}>
                Резервных кодов осталось: <Text strong>{remaining} из {state.recoveryCodes.length}</Text>
              </Text>
            </Space>
          }
        />

        {remaining <= 3 && remaining > 0 && (
          <Alert
            type="warning"
            showIcon
            message="Заканчиваются резервные коды"
            description="Сгенерируйте новый набор — старые перестанут работать сразу."
          />
        )}

        <Space wrap>
          <Button icon={<ReloadOutlined />} onClick={handleRegenerate}>
            Новые резервные коды
          </Button>
          <Popconfirm
            title="Отключить 2FA?"
            description="Безопасность аккаунта снизится. Если кто-то получит ваш пароль — войдёт без второго фактора."
            okText="Отключить"
            okButtonProps={{ danger: true }}
            cancelText="Отмена"
            onConfirm={() => {
              twoFactorStore.disable()
              message.warning('2FA отключена')
            }}
          >
            <Button danger>Отключить 2FA</Button>
          </Popconfirm>
        </Space>

        <Text type="secondary" style={{ fontSize: 11 }}>
          На клиентской стороне (Sprint 11 MVP). Backend-валидация TOTP → Sprint 12: POST /api/v1/users/me/2fa/verify
          с HMAC-SHA1 проверкой кода. Текущая реализация принимает любой 6-значный код.
        </Text>
      </Space>

      <Modal
        title="Новые резервные коды"
        open={regenOpen}
        onCancel={() => { setRegenOpen(false); setNewCodes([]) }}
        onOk={() => { setRegenOpen(false); setNewCodes([]) }}
        okText="Сохранил коды"
        cancelButtonProps={{ style: { display: 'none' } }}
        closable={false}
        maskClosable={false}
      >
        <Alert
          type="warning"
          showIcon
          message="Сохраните эти коды СЕЙЧАС"
          description="Каждый код можно использовать только один раз. После закрытия этого окна — увидеть их снова нельзя."
          style={{ marginBottom: 12 }}
        />
        <RecoveryCodesGrid codes={newCodes} />
      </Modal>
    </Card>
  )
}

// ---------------------------------------------------------------------
// Setup wizard
// ---------------------------------------------------------------------

function SetupWizard({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [step, setStep] = useState<0 | 1 | 2>(0)
  const [secret, setSecret] = useState<string | null>(null)
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([])
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [acknowledged, setAcknowledged] = useState(false)

  const user = authStore.getUser()
  const account = user?.email ?? 'demo@sber.ru'

  // Lazy-init secret on first open of step 0.
  if (open && step === 0 && !secret) {
    const { secret: s, recoveryCodes: rc } = twoFactorStore.beginSetup()
    setSecret(s)
    setRecoveryCodes(rc)
  }

  const handleVerify = () => {
    if (!secret) return
    const ok = twoFactorStore.enable(secret, recoveryCodes, code)
    if (ok) {
      setError(null)
      setStep(2)
    } else {
      setError('Введите 6 цифр из приложения-аутентификатора')
    }
  }

  const handleClose = () => {
    if (step === 2 && !acknowledged) {
      message.warning('Сохраните резервные коды и подтвердите внизу окна')
      return
    }
    setStep(0)
    setSecret(null)
    setRecoveryCodes([])
    setCode('')
    setError(null)
    setAcknowledged(false)
    onClose()
  }

  return (
    <Modal
      title="Включение 2FA"
      open={open}
      onCancel={handleClose}
      footer={null}
      width={520}
      destroyOnClose
    >
      <Steps
        current={step}
        items={[
          { title: 'Сканировать' },
          { title: 'Проверить код' },
          { title: 'Резервные коды' },
        ]}
        style={{ marginBottom: 20 }}
      />

      {step === 0 && secret && (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Paragraph>
            Откройте Google Authenticator, Microsoft Authenticator, Я.Ключ или другое TOTP-приложение
            и отсканируйте QR-код или введите секрет вручную:
          </Paragraph>

          {/* QR placeholder. Real QR rendering needs a tiny library
              (qrcode.js ~16KB) — Sprint 12 swap-in. For now, show the
              otpauth:// URI as копируемый текст; пользователь может
              использовать "ввести вручную" режим в приложении. */}
          <Alert
            type="info"
            message="QR-код"
            description={
              <Space direction="vertical" size={4} style={{ width: '100%' }}>
                <Text type="secondary" style={{ fontSize: 12 }}>
                  Графический QR — в Sprint 12. Пока — копируйте URI и используйте «ввести вручную» в приложении.
                </Text>
                <CopyableBlock value={buildOtpauthUri(account, secret)} fontSize={11} />
              </Space>
            }
          />

          <Space direction="vertical" size={4} style={{ width: '100%' }}>
            <Text strong>Или введите секрет вручную:</Text>
            <CopyableBlock value={secret} fontSize={14} />
            <Text type="secondary" style={{ fontSize: 11 }}>
              Аккаунт: <Text code>{account}</Text>; алгоритм: SHA1; цифры: 6; период: 30 сек.
            </Text>
          </Space>

          <Button type="primary" onClick={() => setStep(1)} block>
            Я добавил аккаунт в приложение — далее
          </Button>
        </Space>
      )}

      {step === 1 && (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Paragraph>
            Введите 6-значный код из приложения-аутентификатора, чтобы подтвердить настройку.
          </Paragraph>
          <Input
            placeholder="000000"
            value={code}
            onChange={(e) => { setCode(e.target.value.replace(/\D/g, '').slice(0, 6)); setError(null) }}
            size="large"
            style={{ fontSize: 22, fontVariantNumeric: 'tabular-nums', textAlign: 'center', letterSpacing: 6 }}
            maxLength={6}
            onPressEnter={handleVerify}
            autoFocus
          />
          {error && <Alert type="error" message={error} showIcon />}
          <Space style={{ width: '100%', justifyContent: 'space-between' }}>
            <Button onClick={() => setStep(0)}>Назад</Button>
            <Button type="primary" disabled={code.length !== 6} onClick={handleVerify}>
              Подтвердить
            </Button>
          </Space>
          <Text type="secondary" style={{ fontSize: 11 }}>
            MVP: принимаем любой 6-значный код. Реальная TOTP-валидация — Sprint 12 backend.
          </Text>
        </Space>
      )}

      {step === 2 && (
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          <Alert
            type="warning"
            showIcon
            message="Сохраните резервные коды СЕЙЧАС"
            description="Эти 10 кодов помогут войти, если потеряете доступ к приложению-аутентификатору. Каждый код можно использовать только один раз. После закрытия этого окна — увидеть их снова нельзя."
          />
          <RecoveryCodesGrid codes={recoveryCodes} />
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: 'pointer' }}>
            <input
              type="checkbox"
              checked={acknowledged}
              onChange={(e) => setAcknowledged(e.target.checked)}
            />
            <Text>Я сохранил резервные коды в безопасное место</Text>
          </label>
          <Button type="primary" block disabled={!acknowledged} onClick={handleClose}>
            Готово
          </Button>
        </Space>
      )}
    </Modal>
  )
}

// ---------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------

function CopyableBlock({ value, fontSize = 14 }: { value: string; fontSize?: number }) {
  return (
    <div
      style={{
        padding: '8px 10px',
        background: 'var(--surface-2)',
        borderRadius: 6,
        fontFamily: 'JetBrains Mono, ui-monospace, monospace',
        fontSize,
        wordBreak: 'break-all',
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        gap: 8,
      }}
    >
      <span style={{ flex: 1 }}>{value}</span>
      <Button
        size="small"
        type="text"
        icon={<CopyOutlined />}
        onClick={async () => {
          try {
            await navigator.clipboard.writeText(value)
            message.success('Скопировано')
          } catch {
            // Clipboard API недоступен — fallback: select & ask user.
            // eslint-disable-next-line no-alert
            window.prompt('Скопируйте вручную:', value)
          }
        }}
        aria-label="Скопировать"
      />
    </div>
  )
}

function RecoveryCodesGrid({ codes }: { codes: ReadonlyArray<string> }) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(2, 1fr)',
        gap: 6,
        padding: 12,
        background: 'var(--surface-1)',
        borderRadius: 8,
        fontFamily: 'JetBrains Mono, ui-monospace, monospace',
        fontSize: 13,
      }}
    >
      {codes.map((c, i) => (
        <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          <Text type="secondary" style={{ fontSize: 10, minWidth: 18 }}>{i + 1}.</Text>
          <Text>{c}</Text>
        </div>
      ))}
    </div>
  )
}
