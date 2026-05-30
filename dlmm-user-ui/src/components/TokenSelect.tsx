import { useMemo, useState } from 'react'
import { Modal, Input, Empty } from 'antd'
import { SearchOutlined, DownOutlined } from '@ant-design/icons'
import TokenIcon from './TokenIcon'

/**
 * Sprint 10 — proper token picker, replacing the bare AntD `<Select showSearch>`
 * used on SwapPage + SimpleTradePage (cramped plain-text dropdown, no icons /
 * names / balances). Opens a modal with a search box and rich rows
 * (coin glyph · symbol · name · balance), tokens you hold sorted first.
 */
export interface TokenSelectItem {
  id: string
  symbol: string
  name?: string
  /** balance in whole token units; shown on the row + used to sort held first */
  available?: number
}

export default function TokenSelect({
  value,
  onChange,
  tokens,
  excludeId,
  placeholder = 'Выбрать токен',
  disabled = false,
}: {
  value?: string | null
  onChange: (id: string) => void
  tokens: TokenSelectItem[]
  excludeId?: string
  placeholder?: string
  disabled?: boolean
}) {
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')

  const selected = tokens.find((t) => t.id === value)

  const list = useMemo(() => {
    const query = q.trim().toLowerCase()
    return tokens
      .filter((t) => t.id !== excludeId)
      .filter(
        (t) =>
          !query ||
          t.symbol.toLowerCase().includes(query) ||
          (t.name || '').toLowerCase().includes(query),
      )
      .sort(
        (a, b) =>
          (b.available ?? 0) - (a.available ?? 0) || a.symbol.localeCompare(b.symbol),
      )
  }, [tokens, q, excludeId])

  const close = () => {
    setOpen(false)
    setQ('')
  }

  return (
    <>
      <button
        type="button"
        className={`sber-tokensel-trigger${selected ? '' : ' sber-tokensel-trigger--empty'}`}
        onClick={() => !disabled && setOpen(true)}
        disabled={disabled}
        aria-label={selected ? `Токен ${selected.symbol}` : placeholder}
      >
        {selected ? (
          <>
            <TokenIcon symbol={selected.symbol} size={26} decorative />
            <span className="sber-tokensel-trigger__sym">{selected.symbol}</span>
          </>
        ) : (
          <span className="sber-tokensel-trigger__ph">{placeholder}</span>
        )}
        <DownOutlined className="sber-tokensel-trigger__chev" />
      </button>

      <Modal
        open={open}
        onCancel={close}
        footer={null}
        title="Выберите токен"
        width={420}
        className="sber-tokensel-modal"
        styles={{ body: { padding: 0 } }}
        destroyOnClose
      >
        <div className="sber-tokensel-search">
          <Input
            autoFocus
            allowClear
            size="large"
            placeholder="Поиск по тикеру или названию"
            prefix={<SearchOutlined style={{ color: 'var(--text-muted)' }} />}
            value={q}
            onChange={(e) => setQ(e.target.value)}
          />
        </div>
        <div className="sber-tokensel-list">
          {list.length === 0 ? (
            <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="Ничего не найдено" style={{ padding: 24 }} />
          ) : (
            list.map((t) => (
              <button
                key={t.id}
                type="button"
                className={`sber-tokensel-row${t.id === value ? ' sber-tokensel-row--active' : ''}`}
                onClick={() => {
                  onChange(t.id)
                  close()
                }}
              >
                <TokenIcon symbol={t.symbol} size={36} decorative />
                <div className="sber-tokensel-row__meta">
                  <span className="sber-tokensel-row__sym">{t.symbol}</span>
                  {t.name ? <span className="sber-tokensel-row__name">{t.name}</span> : null}
                </div>
                {t.available != null && t.available > 0 ? (
                  <span className="sber-tokensel-row__bal">{t.available.toLocaleString('ru-RU')}</span>
                ) : null}
              </button>
            ))
          )}
        </div>
      </Modal>
    </>
  )
}
