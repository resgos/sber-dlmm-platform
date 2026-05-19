import { useMemo, useState } from 'react'
import { Card, Statistic, InputNumber, Button, Space, Alert, Typography, Divider } from 'antd'
import { GiftOutlined, SwapOutlined } from '@ant-design/icons'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { balances, tokens, spasibo } from '@/api/services'
import type { Token, TokenBalance } from '@/api/types'

const { Text, Title } = Typography

const SSPAS_SYMBOL = 'SSPAS'

/**
 * Sprint 5 #5.5 — SberSpasibo conversion widget for the user dashboard.
 *
 * <p>Shows the user's SSPAS balance, lets them convert a chosen amount to
 * SRUB at the platform-configured rate (default 1:1, zero fee). Calls the
 * convert endpoint shipped in Sprint 5 #5.4.
 *
 * <p>Mounted on DashboardPage (right sidebar / promo card) — visible
 * immediately on login so the conversion path doesn't get buried.
 */
export default function SpasiboWidget() {
  const queryClient = useQueryClient()
  const [pointsToConvert, setPointsToConvert] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [success, setSuccess] = useState(false)

  const { data: tokenList } = useQuery({
    queryKey: ['tokens'],
    queryFn: () => tokens.getTokens(0, 200),
  })

  const { data: myBalances } = useQuery({
    queryKey: ['myBalances'],
    queryFn: balances.getMyBalances,
  })

  const sspasToken = useMemo<Token | null>(() => {
    return tokenList?.content?.find((t: Token) => t.symbol === SSPAS_SYMBOL) || null
  }, [tokenList])

  const sspasBalance = useMemo<TokenBalance | null>(() => {
    if (!sspasToken) return null
    return (myBalances || []).find((b: TokenBalance) => b.tokenId === sspasToken.id) || null
  }, [myBalances, sspasToken])

  const convertMut = useMutation({
    mutationFn: () => spasibo.convertToRub({
      points: pointsToConvert!,
      reference: `convert-${crypto.randomUUID()}`,
    }),
    onSuccess: () => {
      setSuccess(true)
      setError(null)
      setPointsToConvert(null)
      queryClient.invalidateQueries({ queryKey: ['myBalances'] })
      setTimeout(() => setSuccess(false), 4000)
    },
    onError: (err: unknown) => {
      const e = err as { response?: { data?: { message?: string } } }
      setError(e?.response?.data?.message || 'Не удалось конвертировать баллы')
    },
  })

  const available = sspasBalance?.available ?? 0
  const canConvert = !!pointsToConvert && pointsToConvert > 0 && pointsToConvert <= available

  return (
    <Card
      className="sber-card"
      style={{
        background: 'linear-gradient(135deg, #21A038 0%, #00C853 100%)',
        border: 'none',
        color: 'white',
      }}
      styles={{ body: { padding: 20 } }}
    >
      <Space direction="vertical" size={14} style={{ width: '100%' }}>
        <Space align="center">
          <GiftOutlined style={{ fontSize: 22, color: 'white' }} />
          <Title level={5} style={{ color: 'white', margin: 0 }}>
            СберСпасибо
          </Title>
        </Space>

        <Statistic
          title={<Text style={{ color: 'rgba(255,255,255,0.85)', fontSize: 12 }}>Доступно баллов</Text>}
          value={available}
          valueStyle={{ color: 'white', fontSize: 26, fontWeight: 700 }}
          groupSeparator=" "
          suffix={<span style={{ fontSize: 14, color: 'rgba(255,255,255,0.7)' }}>SSPAS</span>}
        />

        <Divider style={{ margin: '4px 0', background: 'rgba(255,255,255,0.2)' }} />

        <Text style={{ color: 'rgba(255,255,255,0.9)', fontSize: 12 }}>
          Конвертировать баллы в SRUB по курсу 1:1 (без комиссии)
        </Text>

        {success && (
          <Alert message="Конвертация выполнена" type="success" showIcon
            closable onClose={() => setSuccess(false)}
            style={{ borderRadius: 8 }} />
        )}
        {error && (
          <Alert message={error} type="error" showIcon closable
            onClose={() => setError(null)}
            style={{ borderRadius: 8 }} />
        )}

        {/* Sprint 9 — was Space.Compact which inherited the 42px input
            override but kept the button at default 38px; AntD's Compact
            collapses negative margins to fuse borders and that gap
            misaligned the button vertically + visually covered the input
            on the dashboard card. Plain flex with explicit gap renders
            both children at the same 40px height regardless of the
            theme-level input/button overrides. */}
        <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
          <InputNumber
            placeholder="Сколько баллов?"
            value={pointsToConvert}
            onChange={setPointsToConvert}
            min={0}
            max={available}
            controls={false}
            style={{ flex: 1, minWidth: 0, height: 40 }}
          />
          <Button
            type="primary"
            icon={<SwapOutlined />}
            onClick={() => convertMut.mutate()}
            disabled={!canConvert || convertMut.isPending}
            loading={convertMut.isPending}
            style={{
              background: 'white',
              color: 'var(--sber-green)',
              fontWeight: 600,
              borderColor: 'white',
              height: 40,
              flexShrink: 0,
            }}
          >
            В рубли
          </Button>
        </div>
      </Space>
    </Card>
  )
}
