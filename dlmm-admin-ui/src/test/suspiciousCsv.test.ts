import { describe, it, expect } from 'vitest'
import { buildCsv } from '@/lib/csvExport'
import { suspiciousCsvColumns } from '../pages/SuspiciousTransactionsPage'
import type { SuspiciousTransaction } from '@/api/types'

const FIX: SuspiciousTransaction[] = [
  { id: '11111111-1111-1111-1111-111111111111', type: 'SWAP', userId: 'u-1', poolId: 'p-1', amount: 5000000, reason: 'Large single swap > 1M', timestamp: '2026-06-17T10:00:00' },
  { id: '22222222-2222-2222-2222-222222222222', type: 'TRANSFER', userId: 'u-2', poolId: null, amount: 250000, reason: 'Rapid velocity, 12 ops/min', timestamp: '2026-06-17T09:30:00' },
]

const strip = (csv: string) => csv.replace(/^﻿/, '').split('\r\n')

describe('suspicious-transactions CSV export', () => {
  it('header lists the compliance columns', () => {
    expect(strip(buildCsv(FIX, suspiciousCsvColumns))[0]).toBe(
      'ID,Тип,ID пользователя,ID пула,Сумма,Причина,Время',
    )
  })

  it('first row carries type/amount + a formatted timestamp', () => {
    const r = strip(buildCsv(FIX, suspiciousCsvColumns))[1]
    expect(r).toContain('SWAP')
    expect(r).toContain('5000000')
    expect(r).toContain('2026-06-17 10:00:00')
  })

  it('null poolId exports as an empty cell', () => {
    // row 2: ...,u-2,<empty pool>,250000,...
    expect(strip(buildCsv(FIX, suspiciousCsvColumns))[2]).toMatch(/u-2,,250000/)
  })

  it('a reason containing a comma is quoted (CSV-safe)', () => {
    expect(buildCsv(FIX, suspiciousCsvColumns)).toContain('"Rapid velocity, 12 ops/min"')
  })
})
