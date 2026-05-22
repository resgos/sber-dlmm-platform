import { useState } from 'react'
import { Card, Upload, Button, Space, Typography, Tag, Alert, message, Popconfirm } from 'antd'
import { InboxOutlined, FileTextOutlined, SafetyCertificateOutlined, ReloadOutlined } from '@ant-design/icons'
import type { UploadFile, UploadProps } from 'antd/es/upload/interface'

const { Text, Paragraph } = Typography
const { Dragger } = Upload

type KycStatus = 'NOT_SUBMITTED' | 'PENDING' | 'VERIFIED' | 'REJECTED'

interface KycUploadPanelProps {
  /** Current KYC status — drives whether the upload Dragger is shown. */
  kycStatus: KycStatus
  /** Sprint 10 F-21 — surface the admin-supplied rejection reason on
   *  REJECTED so the user knows what to fix on resubmit. Optional
   *  because the backend contract is still in flux (admin UI already
   *  collects the reason via the audit-log REJECT action). */
  rejectionReason?: string
}

const ACCEPTED_MIME = ['image/jpeg', 'image/png', 'image/heic', 'application/pdf']
const MAX_SIZE_MB = 10

// Sprint 10 F-21 — soft client-side cooldown between submissions.
// Prevents spam clicks; the real rate-limit will live in the gateway
// when the Sber ID endpoint lands. localStorage key is per-browser.
const RESUBMIT_COOLDOWN_KEY = 'dlmm.user.kycLastSubmittedAt'
const RESUBMIT_COOLDOWN_MS = 60 * 60 * 1000 // 1h — generous; tightens to 24h once backend rate-limit ships

interface RequiredDocument {
  key: string
  label: string
  hint: string
}

const REQUIRED_DOCS: RequiredDocument[] = [
  { key: 'passport-main', label: 'Паспорт — главный разворот',  hint: 'Страница 2–3, фото и ФИО полностью видны' },
  { key: 'passport-registration', label: 'Паспорт — страница с регистрацией', hint: 'Страница 5, актуальный адрес' },
  { key: 'selfie', label: 'Селфи с паспортом', hint: 'Лицо и страница с фото в одном кадре' },
]

/**
 * Sprint 9-DS-r4 P2-8 + Sprint 10 F-21 — KYC document upload.
 *
 * Stub backend integration. Sber ID's OCR + face-match contract isn't
 * published on this branch — the UX shell is wired so when the contract
 * lands in Sprint 11, only `handleSubmit` flips from client-side stub
 * to a real POST /api/v1/users/kyc/submit.
 *
 * F-21 (this revision) — self-service re-verification:
 *   - REJECTED users see the admin-supplied rejection reason at the
 *     top of the Dragger so they know what to fix.
 *   - VERIFIED users get a "Запросить переверификацию" button (for
 *     expired docs / changed personal info) that flips the local
 *     panel into the Dragger flow.
 *   - 1h soft cooldown between submissions (localStorage timestamp)
 *     prevents spam; real rate-limit ships with the backend.
 *
 * Today's "submit" still just message.success()s + flips local state
 * to PENDING. No file actually leaves the browser.
 */
