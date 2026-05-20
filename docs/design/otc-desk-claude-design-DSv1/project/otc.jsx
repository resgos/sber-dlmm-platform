/* ============================================================
   OTC Desk — admin-ui /otc page
   Russian-locale block-trade workflow for the ops desk.
   ============================================================ */
const { useState, useMemo, useEffect } = React;

/* ---------------- Sample data ----------------
   Stages: REQUESTED → QUOTED → ACCEPTED → SETTLED (or REJECTED / EXPIRED)
   Russian labels:
     REQUESTED  → Ожидает котировки
     QUOTED     → Котировка выставлена
     ACCEPTED   → Принята к расчёту
     SETTLED    → Сделка исполнена
     REJECTED   → Отклонена
     EXPIRED    → Просрочена
*/

const STAGE = {
  REQUESTED: { id: 'REQUESTED', nm: 'Ожидает котировки',     short: 'Ожидает',  color: 'var(--warning)' },
  QUOTED:    { id: 'QUOTED',    nm: 'Котировка выставлена',  short: 'Котировка',color: 'var(--sber-green)' },
  ACCEPTED:  { id: 'ACCEPTED',  nm: 'Принята к расчёту',     short: 'Принята',  color: 'var(--accent-info)' },
  SETTLED:   { id: 'SETTLED',   nm: 'Сделка исполнена',      short: 'Исполнена',color: 'var(--success)' },
  REJECTED:  { id: 'REJECTED',  nm: 'Отклонена',             short: 'Отклонена',color: 'var(--critical)' },
  EXPIRED:   { id: 'EXPIRED',   nm: 'Просрочена',            short: 'Просрочена',color: 'var(--critical)' },
};

const fmtN = (v, frac = 2) =>
  v.toLocaleString('ru-RU', { maximumFractionDigits: frac, minimumFractionDigits: frac });
const fmtNi = (v) =>
  v.toLocaleString('ru-RU', { maximumFractionDigits: 0 });
const fmtRubCompact = (v) => {
  if (v >= 1_000_000_000) return `${(v / 1_000_000_000).toFixed(2).replace('.', ',')} млрд`;
  if (v >= 1_000_000)     return `${(v / 1_000_000).toFixed(1).replace('.', ',')} млн`;
  if (v >= 1_000)         return `${(v / 1_000).toFixed(1).replace('.', ',')} тыс`;
  return v.toLocaleString('ru-RU');
};

