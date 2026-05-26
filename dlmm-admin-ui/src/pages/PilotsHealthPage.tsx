import { Card, Typography, Table, Tag, Tooltip, Space, Statistic, Row, Col, Progress } from 'antd'
import { TeamOutlined, WarningFilled, CheckCircleFilled, AlertFilled, ClockCircleOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import apiClient from '@/api/client'
import dayjs from 'dayjs'

const { Title, Text } = Typography

/**
 * B-06 (Batch #5, Sprint 14) — pilot health dashboard.
 *
 * <p>PO opens сюда раз в день: видит таблицу всех pilot orgs
 * sorted by engagement score asc (worst first → immediate action).
 * Каждая строка — score + flag + suggested actions list.
 *
 * <p>No filter / search yet — list ожидается ≤ 50 orgs первый
 * year. Search/filter добавится когда pilot scales.
 */
interface PilotHealth {
  orgId: string
  orgName: string
  memberCount: number
  lastActiveAt: string | null
  transactions30d: number
  activePositions: number
  fees30dRub: number
  engagementScore: number
  healthFlag: 'HEALTHY' | 'WARNING' | 'AT_RISK'
  suggestedActions: string[]
}

const flagConfig: Record<string, { color: string; icon: React.ReactNode; label: string }> = {
  HEALTHY: { color: 'green', icon: <CheckCircleFilled />, label: 'Здоровая' },
  WARNING: { color: 'orange', icon: <WarningFilled />, label: 'Внимание' },
  AT_RISK: { color: 'red', icon: <AlertFilled />, label: 'Риск' },
}

export default function PilotsHealthPage() {
  const { data, isLoading } = useQuery({
    queryKey: ['pilots-health'],
    queryFn: async () => {
      const resp = await apiClient.get<PilotHealth[]>('/admin/pilots/health')
      return resp.data
    },
    refetchInterval: 60_000, // refresh every minute — same as Dashboard pattern
  })

  const orgs: PilotHealth[] = data ?? []
  const counts = {
    healthy: orgs.filter((o) => o.healthFlag === 'HEALTHY').length,
    warning: orgs.filter((o) => o.healthFlag === 'WARNING').length,
    atRisk: orgs.filter((o) => o.healthFlag === 'AT_RISK').length,
  }

  return (
    <Space direction="vertical" size={24} style={{ width: '100%' }}>
      <div>
        <Title level={4} className="sber-page-title" style={{ marginBottom: 4 }}>
          <TeamOutlined style={{ marginRight: 8, color: 'var(--sber-green)' }} />
          Здоровье пилотных организаций
        </Title>
        <Text type="secondary">
          Composite engagement score (0-100) на основе логинов, транзакций, активных позиций и собранных комиссий за последние 30 дней.
        </Text>
      </div>

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={8}>
          <Card className="sber-card" size="small">
            <Statistic
              title={<Space size={6}><CheckCircleFilled style={{ color: 'var(--sber-green)' }} />Здоровые</Space>}
              value={counts.healthy}
              suffix={`/ ${orgs.length}`}
              valueStyle={{ color: 'var(--sber-green)' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sber-card" size="small">
            <Statistic
              title={<Space size={6}><WarningFilled style={{ color: 'var(--sber-amber, #fa8c16)' }} />Внимание</Space>}
              value={counts.warning}
              suffix={`/ ${orgs.length}`}
              valueStyle={{ color: 'var(--sber-amber, #fa8c16)' }}
            />
          </Card>
        </Col>
        <Col xs={24} sm={8}>
          <Card className="sber-card" size="small">
            <Statistic
              title={<Space size={6}><AlertFilled style={{ color: 'var(--plasma-critical, #ff4d4f)' }} />В зоне риска</Space>}
              value={counts.atRisk}
              suffix={`/ ${orgs.length}`}
              valueStyle={{ color: 'var(--plasma-critical, #ff4d4f)' }}
            />
          </Card>
        </Col>
      </Row>

      <Card className="sber-card">
        <Table<PilotHealth>
          loading={isLoading}
          dataSource={[...orgs].sort((a, b) => a.engagementScore - b.engagementScore)}
          rowKey="orgId"
          pagination={false}
          columns={[
            {
              title: 'Организация',
              dataIndex: 'orgName',
              render: (name, row) => (
                <div>
                  <Text strong>{name}</Text>
                  <br />
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {row.memberCount} {row.memberCount === 1 ? 'участник' : 'участников'}
                  </Text>
                </div>
              ),
            },
            {
              title: 'Score',
              dataIndex: 'engagementScore',
              width: 160,
              align: 'center',
              render: (score, row) => (
                <div>
                  <Progress
                    percent={score}
                    size="small"
                    strokeColor={
                      row.healthFlag === 'HEALTHY' ? 'var(--sber-green)' :
                      row.healthFlag === 'WARNING' ? 'var(--sber-amber, #fa8c16)' :
                      'var(--plasma-critical, #ff4d4f)'
                    }
                    format={(p) => <Text strong style={{ fontSize: 12 }}>{p}</Text>}
                  />
                </div>
              ),
              sorter: (a, b) => a.engagementScore - b.engagementScore,
              defaultSortOrder: 'ascend',
            },
            {
              title: 'Статус',
              dataIndex: 'healthFlag',
              width: 140,
              align: 'center',
              render: (flag) => {
                const cfg = flagConfig[flag] ?? flagConfig.WARNING
                return (
                  <Tag color={cfg.color} icon={cfg.icon} style={{ borderRadius: 999, fontWeight: 600 }}>
                    {cfg.label}
                  </Tag>
                )
              },
            },
            {
              title: 'Активность',
              key: 'activity',
              render: (_, row) => (
                <Space direction="vertical" size={0}>
                  <Text style={{ fontSize: 12 }}>
                    <ClockCircleOutlined style={{ marginRight: 4 }} />
                    {row.lastActiveAt ? dayjs(row.lastActiveAt).format('YYYY-MM-DD HH:mm') : 'никогда'}
                  </Text>
                  <Text type="secondary" style={{ fontSize: 11 }}>
                    {row.transactions30d} tx / {row.activePositions} поз. / {Math.round(row.fees30dRub)} ₽
                  </Text>
                </Space>
              ),
            },
            {
              title: 'Рекомендуемые действия',
              dataIndex: 'suggestedActions',
              render: (actions: string[]) => (
                <ul style={{ margin: 0, paddingLeft: 16 }}>
                  {actions.map((a, i) => (
                    <li key={i}>
                      <Text style={{ fontSize: 12 }}>{a}</Text>
                    </li>
                  ))}
                </ul>
              ),
            },
          ]}
        />
      </Card>
    </Space>
  )
}
