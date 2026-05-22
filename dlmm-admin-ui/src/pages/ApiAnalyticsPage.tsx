import { useMemo, useState } from 'react'
import {
  Card,
  Typography,
  Space,
  Row,
  Col,
  Statistic,
  Tag,
  Progress,
  Alert,
  Button,
  Tooltip,
  Empty,
} from 'antd'
import {
  ApiOutlined,
  ReloadOutlined,
  ThunderboltFilled,
  CheckCircleFilled,
  WarningFilled,
  InfoCircleOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { parsePrometheusText, filterByName, type Sample } from '@/lib/prometheusTextParser'

const { Title, Text } = Typography

const TIERS = ['FREE', 'PRO', 'ENTERPRISE'] as const
type Tier = typeof TIERS[number]

const TIER_COLOR: Record<Tier, string> = {
  FREE: 'default',
  PRO: 'blue',
  ENTERPRISE: 'purple',
}

const TIER_RPS: Record<Tier, number> = {
  // Matches application.yml replenish rates (Sprint 9 #6.6).
  FREE: 10,
  PRO: 100,
  ENTERPRISE: 1000,
}

interface TierStats {
  tier: Tier
  allowed: number
  throttled: number
  total: number
  throttleRatio: number
}

/**
 * Sprint 10 F-15 — API key usage analytics dashboard.
 *
 * Scrapes the gateway's /actuator/prometheus endpoint, parses the
 * dlmm_gateway_ratelimit_total{tier,outcome} counters, and displays
 * per-tier breakdown:
 *
 *   - Total requests by tier (allowed + throttled sum)
 *   - Throttle ratio (throttled / total) — saturation indicator
 *   - Configured rps quota vs measured
 *
 * Refreshes every 15s — same cadence as Grafana's default scrape so
 * the values match what an operator would see in the Prometheus UI.
 *
 * Why scrape directly from the gateway rather than via admin-bff:
 *   1. Zero new backend endpoint or DTO.
 *   2. The metrics already exist (Sprint 9-DS-r4 P1-14); we just need
 *      to render them.
 *   3. /actuator/prometheus is exposed publicly per the JwtValidationFilter
 *      skip list — no auth needed.
 * Trade-off: admin-ui takes a direct dependency on the metrics name
 * format. Drift would break the dashboard but is caught by the
 * "Empty" branch below (parser returns 0 samples), not a crash.
 */
export default function ApiAnalyticsPage() {
  const [autoRefresh, setAutoRefresh] = useState(true)

  const { data: rawText, isLoading, isError, refetch, dataUpdatedAt } = useQuery({
    queryKey: ['gateway-prometheus'],
    queryFn: async () => {
      const resp = await fetch('/actuator/prometheus')
      if (!resp.ok) throw new Error(`HTTP ${resp.status}`)
      return resp.text()
    },
    refetchInterval: autoRefresh ? 15_000 : false,
    refetchOnWindowFocus: false,
  })

  const samples: Sample[] = useMemo(() => rawText ? parsePrometheusText(rawText) : [], [rawText])
  const rateLimitSamples = useMemo(() => filterByName(samples, 'dlmm_gateway_ratelimit_total'), [samples])

  const tierStats: TierStats[] = useMemo(() => TIERS.map((tier) => {
    const allowed = rateLimitSamples
      .filter((s) => s.labels.tier === tier && s.labels.outcome === 'allowed')
      .reduce((sum, s) => sum + s.value, 0)
    const throttled = rateLimitSamples
      .filter((s) => s.labels.tier === tier && s.labels.outcome === 'throttled')
      .reduce((sum, s) => sum + s.value, 0)
    const total = allowed + throttled
    const throttleRatio = total > 0 ? (throttled / total) : 0
    return { tier, allowed, throttled, total, throttleRatio }
  }), [rateLimitSamples])

  const grandTotal = tierStats.reduce((s, t) => s + t.total, 0)

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 12 }}>
        <div>
          <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>
            <ApiOutlined style={{ marginRight: 8, color: 'var(--sber-green)' }} />
            Аналитика API по тарифам
          </Title>
          <Text type="secondary">
            Throttle-ratio + allowed/blocked по каждому тарифу. Источник: счётчики gateway.
          </Text>
        </div>
        <Space>
          <Tag color={autoRefresh ? 'green' : 'default'} style={{ borderRadius: 999 }}>
            {autoRefresh ? 'Авто-обновление 15s' : 'Авто-обновление выкл.'}
          </Tag>
          <Button
            size="small"
            icon={<ReloadOutlined />}
            onClick={() => { setAutoRefresh(false); void refetch() }}
          >
            Обновить вручную
          </Button>
          <Button size="small" onClick={() => setAutoRefresh((v) => !v)}>
            {autoRefresh ? 'Выкл. авто' : 'Вкл. авто'}
          </Button>
        </Space>
      </div>

      {isError && (
        <Alert
          type="error"
          showIcon
          icon={<WarningFilled />}
          message="Не удалось получить данные из /actuator/prometheus"
          description="Проверьте, что gateway запущен и порт 8080 доступен. В dev: `docker-compose up dlmm-gateway`."
        />
      )}

      {!isError && !isLoading && rateLimitSamples.length === 0 && (
        <Empty
          description={
            <Space direction="vertical" size={4}>
              <Text>В метриках нет дат счётчика <code>dlmm_gateway_ratelimit_total</code>.</Text>
              <Text type="secondary" style={{ fontSize: 12 }}>
                Это нормально, если ни один API-вызов ещё не прошёл через rate-limit (например, после холодного старта).
                Сделайте несколько запросов через API и обновите страницу.
              </Text>
            </Space>
          }
          style={{ padding: 40 }}
        />
      )}

      {grandTotal > 0 && (
        <>
          {/* Overview row — same KPI tiles as Dashboard so the visual rhythm matches. */}
          <Row gutter={[16, 16]}>
            <Col xs={24} sm={8}>
              <Card className="sber-card" size="small">
                <Statistic
                  title={<Space size={6}><ThunderboltFilled />Всего запросов</Space>}
                  value={grandTotal}
                  formatter={(v) => Number(v).toLocaleString('ru-RU')}
                />
              </Card>
            </Col>
            <Col xs={24} sm={8}>
              <Card className="sber-card" size="small">
                <Statistic
                  title={<Space size={6}><CheckCircleFilled style={{ color: 'var(--sber-green)' }} />Пропущено (allowed)</Space>}
                  value={tierStats.reduce((s, t) => s + t.allowed, 0)}
                  formatter={(v) => Number(v).toLocaleString('ru-RU')}
                />
              </Card>
            </Col>
            <Col xs={24} sm={8}>
              <Card className="sber-card" size="small">
                <Statistic
                  title={<Space size={6}><WarningFilled style={{ color: 'var(--sber-amber)' }} />Заблокировано (throttled)</Space>}
                  value={tierStats.reduce((s, t) => s + t.throttled, 0)}
                  formatter={(v) => Number(v).toLocaleString('ru-RU')}
                  valueStyle={{ color: 'var(--sber-amber)' }}
                />
              </Card>
            </Col>
          </Row>

          {/* Per-tier table — wider card with bar + numbers. */}
          <Card
            className="sber-card"
            title={<Text strong>Разбивка по тарифам</Text>}
            extra={
              <Text type="secondary" style={{ fontSize: 11 }}>
                Обновлено: {dataUpdatedAt ? new Date(dataUpdatedAt).toLocaleTimeString('ru-RU') : '—'}
              </Text>
            }
          >
            <Space direction="vertical" size={20} style={{ width: '100%' }}>
              {tierStats.map((t) => {
                const throttlePct = t.throttleRatio * 100
                const tone = throttlePct >= 10
                  ? 'var(--plasma-critical)'
                  : throttlePct >= 1
                    ? 'var(--sber-amber)'
                    : 'var(--sber-green)'
                return (
                  <div key={t.tier}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6, flexWrap: 'wrap', gap: 8 }}>
                      <Space size={10}>
                        <Tag color={TIER_COLOR[t.tier]} style={{ borderRadius: 999, fontWeight: 700, fontSize: 12, padding: '0 10px' }}>
                          {t.tier}
                        </Tag>
                        <Text type="secondary" style={{ fontSize: 12 }}>
                          лимит {TIER_RPS[t.tier]} RPS
                        </Text>
                      </Space>
                      <Space size={16}>
                        <Text style={{ fontVariantNumeric: 'tabular-nums', fontSize: 13 }}>
                          allowed: <Text strong>{t.allowed.toLocaleString('ru-RU')}</Text>
                        </Text>
                        <Text style={{ fontVariantNumeric: 'tabular-nums', fontSize: 13 }}>
                          throttled: <Text strong style={{ color: tone }}>{t.throttled.toLocaleString('ru-RU')}</Text>
                        </Text>
                        <Tooltip title="Отношение заблокированных запросов к общему числу. > 10% — пора повышать лимит / апселлить клиента / расследовать runaway.">
                          <Text style={{ fontSize: 12, fontVariantNumeric: 'tabular-nums', color: tone, fontWeight: 600 }}>
                            throttle ratio: {throttlePct.toFixed(2)}%
                            <InfoCircleOutlined style={{ marginInlineStart: 4, fontSize: 11 }} />
                          </Text>
                        </Tooltip>
                      </Space>
                    </div>
                    <Progress
                      percent={t.total === 0 ? 0 : 100}
                      success={{ percent: t.total === 0 ? 0 : (t.allowed / t.total) * 100, strokeColor: 'var(--sber-green)' }}
                      strokeColor={tone}
                      showInfo={false}
                      size={['default', 12]}
                    />
                  </div>
                )
              })}
            </Space>
          </Card>

          <Alert
            type="info"
            showIcon
            message="Как читать throttle ratio"
            description={
              <Space direction="vertical" size={2}>
                <Text style={{ fontSize: 12 }}>· <Text strong>&lt; 1%</Text> — здоровый уровень, лимит с запасом</Text>
                <Text style={{ fontSize: 12 }}>· <Text strong>1–10%</Text> — клиенты периодически упираются в потолок, рассмотрите апселл</Text>
                <Text style={{ fontSize: 12 }}>· <Text strong>&gt; 10%</Text> — массовая блокировка, проверьте runaway-клиента или расширьте тариф</Text>
              </Space>
            }
          />
        </>
      )}
    </Space>
  )
}
