// DLMM Dashboard — main app
// Renders the treasurer's home view at 1440px desktop.

const { useState, useMemo } = React;

// ─── data ───────────────────────────────────────────────────────────────

const PORTFOLIO = {
  value: 12_437_820.40,
  delta24h: 384_200.00,
  delta24hPct: 3.18,
  asOf: '09:42 МСК',
  activePositions: 7,
  positionsInRange: 6,
  volume24h: 2_140_320.00,
  volume24hDelta: 12.4,
  unclaimedFees: 18_240.55,
  unclaimedPools: 3,
  lifetimeEarnings: 487_200.00,
  lifetimeDays: 47,
};

const ACTIVITY = [
  { t: '09:42', kind: 'swap',     pair: ['SUSDT','SRUB'], amount: 50_000.00,  status: 'done',    dir: 'out' },
  { t: '09:15', kind: 'liquidity',pair: ['SBER','SRUB'],  amount: 200_000.00, status: 'done',    dir: 'in'  },
  { t: '08:55', kind: 'hedge',    pair: ['USD','RUB'],    amount: 80_000.00,  status: 'pending', dir: 'out' },
  { t: '08:23', kind: 'swap',     pair: ['SBER','SRUB'],  amount: 12_500.00,  status: 'done',    dir: 'out' },
  { t: '07:48', kind: 'fees',     pair: ['SUSDC','SUSDT'],amount: 1_240.55,   status: 'done',    dir: 'in'  },
];

const ACT_LABEL = {
  swap:      'Своп',
  liquidity: 'Ликвидность',
  hedge:     'Хедж',
  fees:      'Сбор комиссий',
};

const TOKENS = [
  { sym:'SRUB',  available:4_820_540.00, locked:1_240_000.00, price:1.00,        delta:0.00,  value:6_060_540.00 },
  { sym:'SBER',  available:8_240,        locked:2_100,        price:304.50,      delta:1.82,  value:3_148_530.00 },
  { sym:'SUSDT', available:18_420.55,    locked:0,            price:92.40,       delta:0.08,  value:1_702_058.82 },
  { sym:'SETH',  available:4.82,         locked:0.50,         price:247_800.00,  delta:-2.41, value:1_318_696.00 },
  { sym:'SUSDC', available:6_240.00,     locked:800.00,       price:92.38,       delta:-0.14, value:650_273.92 },
  { sym:'SBTC',  available:0.082,        locked:0,            price:5_240_000.00,delta:0.62,  value:429_680.00 },
  { sym:'SCNY',  available:12_400.00,    locked:0,            price:12.84,       delta:0.32,  value:159_216.00 },
];

const POSITIONS = [
  {
    pair: ['SBER','SRUB'], value: 4_248_200.00, delta: 3.82, apr: 18.4,
    range: { low: 296.40, high: 318.20, cur: 304.50 }, status: 'active',
    spark: [101,100,102,101,103,104,103,105,106,105,107,108,109,108,110,111,112,111,113,114,113,115,116,118],
  },
  {
    pair: ['SUSDC','SUSDT'], value: 2_140_320.00, delta: 0.41, apr: 6.2,
    range: { low: 0.998, high: 1.002, cur: 1.0002 }, status: 'active',
    spark: [100,100.1,99.9,100.0,100.2,100.1,99.95,100.0,100.1,100.05,100.0,99.92,100.1,100.0,100.05,100.1,99.98,100.0,100.08,100.05,100.0,100.1,100.05,100.4],
  },
  {
    pair: ['SETH','SRUB'], value: 1_420_480.00, delta: -1.18, apr: 22.8,
    range: { low: 248_000, high: 264_000, cur: 246_400 }, status: 'out',
    spark: [110,109,111,108,107,109,106,105,107,106,104,105,103,104,102,103,100,101,99,100,98,99,97,98],
  },
];

// ─── sidebar ────────────────────────────────────────────────────────────