/* ---------------- Deals ---------------- */
const DEALS = [
  // ----- REQUESTED -----
  {
    id: 'DLM-OTC-2026-0481',
    stage: 'REQUESTED',
    side: 'BUY',
    pair: { base: 'BTC', quote: 'RUB' },
    cp: 'Газпромбанк УА',
    cpId: 'CP-GPB-04',
    cpDesk: 'Казначейство',
    size: 12.5,
    sizeRub: 121_500_000,
    mid: 9_720_400,
    quote: null,
    spreadBps: null,
    received: '12:42:08',
    expiresIn: 312,  // seconds
    expiresTotal: 900,
    audit: [
      { nm: 'RFQ получен от контрагента', by: 'Газпромбанк УА · CP-GPB-04', ts: '12:42:08', state: 'done' },
      { nm: 'KYC и лимиты подтверждены',  by: 'auto · risk-engine',         ts: '12:42:09', state: 'done' },
      { nm: 'Ожидает выставления котировки', by: 'desk@sber',                ts: '—',        state: 'cur'  },
      { nm: 'Подтверждение контрагентом', by: '',                            ts: '—',        state: 'pending' },
      { nm: 'Расчёт через пул #BTC-RUB-01', by: '',                          ts: '—',        state: 'pending' },
    ],
  },
  {
    id: 'DLM-OTC-2026-0480',
    stage: 'REQUESTED',
    side: 'SELL',
    pair: { base: 'USDT', quote: 'RUB' },
    cp: 'НПФ Газфонд',
    cpId: 'CP-GZF-11',
    cpDesk: 'Управление ликвидностью',
    size: 8_000_000,
    sizeRub: 731_200_000,
    mid: 91.40,
    quote: null,
    spreadBps: null,
    received: '12:39:51',
    expiresIn: 524,
    expiresTotal: 900,
    audit: [],
  },
  {
    id: 'DLM-OTC-2026-0479',
    stage: 'REQUESTED',
    side: 'BUY',
    pair: { base: 'ETH', quote: 'RUB' },
    cp: 'УК «Альфа-Капитал»',
    cpId: 'CP-AKB-22',
    cpDesk: 'Дилинг',
    size: 320,
    sizeRub: 121_664_000,
    mid: 380_200,
    quote: null,
    spreadBps: null,
    received: '12:35:14',
    expiresIn: 86,
    expiresTotal: 900,
    audit: [],
  },

  // ----- QUOTED -----
  {
    id: 'DLM-OTC-2026-0478',
    stage: 'QUOTED',
    side: 'SELL',
    pair: { base: 'BTC', quote: 'USDT' },
    cp: 'ВТБ Капитал',
    cpId: 'CP-VTB-08',
    cpDesk: 'Treasury Desk',
    size: 4.2,
    sizeRub: 43_500_000,
    mid: 106_350,
    quote: 106_280,
    spreadBps: 6.6,
    received: '12:31:02',
    quotedAt: '12:32:18',
    expiresIn: 642,
    expiresTotal: 1200,
    audit: [
      { nm: 'RFQ получен от контрагента', by: 'ВТБ Капитал · CP-VTB-08',   ts: '12:31:02', state: 'done' },
      { nm: 'KYC и лимиты подтверждены',  by: 'auto · risk-engine',         ts: '12:31:03', state: 'done' },
      { nm: 'Котировка выставлена',       by: 'А. Сорокин · desk-2',        ts: '12:32:18', state: 'cur'  },
      { nm: 'Ожидает подтверждения контрагента', by: '',                    ts: '—',        state: 'pending' },
      { nm: 'Расчёт через пул #BTC-USDT-01',     by: '',                    ts: '—',        state: 'pending' },
    ],
  },
  {
    id: 'DLM-OTC-2026-0477',
    stage: 'QUOTED',
    side: 'BUY',
    pair: { base: 'USDC', quote: 'RUB' },
    cp: 'РСХБ Управление активами',
    cpId: 'CP-RSH-15',
    cpDesk: 'Казначейство',
    size: 15_000_000,
    sizeRub: 1_370_700_000,
    mid: 91.38,
    quote: 91.42,
    spreadBps: 4.4,
    received: '12:28:40',
    quotedAt: '12:29:55',
    expiresIn: 218,
    expiresTotal: 1200,
    audit: [],
  },
  {
    id: 'DLM-OTC-2026-0476',
    stage: 'QUOTED',
    side: 'SELL',
    pair: { base: 'ETH', quote: 'RUB' },
    cp: 'ПСБ Казначейство',
    cpId: 'CP-PSB-03',
    cpDesk: 'Казначейство',
    size: 180,
    sizeRub: 68_373_000,
    mid: 380_200,
    quote: 379_850,
    spreadBps: 9.2,
    received: '12:24:12',
    quotedAt: '12:25:30',
    expiresIn: 854,
    expiresTotal: 1200,
    audit: [],
  },
  {
    id: 'DLM-OTC-2026-0475',
    stage: 'QUOTED',
    side: 'SELL',
    pair: { base: 'USDT', quote: 'EUR' },
    cp: 'Совкомбанк Каз.',
    cpId: 'CP-SVK-07',
    cpDesk: 'Treasury',
    size: 5_000_000,
    sizeRub: 457_000_000,
    mid: 1.0860,
    quote: 1.0855,
    spreadBps: 4.6,
    received: '12:20:08',
    quotedAt: '12:21:33',
    expiresIn: 540,
    expiresTotal: 1200,
    audit: [],
  },

  // ----- ACCEPTED -----
  {
    id: 'DLM-OTC-2026-0473',
    stage: 'ACCEPTED',
    side: 'BUY',
    pair: { base: 'BTC', quote: 'RUB' },
    cp: 'МКБ Инвестиции',
    cpId: 'CP-MKB-19',
    cpDesk: 'Дилинг',
    size: 8.0,
    sizeRub: 77_760_000,
    mid: 9_720_400,
    quote: 9_715_280,
    spreadBps: 5.3,
    received: '12:14:02',
    quotedAt: '12:15:18',
    acceptedAt: '12:16:44',
    expiresIn: 0,
    expiresTotal: 0,
    audit: [],
  },
  {
    id: 'DLM-OTC-2026-0472',
    stage: 'ACCEPTED',
    side: 'SELL',
    pair: { base: 'ETH', quote: 'USDT' },
    cp: 'ВЭБ.РФ Каз.',
    cpId: 'CP-VEB-02',
    cpDesk: 'Корпоративное казначейство',
    size: 1_200,
    sizeRub: 456_240_000,
    mid: 4_120,
    quote: 4_116.8,
    spreadBps: 7.8,
    received: '11:58:22',
    quotedAt: '11:59:51',
    acceptedAt: '12:01:09',
    expiresIn: 0,
    expiresTotal: 0,
    audit: [],
  },
];

