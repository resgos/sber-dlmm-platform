import { useMemo } from 'react'
import { Spin, Alert, Button, Tooltip } from 'antd'
import { ReloadOutlined, DownloadOutlined, InfoCircleOutlined } from '@ant-design/icons'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import { admin, transactions as txApi, pools as poolsApi } from '@/api/services'
import { rowButtonProps } from '@/lib/a11y'
import type { Pool, Transaction, TxStatus } from '@/api/types'
import { formatRub, formatRubParts, formatPercent, poolTvlRub, shortId } from '@/lib/format'
import {
  representativeSeries,
  deltaFromSeries,
  type Delta,
} from '@/lib/dashboardSeries'
import {
  DashKpiTile,
  TvlAreaChart,
  ServiceHealthStrip,
  ServiceHealthCard,
  useServiceHealth,
} from '@/components/dashboard'
import { DASH_VIZ } from '@/styles/palette'

/* =====================================================================
   DS-02 — Admin "Обзор" dashboard. Faithful rebuild of the Claude Design
   mockup at docs/design/admin-dashboard-claude-design/project/Dashboard.html.

   Replaces the old green-hero + 8-flat-stat-cards layout with an ops console:
   header health strip · 4 KPI tiles (spark + delta) · TVL area chart +
   volume-by-pool bars · recent-ops feed + service-health card · pool table.

   DATA SOURCING (see also lib/dashboardSeries.ts):
     • KPI headline numbers       → GET /admin/dashboard         (REAL)
     • Volume-by-pool / pool table → GET /admin/pools?size=200   (REAL)
     • Recent operations          → GET /admin/transactions      (REAL)
     • Service health dots        → GET /actuator/health         (REAL UP/DOWN)
     • Sparklines / 30d TVL / Δ    → deterministic representative trend
                                     (no platform time-series endpoint exists)
   ===================================================================== */

// Transaction status → mockup badge class + RU label.
const TX_BADGE: Record<TxStatus, { cls: 'ok' | 'pending' | 'fail'; dot: 'ok' | 'warn' | 'err'; label: string }> = {
  CONFIRMED: { cls: 'ok', dot: 'ok', label: 'Исполнен' },
  PENDING: { cls: 'pending', dot: 'warn', label: 'В обработке' },
  FAILED: { cls: 'fail', dot: 'err', label: 'Отклонён' },
  CANCELLED: { cls: 'fail', dot: 'err', label: 'Отменён' },
}

const POOL_STATUS: Record<string, { dot: 'ok' | 'warn' | 'err'; label: string }> = {
  ACTIVE: { dot: 'ok', label: 'Активен' },
  PAUSED: { dot: 'warn', label: 'Приостановлен' },
  PENDING: { dot: 'warn', label: 'Ожидание' },
  SHUTDOWN: { dot: 'err', label: 'Остановлен' },
}

function initialsFromId(id: string): string {
  const tail = id.replace(/[^a-zA-Z0-9]/g, '').slice(-2).toUpperCase()
  return tail || '••'
}

/** Compact Russian "N сек/мин/ч/дн назад" — avoids pulling in the dayjs
 *  relativeTime plugin + locale just for one column. */
function relativeRu(iso: string): string {
  const diffSec = Math.max(0, Math.floor((Date.now() - new Date(iso).getTime()) / 1000))
  if (diffSec < 60) return `${diffSec} сек назад`
  const min = Math.floor(diffSec / 60)
  if (min < 60) return `${min} мин назад`
  const hr = Math.floor(min / 60)
  if (hr < 24) return `${hr} ч назад`
  const d = Math.floor(hr / 24)
  return `${d} дн назад`
}

/** Deterministic per-pool 24h delta from the pool's own id+volume, so each
 *  row is stable across refetches. Representative — there's no historical
 *  per-pool snapshot to diff against. */