const NAV = [
  { id:'home',      label:'Главная',      icon:'home',      badge:null },
  { id:'swap',      label:'Обмен',        icon:'swap',      badge:null },
  { id:'hedge',     label:'Хедж FX',      icon:'shield',    badge:'2' },
  { id:'pools',     label:'Пулы',         icon:'pool',      badge:null },
  { id:'positions', label:'Мои позиции',  icon:'positions', badge:'7' },
  { id:'tx',        label:'Транзакции',   icon:'tx',        badge:null },
  { id:'profile',   label:'Профиль',      icon:'user',      badge:null },
];

function Sidebar({ active, onSelect }) {
  return (
    <aside style={{
      width: 240, flex: '0 0 240px', borderRight: `1px solid ${T.border}`,
      background: T.card, display: 'flex', flexDirection: 'column',
      position: 'sticky', top: 0, height: '100vh',
    }}>
      {/* brand block */}
      <div style={{
        padding: '20px 20px 20px', display: 'flex', alignItems: 'center', gap: 10,
        borderBottom: `1px solid ${T.border}`,
      }}>
        <div style={{
          width: 28, height: 28, borderRadius: 6,
          background: T.brand, display:'flex', alignItems:'center', justifyContent:'center',
          color: '#fff', fontWeight: 700, fontSize: 14, letterSpacing: '-0.02em',
        }}>Σ</div>
        <div style={{ display:'flex', flexDirection:'column', lineHeight: 1.15 }}>
          <span style={{ fontSize: 13, fontWeight: 600, letterSpacing:'0.01em' }}>Sber DLMM</span>
          <span style={{ fontSize: 11, color: T.text2 }}>Корп. казначейство</span>
        </div>
      </div>

      <nav style={{ padding: '12px 12px', flex: 1, display:'flex', flexDirection:'column', gap: 2 }}>
        {NAV.map(item => {
          const isActive = item.id === active;
          return (
            <button key={item.id} onClick={() => onSelect(item.id)} style={{
              all: 'unset', cursor: 'default',
              display: 'flex', alignItems: 'center', gap: 10,
              padding: '8px 12px', borderRadius: 6, height: 34,
              background: isActive ? T.surface02 : 'transparent',
              color: isActive ? T.text : T.text2,
              fontSize: 13, fontWeight: isActive ? 500 : 400,
              position: 'relative',
            }}
              onMouseEnter={e => { if (!isActive) e.currentTarget.style.background = T.surface01; }}
              onMouseLeave={e => { if (!isActive) e.currentTarget.style.background = 'transparent'; }}
            >
              {isActive && <span style={{ position:'absolute', left: 0, top: 6, bottom: 6, width: 2, borderRadius: 2, background: T.brand }} />}
              <Icon name={item.icon} size={16} color={isActive ? T.text : T.text2} />
              <span style={{ flex: 1 }}>{item.label}</span>
              {item.badge && (
                <span style={{
                  display:'inline-flex', alignItems:'center', justifyContent:'center',
                  minWidth: 18, height: 18, padding: '0 5px', borderRadius: 4,
                  background: T.surface02, color: T.text2, fontSize: 11, fontWeight: 500,
                }}>{item.badge}</span>
              )}
            </button>
          );
        })}
      </nav>

      {/* footer */}
      <div style={{ padding: '12px 16px', borderTop: `1px solid ${T.border}`, fontSize: 11, color: T.text3, display:'flex', justifyContent:'space-between' }}>
        <span>v 4.12.0</span>
        <span style={{ display:'inline-flex', alignItems:'center', gap: 4 }}>
          <span style={{ width:6, height:6, borderRadius:999, background:T.success }} />
          Прод. сеть
        </span>
      </div>
    </aside>
  );
}

// ─── topbar ─────────────────────────────────────────────────────────────

