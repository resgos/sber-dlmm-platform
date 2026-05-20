// DLMM treasurer dashboard — Plasma tokens, single-green-accent rule, tabular nums

const T = {
  pageBg:        '#FAFAF8',
  card:          '#FFFFFF',
  surface01:     '#FAFAFA',
  surface02:     '#F5F5F2',
  text:          '#080808',
  text2:         'rgba(8,8,8,0.56)',
  text3:         'rgba(8,8,8,0.28)',
  text4:         'rgba(8,8,8,0.16)',
  border:        'rgba(8,8,8,0.10)',
  borderStrong:  'rgba(8,8,8,0.16)',
  success:       '#0D8523',
  critical:      '#E31227',
  warning:       '#D14D00',
  brand:         '#21A038',
  successSoft:   'rgba(13,133,35,0.10)',
  criticalSoft:  'rgba(227,18,39,0.09)',
  warningSoft:   'rgba(209,77,0,0.10)',
  brandSoft:     'rgba(33,160,56,0.10)',
};

// ─── helpers ─────────────────────────────────────────────────────────────

const nf = (n, d = 2) => {
  if (n === null || n === undefined || Number.isNaN(n)) return '—';
  const fixed = Math.abs(n).toLocaleString('ru-RU', { minimumFractionDigits: d, maximumFractionDigits: d });
  return (n < 0 ? '−' : '') + fixed;
};
const compact = (n) => {
  if (n === null || n === undefined) return '—';
  const a = Math.abs(n);
  if (a >= 1e9) return (n / 1e9).toLocaleString('ru-RU', { maximumFractionDigits: 2 }) + ' млрд';
  if (a >= 1e6) return (n / 1e6).toLocaleString('ru-RU', { maximumFractionDigits: 2 }) + ' млн';
  if (a >= 1e3) return (n / 1e3).toLocaleString('ru-RU', { maximumFractionDigits: 1 }) + ' тыс.';
  return n.toLocaleString('ru-RU', { maximumFractionDigits: 0 });
};
const signed = (n, d = 2, suffix = '%') => (n >= 0 ? '+' : '−') + Math.abs(n).toLocaleString('ru-RU', { minimumFractionDigits: d, maximumFractionDigits: d }) + suffix;

// ─── token registry ─────────────────────────────────────────────────────

const TOK = {
  SRUB:  { sym: 'SRUB',  name: 'Цифровой рубль',     color: '#3A3A3A' },
  SUSDT: { sym: 'SUSDT', name: 'Токенизир. USDT',    color: '#11997A' },
  SUSDC: { sym: 'SUSDC', name: 'Токенизир. USDC',    color: '#1D6FD8' },
  SBER:  { sym: 'SBER',  name: 'Акция Сбер',         color: '#7A6500' },
  SETH:  { sym: 'SETH',  name: 'Токенизир. ETH',     color: '#5B4FD0' },
  SBTC:  { sym: 'SBTC',  name: 'Токенизир. BTC',     color: '#C26A11' },
  SCNY:  { sym: 'SCNY',  name: 'Цифровой юань',      color: '#B22833' },
};

const Dot = ({ color, size = 6 }) => (
  <span style={{
    display: 'inline-block', width: size, height: size, borderRadius: 999,
    background: color, marginRight: 6, verticalAlign: 'middle', flex: '0 0 auto',
  }} />
);

const TokenChip = ({ sym, size = 'sm' }) => {
  const t = TOK[sym] || { sym, color: '#999' };
  const padY = size === 'sm' ? 2 : 3;
  const padX = size === 'sm' ? 6 : 8;
  const fs = size === 'sm' ? 11 : 12;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', height: size === 'sm' ? 20 : 22,
      padding: `${padY}px ${padX}px`, borderRadius: 4,
      background: T.surface02, border: `1px solid ${T.border}`,
      fontSize: fs, fontWeight: 500, color: T.text, letterSpacing: '0.01em',
      whiteSpace: 'nowrap',
    }}>
      <Dot color={t.color} size={6} />
      {t.sym}
    </span>
  );
};

const PairChip = ({ a, b }) => (
  <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
    <TokenChip sym={a} />
    <span style={{ color: T.text3, fontSize: 11 }}>/</span>
    <TokenChip sym={b} />
  </span>
);

// ─── delta pill ─────────────────────────────────────────────────────────

const DeltaPill = ({ value, suffix = '%', flat = false }) => {
  const positive = value > 0;
  const zero = value === 0;
  const color = zero ? T.text2 : positive ? T.success : T.critical;
  const bg = zero ? T.surface02 : positive ? T.successSoft : T.criticalSoft;
  const arrow = zero ? '·' : positive ? '▲' : '▼';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      height: 20, padding: '0 6px', borderRadius: 4,
      background: flat ? 'transparent' : bg,
      color, fontSize: 11, fontWeight: 500, letterSpacing: '0.01em',
      fontVariantNumeric: 'tabular-nums',
    }}>
      <span style={{ fontSize: 8, lineHeight: 1, transform: 'translateY(-0.5px)' }}>{arrow}</span>
      {Math.abs(value).toLocaleString('ru-RU', { minimumFractionDigits: suffix === '%' ? 2 : 0, maximumFractionDigits: 2 })}{suffix}
    </span>
  );
};

// ─── icons (minimal monoline, 16px) ─────────────────────────────────────