const SETTLEMENTS = [
  { ts: '12:18', pair: 'BTC/RUB', size: '6,5 BTC',    cp: 'ВТБ Капитал',          pnl: +318_400,  pnlBps: +5.1, dir: 'up' },
  { ts: '12:11', pair: 'USDT/RUB',size: '12,0 млн',   cp: 'Совкомбанк',           pnl: +482_100,  pnlBps: +4.4, dir: 'up' },
  { ts: '12:04', pair: 'ETH/RUB', size: '240 ETH',    cp: 'ПСБ Каз.',             pnl: +211_600,  pnlBps: +2.3, dir: 'up' },
  { ts: '11:52', pair: 'BTC/USDT',size: '3,2 BTC',    cp: 'РСХБ УА',              pnl: -98_300,   pnlBps: -2.9, dir: 'dn' },
  { ts: '11:40', pair: 'USDC/RUB',size: '8,5 млн',    cp: 'НПФ Газфонд',          pnl: +274_900,  pnlBps: +3.5, dir: 'up' },
  { ts: '11:28', pair: 'ETH/USDT',size: '420 ETH',    cp: 'МКБ Инвестиции',       pnl: +156_800,  pnlBps: +3.1, dir: 'up' },
  { ts: '11:17', pair: 'BTC/RUB', size: '10,1 BTC',   cp: 'УК «Альфа-Капитал»',   pnl: +612_400,  pnlBps: +6.2, dir: 'up' },
  { ts: '11:02', pair: 'USDT/EUR',size: '4,8 млн',    cp: 'ВЭБ.РФ Каз.',          pnl: -42_700,   pnlBps: -1.2, dir: 'dn' },
];

