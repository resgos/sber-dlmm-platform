// Sber DLMM — Хедж FX page
// Open hedge position cards. Each card = one SRUB → S<FX> swap that can be unwound at any time.

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "density": "regular",
  "sparkline": true,
  "showStrip": true,
  "accent": "#21A038",
  "dark": false
}/*EDITMODE-END*/;

// ─── number formatting (RU locale: space thousands, comma decimal) ──────────
const fmtRub = (n) =>
  new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 0 }).format(Math.round(n));
const fmtTok = (n) =>
  new Intl.NumberFormat("ru-RU", { maximumFractionDigits: 2 }).format(n);
const fmtRate = (n) =>
  new Intl.NumberFormat("ru-RU", { minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(n);
const fmtPct = (n) => {
  const s = new Intl.NumberFormat("ru-RU", {
    minimumFractionDigits: 1, maximumFractionDigits: 2, signDisplay: "exceptZero",
  }).format(n);
  return s + "%";
};
const fmtSigned = (n, suffix) => {
  const s = new Intl.NumberFormat("ru-RU", {
    maximumFractionDigits: 0, signDisplay: "exceptZero",
  }).format(Math.round(n));
  return s + (suffix ? " " + suffix : "");
};

// ─── seeded sparkline (deterministic per id, ends at delta sign) ────────────
function makeSeries(seed, deltaPct, n = 40) {
  let s = 0;
  for (let i = 0; i < seed.length; i++) s = (s * 31 + seed.charCodeAt(i)) >>> 0;
  const rand = () => ((s = (s * 1664525 + 1013904223) >>> 0) & 0xffff) / 0xffff;
  const out = [];
  let v = 1;
  for (let i = 0; i < n; i++) {
    const drift = (deltaPct / 100) * (i / (n - 1));
    const noise = (rand() - 0.5) * 0.008;
    v = 1 + drift + noise * (1 - i / n);
    out.push(v);
  }
  out[n - 1] = 1 + deltaPct / 100;
  return out;
}

function Sparkline({ seed, deltaPct, positive }) {
  const pts = React.useMemo(() => makeSeries(seed, deltaPct), [seed, deltaPct]);
  const w = 140, h = 44, pad = 4;
  const min = Math.min(...pts), max = Math.max(...pts);
  const range = max - min || 0.0001;
  const xs = (i) => pad + (i * (w - pad * 2)) / (pts.length - 1);
  const ys = (v) => pad + (h - pad * 2) * (1 - (v - min) / range);
  const d = pts.map((v, i) => (i === 0 ? "M" : "L") + xs(i).toFixed(1) + "," + ys(v).toFixed(1)).join(" ");
  const baselineY = ys(1);
  const stroke = positive ? "#21A038" : "#DC2626";
  const fillId = `sp-${seed}`;
  const lastX = xs(pts.length - 1), lastY = ys(pts[pts.length - 1]);
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="spark" preserveAspectRatio="none">
      <defs>
        <linearGradient id={fillId} x1="0" x2="0" y1="0" y2="1">
          <stop offset="0%" stopColor={stroke} stopOpacity="0.18" />
          <stop offset="100%" stopColor={stroke} stopOpacity="0" />
        </linearGradient>
      </defs>
      <line x1="0" x2={w} y1={baselineY} y2={baselineY}
            stroke="currentColor" strokeOpacity="0.18" strokeDasharray="2 3" strokeWidth="1" />
      <path d={`${d} L ${xs(pts.length - 1)},${h - pad} L ${pad},${h - pad} Z`}
            fill={`url(#${fillId})`} />
      <path d={d} fill="none" stroke={stroke} strokeWidth="1.5"
            strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={lastX} cy={lastY} r="2.6" fill={stroke} />
      <circle cx={lastX} cy={lastY} r="5" fill={stroke} fillOpacity="0.18" />
    </svg>
  );
}

// ─── tiny token glyph ───────────────────────────────────────────────────────
function Token({ symbol }) {
  const letter = symbol.replace("S", "").charAt(0) || symbol.charAt(0);
  return <span className="tok">{letter}</span>;
}

function PairPill({ to }) {
  return (
    <span className="pair-pill">
      <span className="from"><Token symbol="SRUB" />SRUB</span>
      <span className="arrow" aria-hidden="true">→</span>
      <span className="to"><Token symbol={to} />{to}</span>
    </span>
  );
}

// ─── position card ──────────────────────────────────────────────────────────
function PositionCard({ p, sparkline, onClose }) {
  const [expanded, setExpanded] = React.useState(false);
  const [confirming, setConfirming] = React.useState(false);
  const [state, setState] = React.useState("open"); // open | closing | closed
  const cardRef = React.useRef(null);

  React.useEffect(() => {
    if (!confirming) return undefined;
    const onDoc = (e) => {
      if (cardRef.current && !cardRef.current.contains(e.target)) setConfirming(false);
    };
    const onEsc = (e) => { if (e.key === "Escape") setConfirming(false); };
    document.addEventListener("mousedown", onDoc);
    document.addEventListener("keydown", onEsc);
    return () => {
      document.removeEventListener("mousedown", onDoc);
      document.removeEventListener("keydown", onEsc);
    };
  }, [confirming]);

  const delta = p.currentRub - p.openRub;
  const positive = delta >= 0;
  const deltaPct = (delta / p.openRub) * 100;

  const confirmClose = () => {
    setConfirming(false);
    setState("closing");
    setTimeout(() => {
      setState("closed");
      onClose?.(p.id);
    }, 900);
  };

  return (
    <article ref={cardRef} className="position" data-state={state}>
      <div className="position-row">
        {/* col 1 — identity */}
        <div className="pair-block">
          <PairPill to={p.to} />
          <div className="meta">
            <span>Открыт {p.openedAt}</span>
            <span className="sep" />
            <span className="id">#{p.id}</span>
          </div>
        </div>

        {/* col 2 — amount + equivalent */}
        <div className="amount-block">
          <div className="amount num">
            {fmtTok(p.amount)}<span className="unit">{p.to}</span>
          </div>
          <div className="eq">
            <span>= {fmtRub(p.openRub)} ₽</span>
            <span className="sep" />
            <span className="rate">курс 1 {p.fx} = {fmtRate(p.openRate)} SRUB</span>
          </div>
        </div>

        {/* col 3 — sparkline */}
        <div style={{ minWidth: 0 }}>
          {sparkline ? (
            <>
              <Sparkline seed={p.id} deltaPct={deltaPct} positive={positive} />
              <div className="spark-label">
                <span>30д</span>
                <span>сейчас</span>
              </div>
            </>
          ) : (
            <div style={{ fontSize: 12, color: "var(--text-3)" }}>
              Срок: {p.daysOpen} дн.
            </div>
          )}
        </div>

        {/* col 4 — current value */}
        <div className="value-block">
          <div className="label">Текущая стоимость</div>
          <div className="value num">
            {fmtRub(p.currentRub)}<span className="unit">₽</span>
          </div>
          <div className="delta">
            <span className={"delta-abs " + (positive ? "pos" : "neg")}>
              {fmtSigned(delta, "₽")}
            </span>
            <span className={"delta-pill " + (positive ? "pos" : "neg")}>
              {fmtPct(deltaPct)}
            </span>
          </div>
        </div>

        {/* col 5 — actions */}
        <div className="actions-block">
          <button
            className="btn btn-primary"
            onClick={() => setConfirming((v) => !v)}
            disabled={state !== "open"}
          >
            {state === "closing" ? "Закрытие…" : state === "closed" ? "Закрыто" : "Закрыть хедж"}
          </button>
          <button className="btn btn-ghost" onClick={() => setExpanded((v) => !v)}>
            {expanded ? "Свернуть" : "Подробнее"}
            <svg width="10" height="10" viewBox="0 0 10 10" aria-hidden="true"
                 style={{ transform: expanded ? "rotate(180deg)" : "none", transition: "transform 120ms ease" }}>
              <path d="M1.5 3.5L5 7L8.5 3.5" stroke="currentColor" strokeWidth="1.4"
                    fill="none" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
        </div>
      </div>

      {expanded && (
        <div className="details">
          <div className="kv">
            <div className="k">Текущий курс</div>
            <div className="v num">1 {p.fx} = {fmtRate(p.currentRate)} SRUB</div>
          </div>
          <div className="kv">
            <div className="k">Срок открытия</div>
            <div className="v num">{p.daysOpen} дн.</div>
          </div>
          <div className="kv">
            <div className="k">Контрагент / пул</div>
            <div className="v">{p.pool}</div>
          </div>
          <div className="kv">
            <div className="k">Бенчмарк</div>
            <div className="v">{p.benchmark}</div>
          </div>
          <div className="kv">
            <div className="k">Комиссия пула</div>
            <div className="v num">{fmtRate(p.fee)}%</div>
          </div>
          <div className="kv">
            <div className="k">Авто-ролл</div>
            <div className="v">{p.autoroll ? "включён" : "—"}</div>
          </div>
          <div className="kv">
            <div className="k">Внутренний реф.</div>
            <div className="v mono">{p.ref}</div>
          </div>
          <div className="kv">
            <div className="k">Ответственный</div>
            <div className="v">{p.owner}</div>
          </div>
        </div>
      )}

      {confirming && (
        <div className="confirm" role="dialog" aria-label="Подтверждение закрытия">
          <div className="h">Закрыть хедж?</div>
          <div className="b">
            Позиция будет конвертирована из {p.to} обратно в SRUB по текущему курсу.
            Операция необратима.
          </div>
          <div className="recap">
            <div className="row">
              <span className="l">Возврат</span>
              <span className="r num">{fmtTok(p.amount)} {p.to} → {fmtRub(p.currentRub)} ₽</span>
            </div>
            <div className="row">
              <span className="l">Результат</span>
              <span className={"r num " + (positive ? "delta-pos" : "delta-neg")}>
                {fmtSigned(delta, "₽")} ({fmtPct(deltaPct)})
              </span>
            </div>
            <div className="row">
              <span className="l">Курс</span>
              <span className="r num">1 {p.fx} = {fmtRate(p.currentRate)} SRUB</span>
            </div>
          </div>
          <div className="actions">
            <button className="btn btn-soft" onClick={() => setConfirming(false)}>Отмена</button>
            <button className="btn btn-primary" onClick={confirmClose}>Подтвердить</button>
          </div>
        </div>
      )}
    </article>
  );
}

// ─── data ───────────────────────────────────────────────────────────────────
const POSITIONS = [
  {
    id: "HX-2841",
    to: "SUSDT", fx: "USDT",
    openedAt: "19.05.2026",
    daysOpen: 0,
    amount: 52631,
    openRub: 5_000_000,
    openRate: 95.20,
    currentRate: 96.15,
    currentRub: 5_050_000,
    pool: "DLMM SUSDT/SRUB · бин 95.0–97.0",
    benchmark: "ЦБ РФ + MOEX",
    fee: 0.05,
    autoroll: false,
    ref: "TR-FX-26-0512",
    owner: "А. Морозова",
  },
  {
    id: "HX-2807",
    to: "SEUR", fx: "EUR",
    openedAt: "02.05.2026",
    daysOpen: 17,
    amount: 96_500,
    openRub: 10_120_000,
    openRate: 104.87,
    currentRate: 107.30,
    currentRub: 10_354_450,
    pool: "DLMM SEUR/SRUB · бин 103–108",
    benchmark: "ЕЦБ-фикс",
    fee: 0.08,
    autoroll: true,
    ref: "TR-FX-26-0428",
    owner: "А. Морозова",
  },
  {
    id: "HX-2775",
    to: "SCNY", fx: "CNY",
    openedAt: "21.04.2026",
    daysOpen: 28,
    amount: 1_842_500,
    openRub: 24_600_000,
    openRate: 13.35,
    currentRate: 13.29,
    currentRub: 24_491_000,
    pool: "DLMM SCNY/SRUB · бин 13.0–13.6",
    benchmark: "PBOC-фикс",
    fee: 0.04,
    autoroll: false,
    ref: "TR-FX-26-0411",
    owner: "Д. Соколов",
  },
  {
    id: "HX-2754",
    to: "SUSDT", fx: "USDT",
    openedAt: "08.04.2026",
    daysOpen: 41,
    amount: 21_000,
    openRub: 1_990_500,
    openRate: 94.78,
    currentRate: 96.15,
    currentRub: 2_019_150,
    pool: "DLMM SUSDT/SRUB · бин 93.0–96.0",
    benchmark: "ЦБ РФ + MOEX",
    fee: 0.05,
    autoroll: true,
    ref: "TR-FX-26-0401",
    owner: "И. Лебедев",
  },
  {
    id: "HX-2698",
    to: "SAED", fx: "AED",
    openedAt: "12.03.2026",
    daysOpen: 68,
    amount: 380_000,
    openRub: 9_880_000,
    openRate: 26.00,
    currentRate: 26.18,
    currentRub: 9_948_400,
    pool: "DLMM SAED/SRUB · бин 25.6–26.4",
    benchmark: "CBUAE-фикс",
    fee: 0.10,
    autoroll: false,
    ref: "TR-FX-26-0307",
    owner: "Д. Соколов",
  },
];

// ─── stats strip ────────────────────────────────────────────────────────────
function StatsStrip({ positions }) {
  const totalOpen = positions.reduce((a, p) => a + p.openRub, 0);
  const totalNow = positions.reduce((a, p) => a + p.currentRub, 0);
  const pnl = totalNow - totalOpen;
  const pnlPct = (pnl / totalOpen) * 100;
  const positive = pnl >= 0;
  const byCcy = positions.reduce((m, p) => ((m[p.to] = (m[p.to] || 0) + 1), m), {});
  const ccyList = Object.entries(byCcy).map(([k, v]) => `${k} · ${v}`).join("  ");

  return (
    <div className="strip">
      <div>
        <div className="k">Открытые позиции</div>
        <div className="v num">{positions.length}</div>
        <div className="sub">{ccyList || "—"}</div>
      </div>
      <div>
        <div className="k">Захеджировано (на открытие)</div>
        <div className="v num">{fmtRub(totalOpen)}<span className="unit">₽</span></div>
        <div className="sub">по курсам открытия</div>
      </div>
      <div>
        <div className="k">Текущая стоимость</div>
        <div className="v num">{fmtRub(totalNow)}<span className="unit">₽</span></div>
        <div className="sub">переоценка 19.05.2026, 14:32</div>
      </div>
      <div>
        <div className="k">Совокупный P&amp;L</div>
        <div className={"v num " + (positive ? "delta-pos" : "delta-neg")}>
          {fmtSigned(pnl, "₽")}
        </div>
        <div className={"sub " + (positive ? "delta-pos" : "delta-neg")}>
          {fmtPct(pnlPct)} к открытию
        </div>
      </div>
    </div>
  );
}

// ─── filter chips ───────────────────────────────────────────────────────────
function Toolbar({ filter, setFilter, currencies }) {
  return (
    <div className="toolbar">
      <div className="filter-group">
        <button className={"chip " + (filter === "all" ? "active" : "")}
                onClick={() => setFilter("all")}>
          <span className="dot" />Все
        </button>
        {currencies.map((c) => (
          <button key={c} className={"chip " + (filter === c ? "active" : "")}
                  onClick={() => setFilter(c)}>
            {c}
          </button>
        ))}
      </div>
      <div className="toolbar-spacer" />
      <input className="search" placeholder="Поиск по ID, контрагенту, реф…" />
      <button className="btn btn-soft" type="button">
        <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M2 4h12M4 8h8M6 12h4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
        Фильтры
      </button>
      <button className="btn btn-soft" type="button">
        <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
          <path d="M8 2v8M4 6l4 4 4-4M3 13h10" stroke="currentColor" strokeWidth="1.6"
                fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        Экспорт
      </button>
    </div>
  );
}

// ─── app ────────────────────────────────────────────────────────────────────
function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [filter, setFilter] = React.useState("all");
  const [closed, setClosed] = React.useState(new Set());

  React.useEffect(() => {
    document.documentElement.style.setProperty("--green", t.accent);
    // derive hover/tint from accent
    const dark = shade(t.accent, -0.15);
    document.documentElement.style.setProperty("--green-hover", dark);
    document.documentElement.style.setProperty("--green-tint", tint(t.accent, 0.86));
    document.documentElement.style.setProperty("--green-tint-strong", tint(t.accent, 0.72));
  }, [t.accent]);

  React.useEffect(() => {
    document.documentElement.setAttribute("data-density", t.density);
    document.documentElement.setAttribute("data-dark", t.dark ? "true" : "false");
  }, [t.density, t.dark]);

  const currencies = Array.from(new Set(POSITIONS.map((p) => p.to)));
  const filtered = POSITIONS.filter((p) => filter === "all" || p.to === filter);

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <div className="brand-mark" style={{ background: t.accent }}>S</div>
          <span>Sber DLMM</span>
          <span className="brand-sub">/ Treasury</span>
        </div>
        <nav className="nav" aria-label="Главное меню">
          <a href="#">Обзор</a>
          <a href="#">Ликвидность</a>
          <a href="#" className="active">Хедж FX</a>
          <a href="#">Платежи</a>
          <a href="#">Отчётность</a>
        </nav>
        <div className="topbar-right">
          <button className="btn btn-ghost" aria-label="Уведомления">
            <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true">
              <path d="M3.5 12V7a4.5 4.5 0 0 1 9 0v5M2 12h12M6.5 14a1.5 1.5 0 0 0 3 0"
                    stroke="currentColor" strokeWidth="1.4" fill="none"
                    strokeLinecap="round" strokeLinejoin="round" />
            </svg>
          </button>
          <div className="acct">
            <span className="avi">ТР</span>
            <span>ООО «Трансрезерв»</span>
          </div>
        </div>
      </header>

      <main className="page" data-screen-label="01 Хедж FX">
        <div className="page-head">
          <div>
            <div className="breadcrumb">Treasury · <b>Хедж FX</b></div>
            <h1>Открытые хедж-позиции</h1>
            <div className="lede">
              Свопы из SRUB в иностранные токены. Каждую позицию можно развернуть
              обратно в любой момент по текущему курсу пула.
            </div>
          </div>
          <div style={{ display: "flex", gap: 8 }}>
            <button className="btn btn-soft">История</button>
            <button className="btn btn-primary">
              <svg width="14" height="14" viewBox="0 0 16 16" aria-hidden="true">
                <path d="M8 3v10M3 8h10" stroke="currentColor" strokeWidth="1.8"
                      strokeLinecap="round" />
              </svg>
              Открыть позицию
            </button>
          </div>
        </div>

        {t.showStrip && <StatsStrip positions={POSITIONS} />}

        <Toolbar filter={filter} setFilter={setFilter} currencies={currencies} />

        <div className="list">
          {filtered.map((p) => (
            <PositionCard
              key={p.id}
              p={p}
              sparkline={t.sparkline}
              onClose={(id) => setClosed((s) => new Set(s).add(id))}
            />
          ))}
        </div>

        <div className="foot">
          Обновлено 19.05.2026, 14:32 · Котировки: DLMM internal · Бенчмарк: ЦБ РФ
        </div>
      </main>

      <TweaksPanel title="Tweaks">
        <TweakSection label="Карточка">
          <TweakRadio label="Плотность" value={t.density}
                      options={[{ value: "compact", label: "Компакт" },
                                { value: "regular", label: "Обычная" },
                                { value: "spacious", label: "Свобода" }]}
                      onChange={(v) => setTweak("density", v)} />
          <TweakToggle label="Спарклайн P&L"
                       value={t.sparkline}
                       onChange={(v) => setTweak("sparkline", v)} />
          <TweakToggle label="Сводная строка"
                       value={t.showStrip}
                       onChange={(v) => setTweak("showStrip", v)} />
        </TweakSection>
        <TweakSection label="Тема">
          <TweakColor label="Акцент" value={t.accent}
                      options={["#21A038", "#0F8F2A", "#157A28", "#2A6FDB", "#7A5AE0"]}
                      onChange={(v) => setTweak("accent", v)} />
          <TweakToggle label="Тёмная тема"
                       value={t.dark}
                       onChange={(v) => setTweak("dark", v)} />
        </TweakSection>
      </TweaksPanel>
    </div>
  );
}

// ─── tiny color utils ───────────────────────────────────────────────────────
function hexToRgb(h) {
  const x = h.replace("#", "");
  const s = x.length === 3 ? x.replace(/./g, (c) => c + c) : x;
  const n = parseInt(s, 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}
function rgbToHex(r, g, b) {
  const c = (n) => Math.max(0, Math.min(255, Math.round(n))).toString(16).padStart(2, "0");
  return "#" + c(r) + c(g) + c(b);
}
function shade(hex, amt) {
  const [r, g, b] = hexToRgb(hex);
  const f = amt < 0 ? 1 + amt : 1 - amt;
  return amt < 0
    ? rgbToHex(r * f, g * f, b * f)
    : rgbToHex(r + (255 - r) * amt, g + (255 - g) * amt, b + (255 - b) * amt);
}
function tint(hex, amt) {
  const [r, g, b] = hexToRgb(hex);
  return rgbToHex(r + (255 - r) * amt, g + (255 - g) * amt, b + (255 - b) * amt);
}

ReactDOM.createRoot(document.getElementById("root")).render(<App />);
