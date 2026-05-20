// Dashboard sections — Hero, Activity, QuickActions, Spasibo, Tokens, Positions

const { useState: useState2 } = React;

// ─── HERO ────────────────────────────────────────────────────────────────

function Hero() {
  const p = PORTFOLIO;
  const positive = p.delta24h >= 0;

  const Metric = ({ label, value, sub, last }) => (
    <div style={{
      flex: 1, padding: '0 22px', borderLeft: `1px solid ${T.border}`,
      display:'flex', flexDirection:'column', justifyContent:'center', gap: 4,
      minWidth: 0,
    }}>
      <div style={{
        fontSize: 11, color: T.text2, letterSpacing: '0.04em',
        textTransform: 'uppercase', fontWeight: 500,
      }}>{label}</div>
      <div className="num" style={{
        fontSize: 22, fontWeight: 500, color: T.text, lineHeight: 1.1,
        letterSpacing: '-0.01em',
      }}>{value}</div>
      <div style={{ fontSize: 11, color: T.text3, display:'flex', alignItems:'center', gap: 6 }}>{sub}</div>
    </div>
  );

  return (
    <Card style={{ height: 140, display: 'flex', alignItems: 'stretch' }}>
      {/* Portfolio cell */}
      <div style={{
        flex: '0 0 360px', padding: '0 24px',
        display:'flex', flexDirection:'column', justifyContent:'center', gap: 6,
      }}>
        <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between' }}>
          <span style={{
            fontSize: 11, color: T.text2, letterSpacing: '0.04em',
            textTransform: 'uppercase', fontWeight: 500,
          }}>Портфель · RUB</span>
          <button style={{
            all:'unset', cursor:'default', display:'inline-flex', alignItems:'center', gap: 4,
            fontSize: 11, color: T.text3,
          }}>
            <Icon name="eye-off" size={12} color={T.text3} />
            Скрыть
          </button>
        </div>
        <div className="num" style={{
          fontSize: 34, fontWeight: 500, color: T.text, lineHeight: 1.05,
          letterSpacing: '-0.025em',
        }}>
          12 437 820<span style={{ color: T.text3, fontSize: 26 }}>,40 ₽</span>
        </div>
        <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
          <DeltaPill value={p.delta24hPct} />
          <span className="num" style={{ fontSize: 12, color: positive ? T.success : T.critical }}>
            {positive ? '+' : '−'}{nf(Math.abs(p.delta24h), 2)} ₽
          </span>
          <span style={{ fontSize: 11, color: T.text3 }}>за 24 ч · обновл. {p.asOf}</span>
        </div>
      </div>

      <Metric
        label="Активных позиций"
        value={p.activePositions}
        sub={<><span className="num" style={{ color: T.success, fontWeight: 500 }}>{p.positionsInRange}</span><span>в диапазоне</span><span style={{ color: T.text4 }}>·</span><span className="num" style={{ color: T.critical, fontWeight: 500 }}>{p.activePositions - p.positionsInRange}</span><span>вне</span></>}
      />
      <Metric
        label="Оборот за 24 ч"
        value={<>{nf(p.volume24h, 0)} <span style={{ color: T.text3, fontWeight: 400 }}>₽</span></>}
        sub={<><DeltaPill value={p.volume24hDelta} flat /><span>к ср. за 7 дн.</span></>}
      />
      <Metric
        label="Незабр. комиссии"
        value={<>{nf(p.unclaimedFees, 0)} <span style={{ color: T.text3, fontWeight: 400 }}>₽</span></>}
        sub={<><span className="num" style={{ color: T.text2 }}>{p.unclaimedPools}</span><span>пула</span><span style={{ color: T.text4 }}>·</span><a style={{ color: T.brand, textDecoration: 'none', fontWeight: 500 }}>Забрать</a></>}
      />
      <Metric
        label="Доход за всё время"
        value={<>{compact(p.lifetimeEarnings)} <span style={{ color: T.text3, fontWeight: 400 }}>₽</span></>}
        sub={<><span>за</span><span className="num" style={{ color: T.text2 }}>{p.lifetimeDays}</span><span>дн. · APR <span className="num" style={{ color: T.success, fontWeight:500 }}>14,8%</span></span></>}
      />
    </Card>
  );
}