const Icon = ({ name, size = 16, color = 'currentColor', strokeWidth = 1.5 }) => {
  const p = { width: size, height: size, viewBox: '0 0 16 16', fill: 'none', stroke: color, strokeWidth, strokeLinecap: 'round', strokeLinejoin: 'round' };
  switch (name) {
    case 'home':    return <svg {...p}><path d="M2.5 7L8 2.5L13.5 7V13a.5.5 0 0 1-.5.5H10v-4H6v4H3a.5.5 0 0 1-.5-.5V7z"/></svg>;
    case 'swap':    return <svg {...p}><path d="M3 5h9.5M10 2.5L12.5 5L10 7.5M13 11H3.5M6 8.5L3.5 11L6 13.5"/></svg>;
    case 'shield':  return <svg {...p}><path d="M8 2l5 2v4.5C13 11 11 13 8 14 5 13 3 11 3 8.5V4l5-2z"/></svg>;
    case 'pool':    return <svg {...p}><circle cx="6" cy="8" r="3.5"/><circle cx="10" cy="8" r="3.5"/></svg>;
    case 'positions': return <svg {...p}><path d="M2.5 13.5V9.5M6 13.5V6M9.5 13.5V3.5M13 13.5V8"/></svg>;
    case 'tx':      return <svg {...p}><path d="M3 2.5h7L13 5.5v8a.5.5 0 0 1-.5.5h-9a.5.5 0 0 1-.5-.5v-11zM5.5 7h5M5.5 9.5h5M5.5 12h3"/></svg>;
    case 'user':    return <svg {...p}><circle cx="8" cy="6" r="2.5"/><path d="M3 13.5c.7-2 2.5-3.5 5-3.5s4.3 1.5 5 3.5"/></svg>;
    case 'search':  return <svg {...p}><circle cx="7" cy="7" r="4.5"/><path d="M10.5 10.5L13 13"/></svg>;
    case 'bell':    return <svg {...p}><path d="M4 7a4 4 0 1 1 8 0v3l1 2H3l1-2V7zM6.5 13.5a1.5 1.5 0 0 0 3 0"/></svg>;
    case 'chevron': return <svg {...p}><path d="M6 4l4 4-4 4"/></svg>;
    case 'arrow-right': return <svg {...p}><path d="M3 8h10M9 4l4 4-4 4"/></svg>;
    case 'dot-status': return <svg {...p}><circle cx="8" cy="8" r="3" fill={color} stroke="none"/></svg>;
    case 'plus':    return <svg {...p}><path d="M8 3v10M3 8h10"/></svg>;
    case 'filter':  return <svg {...p}><path d="M2.5 3.5h11l-4 5v4l-3 1.5v-5.5l-4-5z"/></svg>;
    case 'download': return <svg {...p}><path d="M8 2.5v8M4.5 7L8 10.5L11.5 7M2.5 13.5h11"/></svg>;
    case 'eye-off': return <svg {...p}><path d="M2 8c1.5-3 4-4.5 6-4.5s4.5 1.5 6 4.5c-1.5 3-4 4.5-6 4.5S3.5 11 2 8zM2 2l12 12"/></svg>;
    case 'spark':   return <svg {...p}><path d="M2.5 11L5 7.5L8 9.5L13.5 4"/></svg>;
    default: return null;
  }
};

// ─── status pill ────────────────────────────────────────────────────────

const StatusPill = ({ kind }) => {
  const map = {
    done:    { label: 'Выполнен',    color: T.success,  bg: T.successSoft },
    pending: { label: 'В обработке', color: T.warning,  bg: T.warningSoft },
    failed:  { label: 'Отклонён',    color: T.critical, bg: T.criticalSoft },
    queued:  { label: 'В очереди',   color: T.text2,    bg: T.surface02 },
  };
  const { label, color, bg } = map[kind] || map.done;
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4, height: 20,
      padding: '0 8px', borderRadius: 4, background: bg, color,
      fontSize: 11, fontWeight: 500, letterSpacing: '0.01em',
    }}>
      <span style={{
        width: 5, height: 5, borderRadius: 999, background: color, flex: '0 0 auto',
      }} />
      {label}
    </span>
  );
};

// ─── sparkline ──────────────────────────────────────────────────────────

const Sparkline = ({ data, width = 92, height = 28, positive = true, baseline = true }) => {
  const min = Math.min(...data), max = Math.max(...data);
  const range = (max - min) || 1;
  const stepX = width / (data.length - 1);
  const pts = data.map((v, i) => [i * stepX, height - ((v - min) / range) * (height - 4) - 2]);
  const path = pts.map((p, i) => (i === 0 ? 'M' : 'L') + p[0].toFixed(1) + ' ' + p[1].toFixed(1)).join(' ');
  const areaPath = path + ` L ${width} ${height} L 0 ${height} Z`;
  const color = positive ? T.success : T.critical;
  const last = pts[pts.length - 1];
  return (
    <svg width={width} height={height} style={{ display: 'block', overflow: 'visible' }}>
      <defs>
        <linearGradient id={`sg-${color}-${width}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={color} stopOpacity="0.18" />
          <stop offset="100%" stopColor={color} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={areaPath} fill={`url(#sg-${color}-${width})`} />
      <path d={path} fill="none" stroke={color} strokeWidth="1.25" strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={last[0]} cy={last[1]} r="2" fill={color} />
    </svg>
  );
};

Object.assign(window, { T, TOK, Dot, TokenChip, PairChip, DeltaPill, Icon, StatusPill, Sparkline, nf, compact, signed });