export default function KycUploadPanel({ kycStatus, rejectionReason }: KycUploadPanelProps) {
  const [filesByDoc, setFilesByDoc] = useState<Record<string, UploadFile[]>>({})
  const [submitted, setSubmitted] = useState(false)
  // F-21 — VERIFIED user clicked "Запросить переверификацию" → render
  // the Dragger anyway. Local-only; refresh resets to the server-truth.
  const [reverifyMode, setReverifyMode] = useState(false)

  const renderVerifiedCard = (): JSX.Element => (
    <Card className="sber-card" title={<Text strong>Документы KYC</Text>}>
      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Alert
          message="Документы приняты и верифицированы"
          description="Если ваши документы устарели или изменились личные данные, вы можете запросить переверификацию."
          type="success"
          showIcon
        />
        <Popconfirm
          title="Запросить переверификацию?"
          description="Текущая верификация останется активной до тех пор, пока новые документы не будут одобрены. Платформенные операции прерывать не нужно."
          onConfirm={() => setReverifyMode(true)}
          okText="Продолжить"
          cancelText="Отмена"
        >
          <Button icon={<ReloadOutlined />} type="default">
            Запросить переверификацию
          </Button>
        </Popconfirm>
      </Space>
    </Card>
  )

  const renderPendingCard = (): JSX.Element => (
    <Card className="sber-card" title={<Text strong>Документы KYC</Text>}>
      <Alert
        message="Документы на рассмотрении"
        description="Обычно проверка занимает 1–2 рабочих дня. После одобрения вам будут доступны все функции платформы."
        type="info"
        showIcon
      />
    </Card>
  )

  // Already-verified and not asking for re-verification → exit early.
  if (kycStatus === 'VERIFIED' && !reverifyMode) {
    return renderVerifiedCard()
  }
  if (kycStatus === 'PENDING' || submitted) {
    return renderPendingCard()
  }

  // F-21 — check soft cooldown before letting them submit.
  const lastSubmittedAt = readLastSubmittedAt()
  const cooldownRemainingMs = lastSubmittedAt
    ? Math.max(0, RESUBMIT_COOLDOWN_MS - (Date.now() - lastSubmittedAt))
    : 0
  const onCooldown = cooldownRemainingMs > 0

  const handleSubmit = (): void => {
    const submittedDocs = REQUIRED_DOCS.filter((d) => (filesByDoc[d.key]?.length ?? 0) > 0)
    if (submittedDocs.length < REQUIRED_DOCS.length) {
      message.warning(`Загрузите все ${REQUIRED_DOCS.length} документа перед отправкой`)
      return
    }
    if (onCooldown) {
      message.warning(`Подождите ${Math.ceil(cooldownRemainingMs / 60_000)} мин до следующей отправки`)
      return
    }
    // Stub — real impl will POST a multipart form to /api/v1/users/kyc/submit
    // and respect the Sber ID idempotency key contract.
    message.success('Документы переданы на верификацию. Ожидайте уведомления.')
    writeLastSubmittedAt()
    setSubmitted(true)
  }

  const allReady = REQUIRED_DOCS.every((d) => (filesByDoc[d.key]?.length ?? 0) > 0)

  return (
    <Card
      className="sber-card"
      title={
        <Space>
          <SafetyCertificateOutlined style={{ color: 'var(--sber-green)' }} />
          <Text strong>
            {reverifyMode ? 'Переверификация документов (KYC)' : 'Документы для верификации (KYC)'}
          </Text>
        </Space>
      }
      extra={
        reverifyMode && (
          <Button size="small" type="text" onClick={() => setReverifyMode(false)}>
            Отменить
          </Button>
        )
      }
    >
      {/* Sprint 10 F-21 — surface rejection reason at the top so the user
          knows what to fix BEFORE re-uploading. */}
      {kycStatus === 'REJECTED' && rejectionReason && (
        <Alert
          type="error"
          showIcon
          message="Предыдущая заявка отклонена"
          description={rejectionReason}
          style={{ marginBottom: 16 }}
        />
      )}
      {kycStatus === 'REJECTED' && !rejectionReason && (
        <Alert
          type="warning"
          showIcon
          message="Предыдущая заявка отклонена"
          description="Проверьте качество фото (фокус, нет бликов, читаемый текст) и при необходимости свяжитесь с поддержкой за разъяснениями."
          style={{ marginBottom: 16 }}
        />
      )}

      <Paragraph type="secondary" style={{ marginBottom: 16 }}>
        Загрузите три документа в формате JPG / PNG / HEIC / PDF, объём каждого до {MAX_SIZE_MB} МБ.
        Документы поступают в обработку через Sber ID и хранятся в шифрованном виде.
      </Paragraph>

      <Space direction="vertical" size={20} style={{ width: '100%' }}>
        {REQUIRED_DOCS.map((doc, idx) => {
          const files = filesByDoc[doc.key] ?? []
          const uploadProps: UploadProps = {
            multiple: false,
            maxCount: 1,
            fileList: files,
            beforeUpload: (file) => {
              if (!ACCEPTED_MIME.includes(file.type)) {
                message.error(`Формат «${file.type}» не поддерживается. Допустимы: JPG / PNG / HEIC / PDF.`)
                return Upload.LIST_IGNORE
              }
              if (file.size > MAX_SIZE_MB * 1024 * 1024) {
                message.error(`Файл больше ${MAX_SIZE_MB} МБ. Сожмите или сделайте новый снимок.`)
                return Upload.LIST_IGNORE
              }
              // Sprint 9-DS-r4 stub: keep file client-side, never POST.
              // Sprint 11 will hand `file` to the real endpoint.
              return false
            },
            onChange: ({ fileList }) => {
              setFilesByDoc((prev) => ({ ...prev, [doc.key]: fileList }))
            },
            onRemove: () => {
              setFilesByDoc((prev) => ({ ...prev, [doc.key]: [] }))
              return true
            },
          }
          return (
            <div key={doc.key}>
              <Space style={{ marginBottom: 6 }} size={8}>
                <Tag color={files.length > 0 ? 'green' : 'default'} style={{ borderRadius: 'var(--radius-pill)' }}>
                  {idx + 1} / {REQUIRED_DOCS.length}
                </Tag>
                <Text strong>{doc.label}</Text>
              </Space>
              <Text type="secondary" style={{ display: 'block', fontSize: 'var(--text-xs)', marginBottom: 8 }}>
                {doc.hint}
              </Text>
              <Dragger {...uploadProps}>
                <p className="ant-upload-drag-icon">
                  <InboxOutlined style={{ color: files.length > 0 ? 'var(--sber-green)' : 'var(--text-secondary)' }} />
                </p>
                <p className="ant-upload-text">
                  {files.length > 0 ? (
                    <Space size={6}>
                      <FileTextOutlined style={{ color: 'var(--sber-green)' }} />
                      Файл загружен — нажмите крестик, чтобы заменить
                    </Space>
                  ) : (
                    'Перетащите файл сюда или нажмите для выбора'
                  )}
                </p>
                <p className="ant-upload-hint" style={{ fontSize: 'var(--text-xs)' }}>
                  JPG / PNG / HEIC / PDF · до {MAX_SIZE_MB} МБ
                </p>
              </Dragger>
            </div>
          )
        })}

        <Button
          type="primary"
          size="large"
          disabled={!allReady || onCooldown}
          onClick={handleSubmit}
          style={{ height: 44 }}
        >
          Отправить на верификацию
        </Button>
        {!allReady && (
          <Text type="secondary" style={{ fontSize: 'var(--text-xs)' }}>
            Загрузите все {REQUIRED_DOCS.length} документа, чтобы продолжить.
          </Text>
        )}
        {onCooldown && (
          <Text type="warning" style={{ fontSize: 'var(--text-xs)' }}>
            Следующая отправка возможна через {Math.ceil(cooldownRemainingMs / 60_000)} мин.
          </Text>
        )}
      </Space>
    </Card>
  )
}

// --- soft cooldown helpers --------------------------------------------
// localStorage is per-browser, so the cooldown is advisory only — the
// real rate-limit will live in the gateway / Sber ID endpoint. Using a
// long horizon (1h dev / 24h prod) keeps the bar high enough to deter
// accidental double-submits without trapping the user if they need a
// quick re-upload after fixing a doc.
function readLastSubmittedAt(): number | null {
  try {
    const raw = localStorage.getItem(RESUBMIT_COOLDOWN_KEY)
    if (!raw) return null
    const n = Number(raw)
    return Number.isFinite(n) ? n : null
  } catch {
    return null
  }
}

function writeLastSubmittedAt(): void {
  try {
    localStorage.setItem(RESUBMIT_COOLDOWN_KEY, String(Date.now()))
  } catch { /* quota / private-mode — accept the loss */ }
}