// ─── ACTIVITY ────────────────────────────────────────────────────────────

function ActivityCard() {
  const [hover, setHover] = useState2(null);
  return (
    <Card style={{ flex: 1, display:'flex', flexDirection: 'column' }}>
      <SectionHead
        title="Последняя активность"
        hint="за сегодня"
        right={
          <a style={{ fontSize: 12, color: T.text2, display:'inline-flex', alignItems:'center', gap: 4, textDecoration: 'none' }}>
            Все транзакции <Icon name="arrow-right" size={12} color={T.text2} />
          </a>
        }
      />
      <div style={{ padding: '4px 0' }}>
        {/* header */}
        <div style={{
          display:'grid', gridTemplateColumns: '54px 1fr 140px 110px', gap: 12,
          padding: '6px 18px', fontSize: 11, color: T.text3, letterSpacing: '0.04em',
          textTransform: 'uppercase', fontWeight: 500,
        }}>
          <div>Время</div>
          <div>Операция</div>
          <div style={{ textAlign:'right' }}>Сумма</div>
          <div>Статус</div>
        </div>
        {ACTIVITY.map((row, i) => (
          <div key={i}
               onMouseEnter={() => setHover(i)}
               onMouseLeave={() => setHover(null)}
               style={{
                 display:'grid', gridTemplateColumns: '54px 1fr 140px 110px', gap: 12,
                 padding: '0 18px', height: 40, alignItems: 'center', fontSize: 13,
                 background: hover === i ? T.surface01 : 'transparent',
                 borderTop: `1px solid ${T.border}`,
               }}>
            <span className="num" style={{ color: T.text2 }}>{row.t}</span>
            <span style={{ display:'inline-flex', alignItems:'center', gap: 10, minWidth: 0 }}>
              <span style={{ color: T.text2, fontSize: 12, minWidth: 100 }}>{ACT_LABEL[row.kind]}</span>
              {row.kind === 'hedge' ? (
                <span style={{ display:'inline-flex', alignItems:'center', gap: 4, fontSize: 12 }}>
                  <span style={{ color: T.text }}>{row.pair[0]}</span>
                  <Icon name="arrow-right" size={12} color={T.text3} />
                  <span style={{ color: T.text }}>{row.pair[1]}</span>
                </span>
              ) : row.kind === 'swap' ? (
                <span style={{ display:'inline-flex', alignItems:'center', gap: 4 }}>
                  <TokenChip sym={row.pair[0]} />
                  <Icon name="arrow-right" size={12} color={T.text3} />
                  <TokenChip sym={row.pair[1]} />
                </span>
              ) : (
                <PairChip a={row.pair[0]} b={row.pair[1]} />
              )}
            </span>
            <span className="num" style={{
              textAlign:'right', color: T.text, fontWeight: 500,
              fontVariantNumeric: 'tabular-nums',
            }}>
              <span style={{ color: row.dir === 'in' ? T.success : T.text3, marginRight: 2 }}>
                {row.dir === 'in' ? '+' : '−'}
              </span>
              {nf(row.amount, 2)} <span style={{ color: T.text3, fontWeight: 400 }}>₽</span>
            </span>
            <span><StatusPill kind={row.status} /></span>
          </div>
        ))}
      </div>
    </Card>
  );
}

// ─── QUICK ACTIONS ──────────────────────────────────────────────────────

