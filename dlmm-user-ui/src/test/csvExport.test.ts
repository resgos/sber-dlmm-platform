import { describe, it, expect } from 'vitest'
import { buildCsv, type CsvColumn } from '@/lib/csvExport'

interface Row {
  sym: string | null
  amount: number | null
  note?: string
}

const COLS: CsvColumn<Row>[] = [
  { header: 'Токен', accessor: (r) => r.sym ?? '' },
  { header: 'Сумма', accessor: (r) => r.amount ?? '' },
  { header: 'Прим.', accessor: (r) => r.note ?? '' },
]

describe('buildCsv', () => {
  it('prepends a UTF-8 BOM and joins with CRLF', () => {
    const csv = buildCsv([{ sym: 'SBER', amount: 12.5 }], COLS)
    expect(csv.startsWith('﻿')).toBe(true) // Excel-RU кириллица
    expect(csv).toContain('\r\n')
    const [header, row] = csv.slice(1).split('\r\n')
    expect(header).toBe('Токен,Сумма,Прим.')
    expect(row).toBe('SBER,12.5,')
  })

  it('renders empty string for null/undefined cells', () => {
    const csv = buildCsv([{ sym: null, amount: null }], COLS)
    expect(csv.slice(1).split('\r\n')[1]).toBe(',,')
  })

  it('quotes and escapes cells containing comma, quote, or newline', () => {
    const csv = buildCsv([{ sym: 'A,B', amount: 1, note: 'say "hi"' }], COLS)
    const row = csv.slice(1).split('\r\n')[1]
    expect(row).toBe('"A,B",1,"say ""hi"""')
  })

  it('exports human symbols rather than raw ids (regression for the UUID-dump)', () => {
    const csv = buildCsv(
      [{ sym: 'SGOLD', amount: 100 }, { sym: 'SRUB', amount: 200 }],
      COLS,
    )
    expect(csv).toContain('SGOLD')
    expect(csv).toContain('SRUB')
    // header + 2 data rows
    expect(csv.slice(1).split('\r\n')).toHaveLength(3)
  })
})
