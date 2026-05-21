import { useState } from 'react'
import { Card, Upload, Button, Space, Typography, Tag, Alert, message } from 'antd'
import { InboxOutlined, FileTextOutlined, SafetyCertificateOutlined } from '@ant-design/icons'
import type { UploadFile, UploadProps } from 'antd/es/upload/interface'

const { Text, Paragraph } = Typography
const { Dragger } = Upload

type KycStatus = 'NOT_SUBMITTED' | 'PENDING' | 'VERIFIED' | 'REJECTED'

interface KycUploadPanelProps {
  /** Current KYC status — drives whether the upload Dragger is shown. */
  kycStatus: KycStatus
}

const ACCEPTED_MIME = ['image/jpeg', 'image/png', 'image/heic', 'application/pdf']
const MAX_SIZE_MB = 10

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
 * Sprint 9-DS-r4 P2-8 — KYC document upload.
 *
 * Stub implementation. The Sber ID integration (which would push docs
 * to ID-verify's OCR + face-match pipeline) doesn't have a published
 * contract on this branch — we wire the UX shell now so when the
 * contract lands in Sprint 10, only `handleSubmit` flips from
 * client-side stub to a real POST /api/v1/users/kyc/submit.
 *
 * Today's behaviour: files accumulate client-side in a per-doc map,
 * "Отправить на верификацию" flips the local panel into a PENDING
 * mock state and informs the user that the documents were "received".
 * No file actually leaves the browser. Removed when the real endpoint
 * ships — see TD note in Sprint 10 backlog.
 */
export default function KycUploadPanel({ kycStatus }: KycUploadPanelProps) {
  const [filesByDoc, setFilesByDoc] = useState<Record<string, UploadFile[]>>({})
  const [submitted, setSubmitted] = useState(false)

  // Already-verified or under-review users don't see the uploader at
  // all — the panel renders an info alert and exits.
  if (kycStatus === 'VERIFIED') {
    return (
      <Card className="sber-card" title={<Text strong>Документы KYC</Text>}>
        <Alert
          message="Документы приняты и верифицированы"
          description="Если необходимо обновить документы — обратитесь в поддержку."
          type="success"
          showIcon
        />
      </Card>
    )
  }
  if (kycStatus === 'PENDING' || submitted) {
    return (
      <Card className="sber-card" title={<Text strong>Документы KYC</Text>}>
        <Alert
          message="Документы на рассмотрении"
          description="Обычно проверка занимает 1–2 рабочих дня. После одобрения вам будут доступны все функции платформы."
          type="info"
          showIcon
        />
      </Card>
    )
  }

  const handleSubmit = (): void => {
    const submittedDocs = REQUIRED_DOCS.filter((d) => (filesByDoc[d.key]?.length ?? 0) > 0)
    if (submittedDocs.length < REQUIRED_DOCS.length) {
      message.warning(`Загрузите все ${REQUIRED_DOCS.length} документа перед отправкой`)
      return
    }
    // Stub — real impl will POST a multipart form to /api/v1/users/kyc/submit
    // and respect the Sber ID idempotency key contract.
    message.success('Документы переданы на верификацию. Ожидайте уведомления.')
    setSubmitted(true)
  }

  const allReady = REQUIRED_DOCS.every((d) => (filesByDoc[d.key]?.length ?? 0) > 0)

  return (
    <Card
      className="sber-card"
      title={
        <Space>
          <SafetyCertificateOutlined style={{ color: 'var(--sber-green)' }} />
          <Text strong>Документы для верификации (KYC)</Text>
        </Space>
      }
    >
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
              // Sprint 10 will hand `file` to the real endpoint.
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
                <Tag color={files.length > 0 ? 'green' : 'default'} style={{ borderRadius: 999 }}>
                  {idx + 1} / {REQUIRED_DOCS.length}
                </Tag>
                <Text strong>{doc.label}</Text>
              </Space>
              <Text type="secondary" style={{ display: 'block', fontSize: 12, marginBottom: 8 }}>
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
                <p className="ant-upload-hint" style={{ fontSize: 11 }}>
                  JPG / PNG / HEIC / PDF · до {MAX_SIZE_MB} МБ
                </p>
              </Dragger>
            </div>
          )
        })}

        <Button
          type="primary"
          size="large"
          disabled={!allReady}
          onClick={handleSubmit}
          style={{ height: 44 }}
        >
          Отправить на верификацию
        </Button>
        {!allReady && (
          <Text type="secondary" style={{ fontSize: 12 }}>
            Загрузите все {REQUIRED_DOCS.length} документа, чтобы продолжить.
          </Text>
        )}
      </Space>
    </Card>
  )
}