/* ---------------- Helpers ---------------- */
function fmtCountdown(sec) {
  if (sec <= 0) return '00:00';
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
function timerSeverity(sec) {
  if (sec <= 120) return 'danger';
  if (sec <= 360) return 'warn';
  return '';
}
function spreadClass(bps) {
  if (bps == null) return 'empty';
  if (bps <= 5) return 'tight';
  if (bps <= 8) return 'normal';
  return 'wide';
}

/* ============================================================
   Sidebar
   ============================================================ */
function Sidebar() {
  const items = [
    { key: 'dashboard',   icon: <FundOutlined />,        label: 'Дашборд' },
    { key: 'users',       icon: <UserOutlined />,        label: 'Пользователи' },
    { key: 'tokens',      icon: <BankOutlined />,        label: 'Токены' },
    { key: 'pools',       icon: <PieChartOutlined />,    label: 'Пулы' },
    { key: 'transactions',icon: <TransactionOutlined />, label: 'Транзакции' },
    { key: 'otc',         icon: <SwapOutlined />,        label: 'OTC деск',  active: true, badge: 9 },
    { key: 'suspicious',  icon: <WarningOutlined />,     label: 'Подозрительные' },
    { key: 'settings',    icon: <SettingOutlined />,     label: 'Настройки' },
  ];
  return (
    <aside className="sidebar">
      <div className="sidebar-head">
        <Brand />
      </div>
      <nav className="sidebar-nav">
        {items.map((it) => (
          <div key={it.key} className={'nav-item' + (it.active ? ' active' : '')}>
            {it.icon}
            <span className="label" style={{ flex: 1 }}>{it.label}</span>
            {it.badge != null && (
              <span style={{
                fontSize: 11, fontWeight: 700, padding: '1px 7px',
                borderRadius: 999, background: it.active ? '#fff' : 'var(--surface-solid-02)',
                color: it.active ? 'var(--sber-green)' : 'var(--text-secondary)',
                fontFeatureSettings: "'tnum' 1",
              }}>{it.badge}</span>
            )}
          </div>
        ))}
      </nav>
    </aside>
  );
}

/* ============================================================
   Topbar
   ============================================================ */
function Topbar() {
  return (
    <header className="header">
      <div className="header-left">
        <button className="icon-btn"><MenuFoldOutlined /></button>
        <div style={{
          fontSize: 13, color: 'var(--text-tertiary)', fontFamily: 'var(--font-mono)',
          fontWeight: 500,
        }}>
          admin / <span style={{ color: 'var(--text-secondary)' }}>otc</span>
        </div>
      </div>
      <div className="header-right">
        <div style={{
          display: 'inline-flex', alignItems: 'center', gap: 8,
          padding: '6px 12px', borderRadius: 999,
          background: 'var(--surface-solid-01)', border: '1px solid var(--border-default)',
          fontSize: 12, color: 'var(--text-secondary)',
        }}>
          <span style={{
            width: 8, height: 8, borderRadius: 4,
            background: 'var(--sber-green)',
          }}></span>
          Соединение с биржевым шлюзом установлено
        </div>
        <div className="bell-wrap">
          <BellOutlined size={18} />
          <span className="badge">3</span>
        </div>
        <div className="user-chip">
          <div className="avatar">АС</div>
          <div className="meta">
            <span className="nm">А. Сорокин</span>
            <span className="sub">ops-desk · senior</span>
          </div>
          <DownOutlined size={12} />
        </div>
      </div>
    </header>
  );
}

/* ============================================================
   KPI cards
   ============================================================ */
function KpiCard({ label, value, unit, ic, icTone, deltaTxt, deltaTone, span, spark }) {
  return (
    <div className="kpi">
      <div className="kpi-top">
        <span className="label">{label}</span>
        <span className={'ic ' + (icTone || '')}>{ic}</span>
      </div>
      <div className="value">
        {value}{unit && <span className="unit">{unit}</span>}
      </div>
      <div className="foot">
        <span>
          {deltaTone && <span className={'delta ' + deltaTone}>{deltaTxt}</span>}
          {!deltaTone && deltaTxt && <span className="span">{deltaTxt}</span>}
        </span>
        {span && <span className="span">{span}</span>}
        {spark && <svg className="spark" viewBox="0 0 64 22" preserveAspectRatio="none">
          <polyline points={spark} fill="none" stroke="var(--sber-green)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
        </svg>}
      </div>
    </div>
  );
}

function KpiRow() {
  return (
    <div className="kpi-row">
      <KpiCard
        label="Ожидают котировки"
        value="3"
        ic={<WarningOutlined size={16} />}
        icTone="amber"
        deltaTxt="срочно: 1"
        deltaTone="dn"
        span="ср. время ответа 2:18"
      />
      <KpiCard
        label="Котировок сегодня"
        value="24"
        ic={<SwapOutlined size={16} />}
        icTone="green"
        deltaTxt="+6 vs вчера"
        deltaTone="up"
        span="hit rate 71%"
      />
      <KpiCard
        label="Объём расчётов 24ч"
        value="12,4"
        unit="млрд ₽"
        ic={<BarChartOutlined size={16} />}
        icTone="green"
        deltaTxt="+18,2%"
        deltaTone="up"
        spark="0,21 6,9 12,15 18,11 24,14 30,8 36,12 42,5 48,9 54,3 60,6 64,2"
      />
      <KpiCard
        label="Средний спред к mid"
        value="4,2"
        unit="bps"
        ic={<LineChartOutlined size={16} />}
        icTone="blue"
        deltaTxt="−0,4 bps"
        deltaTone="up"
        span="цель ≤ 5,0 bps"
      />
    </div>
  );
}

/* ============================================================
   Inbox (left pane)
   ============================================================ */
function DealRow({ deal, selected, onSelect }) {
  const { id, stage, side, pair, cp, cpId, size, sizeRub, mid, quote, spreadBps, received, expiresIn, expiresTotal } = deal;
  const sev = timerSeverity(expiresIn);
  const pct = expiresTotal ? Math.min(100, Math.max(2, (expiresIn / expiresTotal) * 100)) : 0;
  const sizeLabel = (() => {
    if (pair.base === 'BTC' || pair.base === 'ETH') return `${fmtN(size, pair.base === 'BTC' ? 2 : 0)} ${pair.base}`;
    return `${fmtRubCompact(size)} ${pair.base}`;
  })();
  return (
    <tr className={selected ? 'selected' : ''} onClick={() => onSelect(id)}>
      <td>
        <div className="cp-cell">
          <span className="nm">{cp}</span>
          <span className="id">{cpId} · {received}</span>
        </div>
      </td>
      <td>
        <span className="pair-cell">
          <span className={'side-tag ' + (side === 'BUY' ? 'buy' : 'sell')}>{side === 'BUY' ? 'ПОК' : 'ПРОД'}</span>
          <span className="pair">{pair.base}<span className="arrow"> → </span>{pair.quote}</span>
        </span>
      </td>
      <td className="num">
        <div style={{ fontWeight: 600 }}>{sizeLabel}</div>
        <div style={{ fontSize: 11, color: 'var(--text-tertiary)', fontFeatureSettings: "'tnum' 1" }}>
          ≈ {fmtRubCompact(sizeRub)} ₽
        </div>
      </td>
      <td className="num">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, alignItems: 'flex-end', lineHeight: 1.2 }}>
          {quote != null ? (
            <>
              <span style={{ fontWeight: 600, fontFeatureSettings: "'tnum' 1" }}>
                {quote.toLocaleString('ru-RU', { maximumFractionDigits: pair.quote === 'EUR' ? 4 : 2 })}
              </span>
              <span className={'spread-pill ' + spreadClass(spreadBps)}>+{fmtN(spreadBps, 1)} bps</span>
            </>
          ) : (
            <>
              <span style={{ fontWeight: 600, color: 'var(--text-secondary)', fontFeatureSettings: "'tnum' 1" }}>
                mid {mid.toLocaleString('ru-RU', { maximumFractionDigits: pair.quote === 'EUR' ? 4 : 2 })}
              </span>
              <span style={{ fontSize: 11, color: 'var(--text-tertiary)' }}>не котировано</span>
            </>
          )}
        </div>
      </td>
      <td>
        {stage === 'ACCEPTED'
          ? <span className="tag tag-info" style={{ fontSize: 11 }}><span className="dot"/> к расчёту</span>
          : (
            <div className={'timer ' + sev}>
              <span className="num">{fmtCountdown(expiresIn)}</span>
              <span className="bar"><span style={{ width: `${pct}%` }}/></span>
            </div>
          )
        }
      </td>
    </tr>
  );
}

