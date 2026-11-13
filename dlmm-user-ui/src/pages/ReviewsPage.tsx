import { Avatar, Card, Rate, Space, Typography } from 'antd'
import { useTranslation } from 'react-i18next'

const { Title, Text, Paragraph } = Typography

// Seed data for pilot testimonials. Имя/роль/текст — в i18n: reviews.items.<key>.*
const REVIEWS = [
  { key: 'treasurer', rating: 5, date: '2026-05-15' },
  { key: 'cfo', rating: 5, date: '2026-05-10' },
  { key: 'risk', rating: 4, date: '2026-04-28' },
  { key: 'accountant', rating: 5, date: '2026-04-20' },
]

export default function ReviewsPage() {
  const { t, i18n } = useTranslation()
  const dateLocale = i18n.language?.startsWith('en') ? 'en-GB' : 'ru-RU'
  return (
    <div style={{ maxWidth: 800, margin: '0 auto', padding: '24px 16px' }}>
      <Title level={2}>{t('reviews.title')}</Title>
      <Paragraph type="secondary" style={{ marginBottom: 32 }}>
        {t('reviews.subtitle')}
      </Paragraph>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {REVIEWS.map((r) => {
          const name = t(`reviews.items.${r.key}.name`)
          return (
            <Card key={r.key} className="sber-card">
              <Space align="start">
                <Avatar size={48} style={{ background: 'var(--sber-primary)', fontSize: 'var(--text-md)' }}>
                  {name[0]}
                </Avatar>
                <div style={{ flex: 1 }}>
                  <Text strong>{name}</Text>
                  <br />
                  <Text type="secondary" style={{ fontSize: 'var(--text-sm)' }}>{t(`reviews.items.${r.key}.role`)}</Text>
                  <br />
                  <Rate disabled defaultValue={r.rating} style={{ fontSize: 'var(--text-base)', margin: '8px 0' }} />
                  <br />
                  <Paragraph style={{ margin: 0 }}>{t(`reviews.items.${r.key}.text`)}</Paragraph>
                  <Text type="secondary" style={{ fontSize: 'var(--text-xs)', marginTop: 8, display: 'block' }}>
                    {new Date(r.date).toLocaleDateString(dateLocale, { day: 'numeric', month: 'long', year: 'numeric' })}
                  </Text>
                </div>
              </Space>
            </Card>
          )
        })}
      </Space>
    </div>
  )
}
