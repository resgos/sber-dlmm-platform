import { Avatar, Card, Rate, Space, Typography } from 'antd'

const { Title, Text, Paragraph } = Typography

// Seed data for pilot testimonials (realistic Sber-context names/roles)
const REVIEWS = [
  {
    id: 1,
    name: 'Алексей Морозов',
    role: 'Казначей, ПАО "РосМеталл"',
    rating: 5,
    text: 'Платформа существенно упростила управление валютной ликвидностью. Автоматический забор комиссий экономит 2-3 часа работы казначейства в день.',
    date: '2026-05-15',
  },
  {
    id: 2,
    name: 'Наталья Соколова',
    role: 'Финансовый директор, ООО "Агроторг"',
    rating: 5,
    text: 'Прозрачное ценообразование и интуитивный интерфейс. Особенно ценим контроль диапазонов и аналитику по позициям в реальном времени.',
    date: '2026-05-10',
  },
  {
    id: 3,
    name: 'Дмитрий Ковалёв',
    role: 'Риск-менеджер, АО "ТехноСнаб"',
    rating: 4,
    text: 'Удобная интеграция через API. Мониторинг здоровья позиций и система оповещений — именно то, что нужно нашей команде для оперативного реагирования.',
    date: '2026-04-28',
  },
  {
    id: 4,
    name: 'Ирина Белова',
    role: 'Главный бухгалтер, ПАО "СибирьЭнерго"',
    rating: 5,
    text: 'Двухфакторная аутентификация и надёжная защита данных — критически важно для нашей отрасли. Рекомендуем как надёжного партнёра по управлению ликвидностью.',
    date: '2026-04-20',
  },
]

export default function ReviewsPage() {
  return (
    <div style={{ maxWidth: 800, margin: '0 auto', padding: '24px 16px' }}>
      <Title level={2}>Отзывы клиентов</Title>
      <Paragraph type="secondary" style={{ marginBottom: 32 }}>
        Мнения пилотных участников платформы DLMM
      </Paragraph>
      <Space direction="vertical" size={16} style={{ width: '100%' }}>
        {REVIEWS.map((r) => (
          <Card key={r.id} className="sber-card">
            <Space align="start">
              <Avatar size={48} style={{ background: 'var(--sber-primary)', fontSize: 'var(--text-md)' }}>
                {r.name[0]}
              </Avatar>
              <div style={{ flex: 1 }}>
                <Text strong>{r.name}</Text>
                <br />
                <Text type="secondary" style={{ fontSize: 12 }}>{r.role}</Text>
                <br />
                <Rate disabled defaultValue={r.rating} style={{ fontSize: 14, margin: '8px 0' }} />
                <br />
                <Paragraph style={{ margin: 0 }}>{r.text}</Paragraph>
                <Text type="secondary" style={{ fontSize: 11, marginTop: 8, display: 'block' }}>
                  {new Date(r.date).toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}
                </Text>
              </div>
            </Space>
          </Card>
        ))}
      </Space>
    </div>
  )
}
