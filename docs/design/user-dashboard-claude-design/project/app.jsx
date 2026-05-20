// App entry — composes the dashboard and mounts to #root

const { useState: useStateApp } = React;

const TWEAK_DEFAULTS = /*EDITMODE-BEGIN*/{
  "density":      "regular",
  "showSpasibo":  true,
  "accent":       "#21A038",
  "numberFormat": "ru"
}/*EDITMODE-END*/;

function App() {
  const [t, setTweak] = useTweaks(TWEAK_DEFAULTS);
  const [nav, setNav] = useStateApp('home');

  // density → body data-attr (CSS hooks)
  React.useEffect(() => {
    document.body.dataset.density = t.density;
  }, [t.density]);

  return (
    <div style={{
      minHeight: '100vh', display: 'flex',
      background: T.pageBg, color: T.text,
    }}>
      <Sidebar active={nav} onSelect={setNav} />
      <main style={{ flex: 1, minWidth: 0, display:'flex', flexDirection:'column' }}>
        <TopBar />
        <div style={{
          padding: '20px 24px 32px', display: 'flex', flexDirection: 'column', gap: 16,
        }}>
          {/* Hero */}
          <Hero />

          {/* Activity + QuickActions */}
          <div style={{ display:'flex', gap: 16, alignItems: 'stretch' }}>
            <ActivityCard />
            <QuickActions />
          </div>

          {/* Spasibo */}
          {t.showSpasibo && <SpasiboStrip />}

          {/* Tokens */}
          <TokensTable />

          {/* Positions */}
          <PositionsStrip />

          {/* footer note */}
          <div style={{
            display:'flex', justifyContent:'space-between',
            fontSize: 11, color: T.text3, marginTop: 4, padding: '0 2px',
          }}>
            <span>Данные обновляются раз в 5 сек. Котировки — ориентир. источник Сбер · Мосбиржа.</span>
            <span>Сборка 09:42:18 МСК · сессия 4 ч 12 мин</span>
          </div>
        </div>
      </main>

      <TweaksPanel>
        <TweakSection label="Плотность" />
        <TweakRadio
          label="Сетка"
          value={t.density}
          options={[
            { value: 'dense',   label: 'Плотно' },
            { value: 'regular', label: 'Стандарт' },
            { value: 'comfort', label: 'Свободно' },
          ]}
          onChange={(v) => setTweak('density', v)}
        />
        <TweakSection label="Виджеты" />
        <TweakToggle
          label="Полоса СберСпасибо"
          value={t.showSpasibo}
          onChange={(v) => setTweak('showSpasibo', v)}
        />
      </TweaksPanel>
    </div>
  );
}

ReactDOM.createRoot(document.getElementById('root')).render(<App />);