function QuickActions() {
  const Btn = ({ primary, label, sub, fromSym, toSym, icon }) => (
    <button style={{
      all:'unset', cursor:'default',
      display:'flex', alignItems:'center', gap: 12,
      padding: '12px 14px', borderRadius: 8,
      background: primary ? T.brand : T.card,
      border: primary ? `1px solid ${T.brand}` : `1px solid ${T.border}`,
      color: primary ? '#fff' : T.text,
      transition: 'all 120ms ease',
    }}
      onMouseEnter={e => {
        if (primary) { e.currentTarget.style.background = '#1B8A2E'; e.currentTarget.style.borderColor = '#1B8A2E'; }
        else { e.currentTarget.style.background = T.surface01; e.currentTarget.style.borderColor = T.borderStrong; }
      }}
      onMouseLeave={e => {
        if (primary) { e.currentTarget.style.background = T.brand; e.currentTarget.style.borderColor = T.brand; }
        else { e.currentTarget.style.background = T.card; e.currentTarget.style.borderColor = T.border; }
      }}
    >
      <div style={{
        width: 32, height: 32, borderRadius: 6, flex:'0 0 32px',
        background: primary ? 'rgba(255,255,255,0.15)' : T.surface02,
        display:'flex', alignItems:'center', justifyContent:'center',
      }}>
        <Icon name={icon} size={16} color={primary ? '#fff' : T.text} />
      </div>
      <div style={{ display:'flex', flexDirection:'column', flex: 1, minWidth: 0, lineHeight: 1.2 }}>
        <span style={{ fontSize: 13, fontWeight: 500 }}>{label}</span>
        <span style={{
          fontSize: 11, marginTop: 3,
          color: primary ? 'rgba(255,255,255,0.78)' : T.text2,
          display:'inline-flex', alignItems:'center', gap: 4,
        }}>{sub}</span>
      </div>
      <Icon name="chevron" size={14} color={primary ? 'rgba(255,255,255,0.78)' : T.text3} />
    </button>
  );

  return (
    <Card style={{ width: 360, flex: '0 0 360px', display:'flex', flexDirection:'column' }}>
      <SectionHead
        title="Быстрые действия"
        right={<span style={{ fontSize: 11, color: T.text3 }}>⌥ + 1‑3</span>}
      />
      <div style={{ padding: 14, display:'flex', flexDirection:'column', gap: 8, flex: 1 }}>
        <Btn primary label="Свопнуть SUSDT → SRUB" sub="Курс 92,40 ₽ · комиссия 0,05%" icon="swap" />
        <Btn label="Открыть хедж USD/RUB" sub="Фьючерс Si‑6.26 · маржа 7,4%" icon="shield" />
        <Btn label="Добавить в пул SBER/SRUB" sub="APR 18,4% · диапазон 296—318 ₽" icon="plus" />
        <div style={{
          marginTop: 'auto', padding: '10px 12px',
          background: T.surface01, borderRadius: 6, border: `1px solid ${T.border}`,
          fontSize: 11, color: T.text2, display:'flex', alignItems:'center', gap: 8,
        }}>
          <span style={{ width:6, height:6, borderRadius:999, background: T.warning, flex:'0 0 6px' }} />
          <span>2 позиции вне диапазона — требуется ребаланс</span>
          <a style={{ color: T.text, marginLeft: 'auto', fontWeight: 500, textDecoration:'none' }}>Открыть →</a>
        </div>
      </div>
    </Card>
  );
}

// ─── SPASIBO STRIP ──────────────────────────────────────────────────────

