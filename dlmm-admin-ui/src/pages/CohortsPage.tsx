import { useState } from 'react'
import { Card, Radio, Segmented, Space, Spin, Typography, Alert } from 'antd'
import { useQuery } from '@tanstack/react-query'
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  ResponsiveContainer,
  Legend,
} from 'recharts'
import { theme } from 'antd'
import apiClient from '@/api/client'

const { Title, Text } = Typography

type Metric = 'DAU' | 'MAU' | 'D7' | 'D30'
type Days = 7 | 30 | 90

interface CohortDataPoint {
  date: string
  value: number
}

async function fetchCohorts(metric: Metric, days: Days): Promise<CohortDataPoint[]> {
  const res = await apiClient.get<CohortDataPoint[]>('/admin/cohorts', {
    params: { metric, days },
  })
  return res.data
}

const METRIC_LABELS: Record<Metric, string> = {
  DAU: 'DAU — Дневная аудитория',
  MAU: 'MAU — Месячная аудитория',
  D7: 'D7 — Удержание за 7 дней (%)',
  D30: 'D30 — Удержание за 30 дней (%)',
}

const DAY_OPTIONS = [
  { label: '7 дней', value: 7 },
  { label: '30 дней', value: 30 },
  { label: '90 дней', value: 90 },
]

export default function CohortsPage() {
  const [metric, setMetric] = useState<Metric>('DAU')
  const [days, setDays] = useState<Days>(30)

  const { token } = theme.useToken()

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['cohorts', metric, days],
    queryFn: () => fetchCohorts(metric, days),
    staleTime: 60_000,
    retry: 2,
  })

  const isRetentionMetric = metric === 'D7' || metric === 'D30'
  const yLabel = isRetentionMetric ? 'Удержание (%)' : 'Пользователи'

  return (
    <Space direction="vertical" size="large" style={{ width: '100%' }}>
      <div>
        <Title level={3} style={{ margin: 0 }}>
          Когортная аналитика
        </Title>
        <Text type="secondary">DAU / MAU и показатели удержания пользователей</Text>
      </div>

      <Card>
        <Space wrap style={{ marginBottom: 24 }}>
          <Segmented<Metric>
            options={[
              { label: 'DAU', value: 'DAU' },
              { label: 'MAU', value: 'MAU' },
              { label: 'D7', value: 'D7' },
              { label: 'D30', value: 'D30' },
            ]}
            value={metric}
            onChange={(val) => setMetric(val)}
          />

          <Radio.Group
            value={days}
            onChange={(e) => setDays(e.target.value as Days)}
            optionType="button"
            buttonStyle="solid"
            options={DAY_OPTIONS}
          />
        </Space>

        <div style={{ marginBottom: 12 }}>
          <Text strong>{METRIC_LABELS[metric]}</Text>
        </div>

        {isError && (
          <Alert
            type="error"
            message="Ошибка загрузки данных"
            description={error instanceof Error ? error.message : 'Неизвестная ошибка'}
            style={{ marginBottom: 16 }}
          />
        )}

        <Spin spinning={isLoading}>
          <ResponsiveContainer width="100%" height={380}>
            <LineChart
              data={data ?? []}
              margin={{ top: 8, right: 24, left: 0, bottom: 8 }}
            >
              <CartesianGrid
                strokeDasharray="3 3"
                stroke={token.colorBorderSecondary}
              />
              <XAxis
                dataKey="date"
                tick={{ fill: token.colorTextSecondary, fontSize: 12 }}
                tickLine={false}
                axisLine={{ stroke: token.colorBorderSecondary }}
                // Show abbreviated dates to avoid overlap
                tickFormatter={(val: string) => {
                  const d = new Date(val)
                  return `${String(d.getDate()).padStart(2, '0')}.${String(d.getMonth() + 1).padStart(2, '0')}`
                }}
              />
              <YAxis
                tick={{ fill: token.colorTextSecondary, fontSize: 12 }}
                tickLine={false}
                axisLine={false}
                width={60}
                tickFormatter={(v: number) =>
                  isRetentionMetric ? `${v}%` : v >= 1000 ? `${(v / 1000).toFixed(0)}k` : String(v)
                }
                label={{
                  value: yLabel,
                  angle: -90,
                  position: 'insideLeft',
                  offset: 10,
                  style: { fill: token.colorTextTertiary, fontSize: 11 },
                }}
              />
              <Tooltip
                contentStyle={{
                  background: token.colorBgElevated,
                  border: `1px solid ${token.colorBorderSecondary}`,
                  borderRadius: token.borderRadius,
                  color: token.colorText,
                }}
                labelStyle={{ color: token.colorText, fontWeight: 600 }}
                formatter={(value: number) =>
                  isRetentionMetric ? [`${value}%`, metric] : [value.toLocaleString('ru-RU'), metric]
                }
              />
              <Legend
                wrapperStyle={{ paddingTop: 12, color: token.colorText }}
              />
              <Line
                type="monotone"
                dataKey="value"
                name={metric}
                stroke={token.colorPrimary}
                strokeWidth={2}
                dot={false}
                activeDot={{ r: 5, fill: token.colorPrimary }}
              />
            </LineChart>
          </ResponsiveContainer>
        </Spin>
      </Card>
    </Space>
  )
}