function StageSection({ stage, deals }) {
  const totalRub = deals.reduce((s, d) => s + d.sizeRub, 0);
  const s = STAGE[stage];
  return (
    <>
      <div className="stage-section">
        <span className="dot" style={{ background: s.color }}/>
        <span className="nm">{s.nm}</span>
        <span className="cnt">{deals.length}</span>
        <span className="vol">объём <b>{fmtRubCompact(totalRub)} ₽</b></span>
      </div>
    </>
  );
}

function Inbox({ deals, selectedId, onSelect, stageFilter, setStageFilter }) {
  const grouped = useMemo(() => {
    const order = ['REQUESTED', 'QUOTED', 'ACCEPTED'];
    return order.map((s) => ({
      stage: s,
      deals: deals.filter((d) => d.stage === s)
    })).filter((g) => g.deals.length);
  }, [deals]);

  const counts = {
    all: deals.length,
    REQUESTED: deals.filter(d => d.stage === 'REQUESTED').length,
    QUOTED: deals.filter(d => d.stage === 'QUOTED').length,
    ACCEPTED: deals.filter(d => d.stage === 'ACCEPTED').length,
  };

  const filtered = stageFilter === 'all'
    ? grouped
    : grouped.filter(g => g.stage === stageFilter);

  return (
    <div className="inbox">
      <div className="inbox-head">
        <div className="row1">
          <div className="ttl">RFQ-инбокс <span className="ct">{counts.all} активных</span></div>
          <div className="search-mini">
            <span className="ic"><SearchOutlined size={14}/></span>
            <input placeholder="Поиск по контрагенту, паре, ID..." />
          </div>
        </div>
        <div className="row2">
          <div className={'chip' + (stageFilter === 'all' ? ' active' : '')} onClick={() => setStageFilter('all')}>
            Все активные
            <span className="ct">{counts.all}</span>
          </div>
          <div className={'chip' + (stageFilter === 'REQUESTED' ? ' active' : '')} onClick={() => setStageFilter('REQUESTED')}>
            <span className="dot" style={{ background: 'var(--warning)' }}/>
            Ожидают
            <span className="ct">{counts.REQUESTED}</span>
          </div>
          <div className={'chip' + (stageFilter === 'QUOTED' ? ' active' : '')} onClick={() => setStageFilter('QUOTED')}>
            <span className="dot" style={{ background: 'var(--sber-green)' }}/>
            Котировки
            <span className="ct">{counts.QUOTED}</span>
          </div>
          <div className={'chip' + (stageFilter === 'ACCEPTED' ? ' active' : '')} onClick={() => setStageFilter('ACCEPTED')}>
            <span className="dot" style={{ background: 'var(--accent-info)' }}/>
            Приняты
            <span className="ct">{counts.ACCEPTED}</span>
          </div>
          <div style={{ flex: 1 }}/>
          <div className="chip">
            Архив сегодня
            <span className="ct">24</span>
          </div>
        </div>
      </div>

      <table className="deals-tbl">
        <thead>
          <tr>
            <th style={{ width: '26%' }}>Контрагент</th>
            <th>Сторона / Пара</th>
            <th className="num">Размер</th>
            <th className="num">Цена</th>
            <th style={{ width: 130 }}>Истекает</th>
          </tr>
        </thead>
        <tbody>
          {filtered.map((g) => (
            <React.Fragment key={g.stage}>
              <tr style={{ pointerEvents: 'none' }}>
                <td colSpan={5} style={{ padding: 0, borderBottom: 0 }}>
                  <StageSection stage={g.stage} deals={g.deals}/>
                </td>
              </tr>
              {g.deals.map(d => (
                <DealRow
                  key={d.id}
                  deal={d}
                  selected={d.id === selectedId}
                  onSelect={onSelect}
                />
              ))}
            </React.Fragment>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ============================================================
   Detail panel (right pane)
   ============================================================ */
function SpreadTrack({ spreadBps, max = 15 }) {
  // Quote position: 0 bps at 50%, +max at 100%, -max at 0%
  // Always BUY-side: positive spread = above mid (we charge premium)
  const pos = 50 + (spreadBps / max) * 50;
  const clamped = Math.max(8, Math.min(92, pos));
  return (
    <div className="spread-track">
      <div className="axis"/>
      <div className="mid-tick"/>
      <div className="mid-lbl">mid</div>
      <div className="quote-mark" style={{ left: `${clamped}%` }}/>
      <div className="quote-lbl"   style={{ left: `${clamped}%` }}>наш +{fmtN(spreadBps, 1)} bps</div>
      <div className="scale l">−{max} bps</div>
      <div className="scale r">+{max} bps</div>
    </div>
  );
}

function Countdown({ seconds, total }) {
  const sev = timerSeverity(seconds);
  const pct = total ? Math.min(100, Math.max(2, (seconds / total) * 100)) : 0;
  return (
    <div className={'countdown' + (sev ? ' ' + sev : '')}>
      <div className="left">
        <span className="lbl">До истечения</span>
        <span className="val">{fmtCountdown(seconds)}</span>
      </div>
      <div className="progress"><span style={{ width: `${pct}%` }}/></div>
    </div>
  );
}

function Detail({ deal }) {
  if (!deal) return null;
  const s = STAGE[deal.stage];
  const auditDefault = [
    { nm: 'RFQ получен от контрагента',     by: `${deal.cp} · ${deal.cpId}`, ts: deal.received, state: 'done' },
    { nm: 'KYC и лимиты подтверждены',      by: 'auto · risk-engine',         ts: deal.received, state: 'done' },
    deal.quotedAt
      ? { nm: 'Котировка выставлена', by: 'А. Сорокин · desk-2', ts: deal.quotedAt, state: deal.stage === 'QUOTED' ? 'cur' : 'done' }
      : { nm: 'Ожидает выставления котировки', by: 'desk@sber', ts: '—', state: deal.stage === 'REQUESTED' ? 'cur' : 'pending' },
    deal.acceptedAt
      ? { nm: 'Принята контрагентом', by: `${deal.cp}`, ts: deal.acceptedAt, state: deal.stage === 'ACCEPTED' ? 'cur' : 'done' }
      : { nm: 'Ожидает подтверждения контрагента', by: '', ts: '—', state: 'pending' },
    { nm: `Расчёт через пул #${deal.pair.base}-${deal.pair.quote}-01`, by: '', ts: '—', state: 'pending' },
  ];
  const audit = deal.audit && deal.audit.length ? deal.audit : auditDefault;
  const sideTone = deal.side === 'BUY' ? 'buy' : 'sell';

  return (
    <aside className="detail">
      <div className="detail-head">
        <div className="row1">
          <span className="deal-id">{deal.id}</span>
          <span className="tag" style={{
            background: deal.stage === 'REQUESTED' ? 'var(--warning-tint)'
                      : deal.stage === 'QUOTED'    ? 'var(--sber-green-tint)'
                      : deal.stage === 'ACCEPTED'  ? 'var(--accent-info-tint)'
                      : 'var(--surface-solid-02)',
            color:      deal.stage === 'REQUESTED' ? 'var(--warning)'
                      : deal.stage === 'QUOTED'    ? 'var(--sber-green)'
                      : deal.stage === 'ACCEPTED'  ? 'var(--accent-info)'
                      : 'var(--text-secondary)',
          }}>
            <span className="dot"/>
            {s.nm}
          </span>
        </div>
        <div className="row2">
          <div className="pair-big">
            <span className={'side-tag ' + sideTone} style={{ fontSize: 11, padding: '3px 8px' }}>
              {deal.side === 'BUY' ? 'ПОКУПКА' : 'ПРОДАЖА'}
            </span>
            {deal.pair.base}<span className="arrow"> → </span>{deal.pair.quote}
          </div>
          <div style={{ textAlign: 'right' }}>
            <div style={{ fontSize: 11, color: 'var(--text-tertiary)', fontWeight: 600, letterSpacing: 0.4, textTransform: 'uppercase' }}>Получено</div>
            <div style={{ fontSize: 13, color: 'var(--text-primary)', fontFamily: 'var(--font-mono)', fontWeight: 500 }}>{deal.received}</div>
          </div>
        </div>
      </div>

      <div className="detail-body">
        {/* Counterparties */}
        <div>
          <div className="sec-head">
            <span className="nm">Контрагенты</span>
            <span className="more">лимит CP: 850 млн ₽ · использовано 18%</span>
          </div>
          <div className="cp-card">
            <div className="who">
              <span className="role">Инициатор</span>
              <span className="nm">{deal.cp}</span>
              <span className="meta">{deal.cpId} · {deal.cpDesk}</span>
            </div>
            <div className="arrow">
              <ArrowRightOutlined size={14}/>
            </div>
            <div className="who">
              <span className="role">Маркет-мейкер</span>
              <span className="nm">Sber DLMM Desk</span>
              <span className="meta">desk-2 · А. Сорокин</span>
            </div>
          </div>
        </div>

        {/* Quote vs mid */}
        <div>
          <div className="sec-head">
            <span className="nm">Котировка относительно mid-market</span>
            <span className="more">источник: пул #{deal.pair.base}-{deal.pair.quote}-01</span>
          </div>
          <div className="quote-block">
            <div className="top">
              <div className="cell">
                <span className="lbl">Запрос</span>
                <span className="val">{fmtN(deal.size, deal.pair.base === 'BTC' ? 2 : 0)} {deal.pair.base}</span>
                <span className="sub">≈ {fmtRubCompact(deal.sizeRub)} ₽</span>
              </div>
              <div className="cell center">
                <span className="lbl">Mid</span>
                <span className="val">{fmtN(deal.mid, deal.pair.quote === 'EUR' ? 4 : 2)}</span>
                <span className="sub">{deal.pair.quote} за {deal.pair.base}</span>
              </div>
              <div className="cell right">
                <span className="lbl">Наша котировка</span>
                <span className={'val ' + (deal.quote != null ? 'accent' : '')}>
                  {deal.quote != null
                    ? fmtN(deal.quote, deal.pair.quote === 'EUR' ? 4 : 2)
                    : '—'}
                </span>
                <span className="sub">
                  {deal.quote != null
                    ? <>спред <b style={{ color: 'var(--sber-green)', fontWeight: 600 }}>+{fmtN(deal.spreadBps, 1)} bps</b></>
                    : 'не выставлена'}
                </span>
              </div>
            </div>
            {deal.quote != null
              ? <SpreadTrack spreadBps={deal.spreadBps}/>
              : (
                <div className="quote-empty">
                  <b>Котировка не выставлена.</b><br/>
                  Источник mid: пул #{deal.pair.base}-{deal.pair.quote}-01 ·
                  глубина в активном бине: <b style={{ color: 'var(--text-secondary)' }}>±18 млн ₽</b>
                </div>
              )
            }
          </div>
        </div>

        {/* Countdown */}
        {(deal.stage === 'REQUESTED' || deal.stage === 'QUOTED') && (
          <Countdown seconds={deal.expiresIn} total={deal.expiresTotal}/>
        )}

        {/* Audit trail */}
        <div>
          <div className="sec-head">
            <span className="nm">История</span>
            <span className="more">{audit.filter(a => a.state === 'done').length} из {audit.length} шагов</span>
          </div>
          <div className="audit">
            {audit.map((a, i) => (
              <div key={i} className={'audit-item ' + a.state}>
                <div className="gutter">
                  <span className="dot"/>
                  <span className="line"/>
                </div>
                <div className="step">
                  <span className="nm">{a.nm}</span>
                  {a.by && <span className="by">{a.by}</span>}
                </div>
                <span className="ts">{a.ts}</span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="detail-actions">
        {deal.stage === 'REQUESTED' && (
          <>
            <button className="btn btn-default">Отклонить</button>
            <button className="btn btn-primary">Выставить котировку</button>
          </>
        )}
        {deal.stage === 'QUOTED' && (
          <>
            <button className="btn btn-default">Аннулировать</button>
            <button className="btn btn-default">Изменить</button>
            <button className="btn btn-primary">Подтвердить расчёт</button>
          </>
        )}
        {deal.stage === 'ACCEPTED' && (
          <>
            <button className="btn btn-danger">Аварийно отменить</button>
            <button className="btn btn-primary">Запустить расчёт</button>
          </>
        )}
      </div>
    </aside>
  );
}

/* ============================================================
   Settlements timeline (bottom strip)
   ============================================================ */
function Settlements() {
  const totalPnl = SETTLEMENTS.reduce((s, x) => s + x.pnl, 0);
  return (
    <div className="settle-strip">
      <div className="settle-head">
        <div className="ttl">
          Последние расчёты <span className="ct">8 сделок</span>
        </div>
        <div className="sub">
          Итог P&L по ленте: <b style={{ color: 'var(--success)', fontWeight: 700, fontFeatureSettings: "'tnum' 1" }}>
            +{fmtNi(totalPnl)} ₽
          </b> · средний спред +3,4 bps
        </div>
      </div>
      <div className="settle-line">
        {SETTLEMENTS.map((x, i) => (
          <div key={i} className={'settle-tick ' + x.dir}>
            <span className="ts">{x.ts}</span>
            <span className="pair">{x.pair}</span>
            <span className="size">{x.size}</span>
            <span className="cp">{x.cp}</span>
            <span className={'pnl ' + (x.dir === 'up' ? 'up' : 'dn')}>
              {x.pnl > 0 ? '+' : '−'}{fmtNi(Math.abs(x.pnl))} ₽
              <span style={{ fontWeight: 500, color: 'var(--text-tertiary)', marginLeft: 6 }}>
                ({x.pnlBps > 0 ? '+' : '−'}{Math.abs(x.pnlBps).toFixed(1).replace('.', ',')} bps)
              </span>
            </span>
          </div>
        ))}
      </div>
    </div>
  );
}

/* ============================================================
   Page
   ============================================================ */
function OtcDeskPage() {
  const [deals, setDeals] = useState(DEALS);
  const [selectedId, setSelectedId] = useState('DLM-OTC-2026-0478'); // QUOTED — shows full spread viz
  const [stageFilter, setStageFilter] = useState('all');

  // Live countdown tick
  useEffect(() => {
    const t = setInterval(() => {
      setDeals(prev => prev.map(d => {
        if (d.stage !== 'REQUESTED' && d.stage !== 'QUOTED') return d;
        return { ...d, expiresIn: Math.max(0, d.expiresIn - 1) };
      }));
    }, 1000);
    return () => clearInterval(t);
  }, []);

  const selected = deals.find(d => d.id === selectedId) || deals[0];

  return (
    <div className="layout-otc" data-screen-label="OTC деск">
      <Sidebar/>
      <div className="shell">
        <Topbar/>
        <div className="content-otc">
          {/* Page head */}
          <div className="page-head-otc">
            <div className="lead">
              <span className="crumb">Дилинг · Институциональные сделки</span>
              <h1 className="title">
                OTC деск
                <span className="live">
                  <span className="pulse"/>
                  торговый день открыт
                </span>
              </h1>
              <span className="meta">
                Сессия: <b>20.05.2026 · 09:30 — 18:30 MSK</b> ·
                Дежурный: <b>А. Сорокин</b> · Поддержка: <b>desk-2, desk-3</b>
              </span>
            </div>
            <div className="actions">
              <button className="btn btn-default">
                <BarChartOutlined size={14}/>
                Отчёт за день
              </button>
              <button className="btn btn-default">
                <SwapVertOutlined size={14}/>
                Хедж-позиция
              </button>
              <button className="btn btn-primary">
                <PlusOutlined size={14}/>
                Новая сделка
              </button>
            </div>
          </div>

          {/* KPI */}
          <KpiRow/>

          {/* Two-pane main */}
          <div className="pane-row">
            <Inbox
              deals={deals}
              selectedId={selectedId}
              onSelect={setSelectedId}
              stageFilter={stageFilter}
              setStageFilter={setStageFilter}
            />
            <Detail deal={selected}/>
          </div>

          {/* Settlements bottom strip */}
          <Settlements/>
        </div>
      </div>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<OtcDeskPage/>);