function TopBar() {
  return (
    <div style={{
      height: 52, borderBottom: `1px solid ${T.border}`, background: T.card,
      display:'flex', alignItems:'center', padding: '0 24px', gap: 16,
    }}>
      <div style={{ fontSize: 13, color: T.text2 }}>
        Главная <span style={{ color: T.text3, margin: '0 8px' }}>/</span>
        <span style={{ color: T.text, fontWeight: 500 }}>Обзор портфеля</span>
      </div>

      <div style={{ flex: 1 }} />

      {/* market mini-ticker */}
      <div style={{ display:'flex', alignItems:'center', gap: 18, fontSize: 12, color: T.text2 }}>
        <span style={{ display:'inline-flex', gap: 6 }}>
          <span>Ключевая ставка</span>
          <span className="num" style={{ color: T.text, fontWeight: 500 }}>16,00%</span>
        </span>
        <span style={{ width: 1, height: 14, background: T.border }} />
        <span style={{ display:'inline-flex', gap: 6 }}>
          <span>RUONIA</span>
          <span className="num" style={{ color: T.text, fontWeight: 500 }}>15,82%</span>
          <DeltaPill value={-0.04} flat />
        </span>
        <span style={{ width: 1, height: 14, background: T.border }} />
        <span style={{ display:'inline-flex', gap: 6 }}>
          <span>USD/RUB</span>
          <span className="num" style={{ color: T.text, fontWeight: 500 }}>92,41</span>
          <DeltaPill value={0.18} flat />
        </span>
      </div>

      <div style={{ width: 1, height: 22, background: T.border, marginLeft: 8 }} />

      {/* search */}
      <button style={{
        all:'unset', cursor:'default', display:'flex', alignItems:'center', gap: 8,
        height: 32, padding: '0 12px', borderRadius: 6,
        border: `1px solid ${T.border}`, background: T.surface01, color: T.text3,
        fontSize: 12,
      }}>
        <Icon name="search" size={14} color={T.text3} />
        <span>Поиск…</span>
        <span style={{
          marginLeft: 16, padding: '1px 5px', borderRadius: 3,
          background: T.card, border:`1px solid ${T.border}`, fontSize: 10, color: T.text3,
        }}>⌘K</span>
      </button>

      <button style={{
        all:'unset', cursor:'default', position:'relative',
        width:32, height:32, borderRadius:6, border:`1px solid ${T.border}`,
        background: T.surface01, display:'flex', alignItems:'center', justifyContent:'center',
      }}>
        <Icon name="bell" size={14} color={T.text2} />
        <span style={{
          position:'absolute', top:6, right:7, width:6, height:6, borderRadius:999,
          background: T.critical, border:`1.5px solid ${T.surface01}`,
        }} />
      </button>

      <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
        <div style={{
          width: 28, height: 28, borderRadius: 999, background: T.surface02,
          color: T.text, display:'flex', alignItems:'center', justifyContent:'center',
          fontSize: 11, fontWeight: 600, border:`1px solid ${T.border}`,
        }}>АК</div>
        <div style={{ display:'flex', flexDirection:'column', lineHeight: 1.15, fontSize: 12 }}>
          <span style={{ fontWeight: 500 }}>А. Котов</span>
          <span style={{ color: T.text2, fontSize: 11 }}>Казначейство · trader-3</span>
        </div>
      </div>
    </div>
  );
}

// ─── Card shell ─────────────────────────────────────────────────────────

function Card({ children, style, padding }) {
  return (
    <div style={{
      background: T.card, border: `1px solid ${T.border}`, borderRadius: 10,
      padding: padding ?? 0, ...style,
    }}>{children}</div>
  );
}

const SectionHead = ({ title, hint, right, style }) => (
  <div style={{
    display:'flex', alignItems:'center', justifyContent:'space-between',
    padding: '14px 18px', borderBottom: `1px solid ${T.border}`, ...style,
  }}>
    <div style={{ display:'flex', alignItems:'baseline', gap: 10 }}>
      <h3 style={{ margin: 0, fontSize: 13, fontWeight: 600, letterSpacing:'0.005em' }}>{title}</h3>
      {hint && <span style={{ fontSize: 11, color: T.text3 }}>{hint}</span>}
    </div>
    {right}
  </div>
);

Object.assign(window, { Sidebar, TopBar, Card, SectionHead, PORTFOLIO, ACTIVITY, ACT_LABEL, TOKENS, POSITIONS });