function SpasiboStrip() {
  return (
    <div style={{
      display:'flex', alignItems:'center', gap: 14,
      height: 56, padding: '0 18px',
      background: T.card, border: `1px solid ${T.border}`, borderRadius: 10,
    }}>
      <div style={{
        width: 32, height: 32, borderRadius: 6, flex: '0 0 32px',
        background: '#F5EEDB', color: '#7A5800',
        display:'flex', alignItems:'center', justifyContent:'center',
        fontWeight: 700, fontSize: 13, letterSpacing: '-0.01em',
      }}>СБ</div>
      <div style={{ display:'flex', flexDirection:'column', lineHeight: 1.2 }}>
        <span style={{ fontSize: 12, color: T.text2 }}>СберСпасибо · корп. программа</span>
        <span style={{ fontSize: 13, fontWeight: 500 }}>Баланс <span className="num">142 480 ₽</span> · кэшбек <span className="num">1,2 %</span> со свопов</span>
      </div>
      <div style={{ width: 1, height: 28, background: T.border, marginLeft: 6 }} />
      <div style={{ display:'flex', flexDirection:'column', lineHeight: 1.2 }}>
        <span style={{ fontSize: 11, color: T.text3, letterSpacing:'0.04em', textTransform:'uppercase' }}>Накоплено за месяц</span>
        <span className="num" style={{ fontSize: 13, fontWeight: 500 }}>+ 24 820 ₽</span>
      </div>
      <div style={{ flex: 1 }} />
      <a style={{ fontSize: 12, color: T.text2, textDecoration:'none', display:'inline-flex', alignItems:'center', gap: 4 }}>
        Условия программы <Icon name="arrow-right" size={12} color={T.text2} />
      </a>
      <button style={{
        all:'unset', cursor:'default', height: 32, padding: '0 14px',
        border:`1px solid ${T.border}`, borderRadius: 6, background: T.surface01,
        fontSize: 12, fontWeight: 500, color: T.text,
      }}>Списать баллы</button>
    </div>
  );
}

// ─── TOKENS TABLE ────────────────────────────────────────────────────────