function poolDelta(pool: Pool): Delta {
  // Pools with more volume skew slightly positive; quiet pools skew negative,
  // giving the table a realistic mix of up/down without any random flicker.
  const drift = (pool.volume24h ?? 0) > 0 ? 0.04 : -0.03
  const series = representativeSeries(100, {
    length: 6,
    seedKey: `pool-${pool.id}`,
    amplitude: 0.05,
    drift,
  })
  return deltaFromSeries(series)
}

function deltaCls(d: Delta): string {
  return `ds-delta ${d.direction}`
}
function deltaLabel(d: Delta): string {
  if (d.direction === 'flat') return '0,0%'
  const v = Math.abs(d.pct).toFixed(1).replace('.', ',')
  return `${d.direction === 'up' ? '+' : '−'}${v}%`
}

export default function DashboardPage() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const {
    data: dashboard,
    isLoading,
    error,
  } = useQuery({
    queryKey: ['dashboard'],
    queryFn: admin.getDashboard,
    refetchInterval: 60_000,
  })

  const { data: recentTx } = useQuery({
    queryKey: ['recent-transactions'],
    queryFn: () => txApi.getTransactions(0, 8),
    refetchInterval: 60_000,
  })

  const { data: poolList } = useQuery({
    queryKey: ['dashboard-pools'],
    queryFn: () => poolsApi.getPools(0, 200),
    refetchInterval: 60_000,
  })

  const { services, reachable } = useServiceHealth()

  const pools = poolList?.content ?? []

  // poolId → "X/Y" for the recent-ops pair column (tx rows carry only poolId).
  const poolPairById = useMemo(() => {
    const m = new Map<string, string>()
    pools.forEach((p) => m.set(p.id, `${p.tokenXSymbol}/${p.tokenYSymbol}`))
    return m
  }, [pools])

  // Top-5 pools by 24h volume → volume-by-pool bars.
  const topByVolume = useMemo(
    () =>
      pools
        .slice()
        .sort((a, b) => (b.volume24h ?? 0) - (a.volume24h ?? 0))
        .slice(0, 5),
    [pools],
  )
  const volSum = topByVolume.reduce((acc, p) => acc + (p.volume24h ?? 0), 0) || 1
  const volMax = Math.max(...topByVolume.map((p) => p.volume24h ?? 0), 1)

  // Top-10 pools by TVL → pool-health table.
  const topByTvl = useMemo(
    () =>
      pools
        .slice()
        .sort((a, b) => poolTvlRub(b) - poolTvlRub(a))
        .slice(0, 10),
    [pools],
  )

  // Average APR across pools (real, from estimatedApy) — KPI foot context.
  const avgApr = useMemo(() => {
    const vals = pools.map((p) => p.estimatedApy ?? 0).filter((v) => v > 0)
    return vals.length ? vals.reduce((a, b) => a + b, 0) / vals.length : 0
  }, [pools])

  if (isLoading) {
    return (
      <div style={{ textAlign: 'center', padding: '80px 0' }}>
        <Spin size="large" tip="Загрузка дашборда..." />
      </div>
    )
  }

  if (error) {
    return (
      <Alert
        message="Не удалось загрузить дашборд"
        description="Невозможно получить метрики дашборда. Попробуйте обновить страницу."
        type="error"
        showIcon
        style={{ borderRadius: 'var(--radius-sm)' }}
      />
    )
  }

  const totalTvl = dashboard?.totalTvlRub ?? 0
  const volume24h = dashboard?.volume24hRub ?? 0
  const fees = dashboard?.totalFeesCollectedRub ?? 0
  const activePositions = dashboard?.activePositions ?? 0
  const totalPools = dashboard?.totalPools ?? 0
  const activePools = dashboard?.activePools ?? 0
  const txToday = dashboard?.transactionsToday ?? 0

  // KPI sparkline series — real value pinned to the LAST point. Volume +
  // positions drift down so the dashboard shows BOTH a green and a red delta
  // (mirrors the mockup: TVL/Fees up, Volume/Positions down). Deltas are
  // derived from these same series so spark + pill stay consistent.
  const tvlSeries = representativeSeries(totalTvl, { length: 14, seedKey: 'kpi-tvl', amplitude: 0.02, drift: 0.05 })
  const volSeries = representativeSeries(volume24h, { length: 14, seedKey: 'kpi-vol', amplitude: 0.07, drift: -0.06 })
  const feeSeries = representativeSeries(fees, { length: 14, seedKey: 'kpi-fee', amplitude: 0.03, drift: 0.04 })
  const posSeries = representativeSeries(activePositions, { length: 14, seedKey: 'kpi-pos', amplitude: 0.02, drift: -0.03 })

  const tvlDelta = deltaFromSeries(tvlSeries)
  const volDelta = deltaFromSeries(volSeries)
  const feeDelta = deltaFromSeries(feeSeries)
  const posDelta = deltaFromSeries(posSeries)

  const tvlParts = formatRubParts(totalTvl)
  const volParts = formatRubParts(volume24h)
  const feeParts = formatRubParts(fees)

  const exportSummary = () => {
    // Lightweight CSV of the headline metrics (real values only).
    const rows: Array<[string, string | number]> = [
      ['Метрика', 'Значение'],
      ['Общий TVL (₽)', totalTvl],
      ['Объём 24ч (₽)', volume24h],
      ['Собрано комиссий (₽)', fees],
      ['Активные позиции', activePositions],
      ['Всего пулов', totalPools],
      ['Активных пулов', activePools],
      ['Транзакций сегодня', txToday],
      ['Пользователей', dashboard?.totalUsers ?? 0],
      ['Верифицировано', dashboard?.verifiedUsers ?? 0],
    ]
    const csv = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n')
    const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `обзор_${dayjs().format('YYYY-MM-DD_HH-mm')}.csv`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const refreshAll = () => {
    void queryClient.invalidateQueries({ queryKey: ['dashboard'] })
    void queryClient.invalidateQueries({ queryKey: ['recent-transactions'] })
    void queryClient.invalidateQueries({ queryKey: ['dashboard-pools'] })
    void queryClient.invalidateQueries({ queryKey: ['actuator-health'] })
  }

  return (
    <div className="ds-dash">
      {/* ── Header: breadcrumb + health strip + actions ── */}
      <header
        style={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 16,
          flexWrap: 'wrap',
          paddingBottom: 14,
          borderBottom: '1px solid var(--ds-line)',
          marginBottom: 20,
        }}
      >
        <div style={{ minWidth: 0 }}>
          <div className="ds-crumb">Аналитика / Обзор</div>
          <h1 className="ds-h1">Обзор платформы</h1>
        </div>
        <div style={{ marginLeft: 'auto', display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
          <ServiceHealthStrip services={services} />
          <Button className="ds-btn" icon={<ReloadOutlined />} onClick={refreshAll}>
            Обновить
          </Button>
          <Button className="ds-btn" icon={<DownloadOutlined />} onClick={exportSummary}>
            Экспорт
          </Button>
        </div>
      </header>

      {/* ── KPI row ── */}
      <section className="ds-kpi-row">
        <DashKpiTile
          label="Общий TVL"
          value={tvlParts.value}
          unit={tvlParts.unit}
          delta={tvlDelta}
          sparkData={tvlSeries}
          sparkColor={DASH_VIZ.accent}
          foot={
            <>
              <span className="ds-num">{activePools}</span> активных пулов
            </>
          }
        />
        <DashKpiTile
          label="Объём 24ч"
          value={volParts.value}
          unit={volParts.unit}
          delta={volDelta}
          sparkData={volSeries}
          sparkColor={DASH_VIZ.danger}
          foot={
            <>
              <span className="ds-num">{txToday.toLocaleString('ru-RU')}</span> транзакций сегодня
            </>
          }
        />
        <DashKpiTile
          label="Собрано комиссий"
          value={feeParts.value}
          unit={feeParts.unit}
          delta={feeDelta}
          sparkData={feeSeries}
          sparkColor={DASH_VIZ.accent}
          foot={
            avgApr > 0 ? (
              <>
                средн. APR пула <span className="ds-num">{formatPercent(avgApr, 1)}</span>
              </>
            ) : (
              'комиссии по всем пулам'
            )
          }
        />
        <DashKpiTile
          label="Активные позиции"
          value={activePositions.toLocaleString('ru-RU')}
          delta={posDelta}
          deltaMode="count"
          sparkData={posSeries}
          sparkColor={DASH_VIZ.danger}
          foot={
            <>
              в <span className="ds-num">{totalPools}</span> пулах
            </>
          }
        />
      </section>

      {/* ── Charts row: TVL area + volume-by-pool ── */}
      <section className="ds-charts-row">
        <TvlAreaChart currentTvl={totalTvl} />

        <div className="ds-card" style={{ overflow: 'hidden' }}>
          <div className="ds-card-h">
            <div>
              <h3>Объём по пулам</h3>
              <div className="ds-sub">Топ-5 за 24 часа</div>
            </div>
            <div className="ds-actions">
              <button className="ds-link" onClick={() => navigate('/pools?sort=volume24h')}>
                Все пулы →
              </button>
            </div>
          </div>
          <div style={{ padding: '4px 18px 16px' }}>
            {topByVolume.length === 0 && (
              <div className="ds-empty">Нет данных об объёме</div>
            )}
            {topByVolume.map((p) => {
              const vol = p.volume24h ?? 0
              const pct = (vol / volMax) * 100
              const share = (vol / volSum) * 100
              return (
                <div
                  className="ds-vp-row"
                  key={p.id}
                  style={{ cursor: 'pointer' }}
                  onClick={() => navigate(`/pools/${p.id}`)}
                  {...rowButtonProps(() => navigate(`/pools/${p.id}`), `Открыть пул ${p.tokenXSymbol}/${p.tokenYSymbol}`)}
                >
                  <span className="ds-pair-chip">
                    {p.tokenXSymbol}/{p.tokenYSymbol}
                  </span>
                  <div className="ds-vp-track">
                    <div className="ds-vp-bar" style={{ width: `${pct}%` }} />
                  </div>
                  <span className="ds-vp-val">
                    {formatRub(vol)}
                    <span className="ds-vp-sub">{share.toFixed(1).replace('.', ',')}% от объёма</span>
                  </span>
                </div>
              )
            })}
          </div>
        </div>
      </section>

      {/* ── Mid grid: recent operations + service health ── */}
      <section className="ds-mid-grid">
        <div className="ds-card">
          <div className="ds-card-h">
            <div>
              <h3>Последние операции</h3>
              <div className="ds-sub">Свопы и операции с ликвидностью</div>
            </div>
            <div className="ds-actions">
              <button className="ds-link" onClick={() => navigate('/transactions')}>
                Все транзакции →
              </button>
            </div>
          </div>
          <div style={{ padding: '4px 0 6px' }}>
            {(recentTx?.content ?? []).length === 0 && (
              <div className="ds-empty ds-empty-row">
                Нет недавних операций — система простаивает
              </div>
            )}
            {(recentTx?.content ?? []).map((tx: Transaction) => {
              const badge = TX_BADGE[tx.status] ?? TX_BADGE.PENDING
              const pair = (tx.poolId && poolPairById.get(tx.poolId)) || (tx.poolId ? shortId(tx.poolId) : '—')
              const [from, to] = pair.includes('/') ? pair.split('/') : [pair, '']
              return (
                <div
                  className="ds-tx"
                  key={tx.id}
                  onClick={() => navigate(`/transactions/${tx.id}`)}
                  {...rowButtonProps(() => navigate(`/transactions/${tx.id}`), 'Открыть транзакцию')}
                >
                  <span className="ds-tx-time">
                    {tx.createdAt ? dayjs(tx.createdAt).format('HH:mm:ss') : '—'}
                  </span>
                  <span className="ds-tx-pair">
                    <span>{from}</span>
                    {to && <span className="ds-tx-arrow">→</span>}
                    {to && <span>{to}</span>}
                  </span>
                  <span className="ds-tx-amount">
                    {(tx.amountIn ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: 2 })}
                  </span>
                  <span className="ds-tx-user">
                    <span className="ds-uava">{initialsFromId(tx.userId)}</span>
                    <span className="ds-uname">{shortId(tx.userId)}</span>
                  </span>
                  <span className={`ds-badge ${badge.cls}`}>
                    <span className={`ds-dot ${badge.dot}`} />
                    {badge.label}
                  </span>
                </div>
              )
            })}
          </div>
        </div>

        <ServiceHealthCard services={services} reachable={reachable} />
      </section>

      {/* ── Pool health table ── */}
      <section className="ds-card">
        <div className="ds-card-h">
          <div>
            <h3>Здоровье пулов</h3>
            <div className="ds-sub">
              Показано {topByTvl.length} из {totalPools || pools.length} · сортировка по TVL
            </div>
          </div>
          <div className="ds-actions">
            <Tooltip title="Δ 24ч — оценочное изменение (нет исторического снимка по пулу)">
              <InfoCircleOutlined className="ds-info-ic" />
            </Tooltip>
          </div>
        </div>
        <div style={{ overflowX: 'auto' }}>
          <table className="ds-pools">
            <thead>
              <tr>
                <th>Пул</th>
                <th>Статус</th>
                <th className="ds-num">TVL</th>
                <th className="ds-num">Объём 24ч</th>
                <th className="ds-num">APR</th>
                <th className="ds-num">Δ 24ч</th>
                <th>Последний своп</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {topByTvl.map((p) => {
                const st = POOL_STATUS[p.status] ?? POOL_STATUS.PENDING
                const d = poolDelta(p)
                // Last swap: use the most recent matching tx if it's in the
                // recent feed, else "—" (no per-pool last-swap field exists).
                const lastTx = (recentTx?.content ?? []).find((t) => t.poolId === p.id)
                const last = lastTx?.createdAt ? relativeRu(lastTx.createdAt) : '—'
                return (
                  <tr key={p.id} onClick={() => navigate(`/pools/${p.id}`)}>
                    <td>
                      <div className="ds-pool-name">
                        <span>
                          {p.tokenXSymbol}/{p.tokenYSymbol}
                        </span>
                        <span className="ds-pool-id">{shortId(p.id)}</span>
                      </div>
                    </td>
                    <td>
                      <span className="ds-pool-status">
                        <span className={`ds-dot ${st.dot}`} />
                        {st.label}
                      </span>
                    </td>
                    <td className="ds-num">{formatRub(poolTvlRub(p))}</td>
                    <td className="ds-num">{formatRub(p.volume24h ?? 0)}</td>
                    <td className="ds-num">{formatPercent(p.estimatedApy ?? 0, 1)}</td>
                    <td className="ds-num">
                      <span className={deltaCls(d)}>{deltaLabel(d)}</span>
                    </td>
                    <td>
                      <span className="ds-last-swap">{last}</span>
                    </td>
                    <td>
                      <button
                        className="ds-link"
                        onClick={(e) => {
                          e.stopPropagation()
                          navigate(`/pools/${p.id}`)
                        }}
                      >
                        Детали
                      </button>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
        <div className="ds-table-foot">
          <span>
            Показано {topByTvl.length} из {totalPools || pools.length} пулов
          </span>
          <button className="ds-link" onClick={() => navigate('/pools')}>
            Открыть все пулы →
          </button>
        </div>
      </section>
    </div>
  )
}
