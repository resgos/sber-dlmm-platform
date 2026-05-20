/* Sber DLMM UI Kit — primitive components.
   Cosmetic recreations of antd primitives, styled by kit.css. */

const SberLogo = ({ size = 28 }) => (
  <span className="sber-logo" style={{ width: size, height: size }}>
    <svg width={size} height={size} viewBox="0 0 32 32">
      <circle cx="16" cy="16" r="16" fill="#21A038" />
      <path d="M8.5 16L13 20.5L23.5 10" stroke="white" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" fill="none" />
    </svg>
  </span>
);

const Brand = ({ collapsed = false, size = 'sm' }) => {
  const logoSize = size === 'lg' ? 48 : 28;
  return (
    <>
      <SberLogo size={logoSize} />
      {!collapsed && (
        <div className="sidebar-brand">
          <span className="wm">СБЕР <span className="accent">DLMM</span></span>
          <span className="tag">Платформа ликвидности</span>
        </div>
      )}
    </>
  );
};

const Button = ({ kind = 'default', size = 'md', block = false, disabled = false, children, icon, onClick, type, htmlType, ...rest }) => {
  const cls = ['btn', `btn-${kind}`];
  if (size === 'lg') cls.push('btn-lg');
  if (size === 'sm') cls.push('btn-sm');
  if (block) cls.push('btn-block');
  return (
    <button type={htmlType || type || 'button'} className={cls.join(' ')} disabled={disabled} onClick={onClick} {...rest}>
      {icon}{children}
    </button>
  );
};

const Input = ({ size = 'md', prefix, suffix, ...rest }) => {
  if (prefix || suffix) {
    return (
      <span className="input-prefixed" style={{ display: 'block' }}>
        {prefix ? <span className="ic-prefix">{prefix}</span> : null}
        <input className={'input' + (size === 'lg' ? ' input-lg' : '')} {...rest} />
        {suffix ? <span className="ic-suffix">{suffix}</span> : null}
      </span>
    );
  }
  return <input className={'input' + (size === 'lg' ? ' input-lg' : '')} {...rest} />;
};

const Field = ({ label, helper, error, children }) => (
  <div className="field">
    {label && <label className="field-label">{label}</label>}
    {children}
    {(helper || error) && <div className={'field-helper' + (error ? ' t-critical' : '')}>{error || helper}</div>}
  </div>
);

const Tag = ({ kind = 'neutral', dot = false, children }) => (
  <span className={`tag tag-${kind}`}>{dot && <span className="dot" />}{children}</span>
);

const Card = ({ title, extra, children, bodyClass = '', headless = false, ...rest }) => (
  <div className="card" {...rest}>
    {!headless && (title || extra) && (
      <div className="card-head">
        <div className="card-title">{title}</div>
        {extra && <div className="card-extra">{extra}</div>}
      </div>
    )}
    <div className={'card-body ' + bodyClass}>{children}</div>
  </div>
);

const StatCard = ({ title, value, icon, tile = 'green', hoverable = true }) => (
  <div className={'stat' + (hoverable ? ' hoverable' : '')}>
    <div className={'tile tile-' + tile}>{icon}</div>
    <div className="meta">
      <div className="nm">{title}</div>
      <div className="vl">{value}</div>
    </div>
  </div>
);

const Alert = ({ kind = 'info', icon, children, closable = false, onClose }) => {
  const defaultIcons = {
    success: <CheckCircleOutlined size={18} />,
    critical: <CloseCircleOutlined size={18} />,
    warning: <WarningOutlined size={18} />,
    info: <InfoOutlined size={18} />,
  };
  return (
    <div className={'alert alert-' + kind}>
      <span style={{ display: 'inline-flex', marginTop: 1, flexShrink: 0 }}>{icon || defaultIcons[kind]}</span>
      <div style={{ flex: 1 }}>{children}</div>
      {closable && (
        <button onClick={onClose} className="icon-btn" style={{ width: 22, height: 22, color: 'inherit', opacity: 0.6 }}>
          <CloseCircleOutlined size={14} />
        </button>
      )}
    </div>
  );
};

const Modal = ({ title, open, onCancel, onOk, okText = 'OK', cancelText = 'Отмена', okKind = 'primary', children }) => {
  if (!open) return null;
  return (
    <div className="scrim" onClick={onCancel}>
      <div className="modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-head">{title}</div>
        <div className="modal-body">{children}</div>
        <div className="modal-foot">
          <Button kind="default" onClick={onCancel}>{cancelText}</Button>
          <Button kind={okKind} onClick={onOk}>{okText}</Button>
        </div>
      </div>
    </div>
  );
};

const Tabs = ({ items, value, onChange }) => (
  <>
    <div className="tabs" role="tablist">
      {items.map((it) => (
        <div
          key={it.key}
          className={'tab' + (value === it.key ? ' active' : '')}
          onClick={() => onChange(it.key)}
          role="tab"
          aria-selected={value === it.key}
        >
          {it.label}
        </div>
      ))}
    </div>
    {items.find((it) => it.key === value)?.children}
  </>
);

/* ---- Locale helpers ---- */
const fmtNum = (v, frac = 2) =>
  (v ?? 0).toLocaleString('ru-RU', { maximumFractionDigits: frac, minimumFractionDigits: 0 });
const fmtRub = (v) => {
  if (v == null) return '—';
  if (v >= 1_000_000_000) return `${(v / 1_000_000_000).toFixed(2).replace('.', ',')} млрд ₽`;
  if (v >= 1_000_000)     return `${(v / 1_000_000).toFixed(2).replace('.', ',')} млн ₽`;
  if (v >= 1_000)         return `${(v / 1_000).toFixed(1).replace('.', ',')} тыс ₽`;
  return `${v.toLocaleString('ru-RU')} ₽`;
};
const fmtPct = (v, frac = 2) =>
  v == null ? '—' : `${v.toFixed(frac).replace('.', ',')}%`;
const fmtDateTime = (d) => {
  const dt = new Date(d);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(dt.getDate())}.${pad(dt.getMonth() + 1)}.${dt.getFullYear()} ${pad(dt.getHours())}:${pad(dt.getMinutes())}`;
};
const fmtDate = (d) => {
  const dt = new Date(d);
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(dt.getDate())}.${pad(dt.getMonth() + 1)}.${dt.getFullYear()}`;
};
const shortId = (id) => id ? `${id.slice(0, 8)}…${id.slice(-4)}` : '—';

Object.assign(window, {
  SberLogo, Brand,
  Button, Input, Field, Tag, Card, StatCard, Alert, Modal, Tabs,
  fmtNum, fmtRub, fmtPct, fmtDateTime, fmtDate, shortId,
});