function TokensTable() {
  const [hover, setHover] = useState2(null);
  const [sort, setSort] = useState2('value');
  const total = TOKENS.reduce((s, t) => s + t.value, 0);

  return (
    <Card>
      <SectionHead
        title="Мои токены"
        hint={`${TOKENS.length} активов · итого ${nf(total, 0)} ₽`}
        right={
          <div style={{ display:'flex', alignItems:'center', gap: 8 }}>
            <div style={{
              display:'inline-flex', height: 28, borderRadius: 6,
              border: `1px solid ${T.border}`, overflow:'hidden',
            }}>
              {['Все', 'С балансом', 'В пулах'].map((t, i) => (
                <button key={t} style={{
                  all:'unset', cursor:'default', padding: '0 12px',
                  fontSize: 12, color: i === 0 ? T.text : T.text2,
                  background: i === 0 ? T.surface02 : 'transparent',
                  borderLeft: i ? `1px solid ${T.border}` : 'none',
                  fontWeight: i === 0 ? 500 : 400,
                  display:'flex', alignItems:'center',
                }}>{t}</button>
              ))}
            </div>
            <button style={{
              all:'unset', cursor:'default', height: 28, padding:'0 10px',
              border:`1px solid ${T.border}`, borderRadius: 6, fontSize: 12, color: T.text2,
              display:'inline-flex', alignItems:'center', gap: 6, background: T.surface01,
            }}>
              <Icon name="filter" size={12} color={T.text2} />
              Фильтр
            </button>
            <button style={{
              all:'unset', cursor:'default', height: 28, padding:'0 10px',
              border:`1px solid ${T.border}`, borderRadius: 6, fontSize: 12, color: T.text2,
              display:'inline-flex', alignItems:'center', gap: 6, background: T.surface01,
            }}>
              <Icon name="download" size={12} color={T.text2} />
              CSV
            </button>
          </div>
        }
      />
      {/* table */}
      <div>
        {/* header */}
        <div style={{
          display:'grid',
          gridTemplateColumns: '220px 1fr 1fr 1fr 130px 1fr 140px',
          padding: '8px 18px', fontSize: 11, color: T.text3,
          letterSpacing: '0.04em', textTransform: 'uppercase', fontWeight: 500,
          background: T.surface01, borderBottom: `1px solid ${T.border}`,
        }}>
          <div>Токен</div>
          <div style={{ textAlign:'right' }}>Доступно</div>
          <div style={{ textAlign:'right' }}>Заблокировано</div>
          <div style={{ textAlign:'right' }}>Цена, ₽</div>
          <div style={{ textAlign:'right' }}>Δ 24 ч</div>
          <div style={{ textAlign:'right' }}>Стоимость, ₽</div>
          <div style={{ textAlign:'right' }}>Доля портфеля</div>
        </div>
        {TOKENS.map((t, i) => {
          const share = (t.value / total) * 100;
          const tk = TOK[t.sym] || {};
          return (
            <div key={t.sym}
                 onMouseEnter={() => setHover(i)}
                 onMouseLeave={() => setHover(null)}
                 style={{
                   display:'grid',
                   gridTemplateColumns: '220px 1fr 1fr 1fr 130px 1fr 140px',
                   padding: '0 18px', height: 40, alignItems:'center',
                   fontSize: 13, borderBottom: `1px solid ${T.border}`,
                   background: hover === i ? T.surface01 : 'transparent',
                 }}>
              <div style={{ display:'flex', alignItems:'center', gap: 10 }}>
                <TokenChip sym={t.sym} />
                <span style={{ fontSize: 12, color: T.text2 }}>{tk.name}</span>
              </div>
              <div className="num" style={{ textAlign:'right', color: T.text, fontWeight: 500 }}>
                {nf(t.available, t.available < 10 ? 4 : 2)}
              </div>
              <div className="num" style={{
                textAlign:'right',
                color: t.locked > 0 ? T.text2 : T.text4,
                fontWeight: t.locked > 0 ? 400 : 400,
              }}>
                {t.locked > 0 ? nf(t.locked, t.locked < 10 ? 4 : 2) : '—'}
              </div>
              <div className="num" style={{ textAlign:'right', color: T.text }}>
                {nf(t.price, t.price >= 10_000 ? 0 : 2)}
              </div>
              <div style={{ textAlign:'right', display:'flex', justifyContent:'flex-end' }}>
                <DeltaPill value={t.delta} />
              </div>
              <div className="num" style={{
                textAlign:'right', color: T.text, fontWeight: 500,
                letterSpacing:'-0.005em',
              }}>
                {nf(t.value, 0)}
              </div>
              <div style={{
                textAlign:'right', display:'flex', alignItems:'center', gap: 8,
                justifyContent:'flex-end',
              }}>
                <div style={{
                  width: 56, height: 5, borderRadius: 999, background: T.surface02,
                  position:'relative', overflow:'hidden',
                }}>
                  <div style={{
                    position:'absolute', inset: '0 auto 0 0',
                    width: `${share}%`, background: T.text2, borderRadius: 999,
                  }} />
                </div>
                <span className="num" style={{ fontSize: 12, color: T.text2, minWidth: 38, textAlign:'right' }}>
                  {share.toLocaleString('ru-RU', { maximumFractionDigits: 1 })}%
                </span>
              </div>
            </div>
          );
        })}
      </div>
    </Card>
  );
}

// ─── POSITIONS STRIP ─────────────────────────────────────────────────────

function PositionsStrip() {
  const Card1 = ({ pos }) => {
    const positive = pos.delta >= 0;
    const inRange = pos.status === 'active';
    // range bar position
    const r = pos.range;
    const pct = Math.max(0, Math.min(100, ((r.cur - r.low) / (r.high - r.low)) * 100));
    const inBounds = pct >= 0 && pct <= 100 && inRange;
    return (
      <div style={{
        flex: 1, padding: 18, border: `1px solid ${T.border}`, borderRadius: 10,
        background: T.card, display:'flex', flexDirection:'column', gap: 12,
        minWidth: 0,
      }}>
        <div style={{ display:'flex', alignItems:'center', justifyContent:'space-between' }}>
          <PairChip a={pos.pair[0]} b={pos.pair[1]} />
          <span style={{
            display:'inline-flex', alignItems:'center', gap: 4, fontSize: 11,
            color: inRange ? T.success : T.warning, fontWeight: 500,
          }}>
            <span style={{ width:6, height:6, borderRadius:999, background: inRange ? T.success : T.warning }} />
            {inRange ? 'В диапазоне' : 'Вне диапазона'}
          </span>
        </div>

        <div style={{ display:'flex', alignItems:'flex-end', justifyContent:'space-between', gap: 12 }}>
          <div style={{ display:'flex', flexDirection:'column', gap: 2 }}>
            <span className="num" style={{ fontSize: 22, fontWeight: 500, color: T.text, letterSpacing:'-0.015em', lineHeight: 1.05 }}>
              {nf(pos.value, 0)} <span style={{ color: T.text3, fontSize: 16 }}>₽</span>
            </span>
            <div style={{ display:'flex', alignItems:'center', gap: 6 }}>
              <DeltaPill value={pos.delta} />
              <span style={{ fontSize: 11, color: T.text3 }}>за 24 ч</span>
            </div>
          </div>
          <Sparkline data={pos.spark} positive={positive} width={96} height={32} />
        </div>

        {/* range bar */}
        <div style={{ display:'flex', flexDirection:'column', gap: 6 }}>
          <div style={{ display:'flex', justifyContent:'space-between', fontSize: 10, color: T.text3, letterSpacing:'0.03em', textTransform:'uppercase' }}>
            <span>Диапазон</span>
            <span className="num" style={{ color: T.text2 }}>
              APR {pos.apr.toLocaleString('ru-RU', { minimumFractionDigits: 1 })}%
            </span>
          </div>
          <div style={{ position:'relative', height: 6, background: T.surface02, borderRadius: 999, overflow:'hidden' }}>
            <div style={{
              position:'absolute', inset: '0 auto 0 0', width: '100%',
              background: `linear-gradient(90deg, transparent 0%, ${T.brandSoft} 20%, ${T.brandSoft} 80%, transparent 100%)`,
            }} />
            {inBounds && (
              <div style={{
                position:'absolute', top: -2, bottom: -2, width: 2,
                left: `calc(${pct}% - 1px)`, background: T.text, borderRadius: 1,
              }} />
            )}
            {!inBounds && (
              <div style={{
                position:'absolute', top: -2, bottom: -2, width: 2,
                left: pct < 0 ? '0' : 'calc(100% - 2px)',
                background: T.warning, borderRadius: 1,
              }} />
            )}
          </div>
          <div style={{ display:'flex', justifyContent:'space-between', fontSize: 11 }}>
            <span className="num" style={{ color: T.text2 }}>{nf(r.low, r.low < 10 ? 4 : 2)}</span>
            <span className="num" style={{ color: T.text, fontWeight: 500 }}>{nf(r.cur, r.cur < 10 ? 4 : 2)}</span>
            <span className="num" style={{ color: T.text2 }}>{nf(r.high, r.high < 10 ? 4 : 2)}</span>
          </div>
        </div>
      </div>
    );
  };

  return (
    <Card>
      <SectionHead
        title="Мои позиции"
        hint="топ 3 по объёму"
        right={
          <a style={{ fontSize: 12, color: T.text2, display:'inline-flex', alignItems:'center', gap: 4, textDecoration:'none' }}>
            Все позиции <Icon name="arrow-right" size={12} color={T.text2} />
          </a>
        }
      />
      <div style={{ display:'flex', gap: 12, padding: 14 }}>
        {POSITIONS.map((p, i) => <Card1 key={i} pos={p} />)}
      </div>
    </Card>
  );
}

Object.assign(window, { Hero, ActivityCard, QuickActions, SpasiboStrip, TokensTable, PositionsStrip });
