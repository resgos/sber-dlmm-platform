import { describe, it, expect } from 'vitest'
import {
  PET_QUIPS,
  milestoneMessage,
  marketMoodLine,
  petReaction,
} from '@/components/sberkot/sberkotQuips'
import type { TokenPrice } from '@/api/types'

const tp = (symbol: string, price: number, change24h: number): TokenPrice => ({
  tokenId: symbol,
  symbol,
  price,
  change24h,
})

describe('sberkotQuips', () => {
  it('milestoneMessage returns a message only at milestones', () => {
    expect(milestoneMessage(5)).toContain('подружились')
    expect(milestoneMessage(100)).toContain('легенда')
    expect(milestoneMessage(4)).toBeNull()
    expect(milestoneMessage(7)).toBeNull()
  })

  it('marketMoodLine reports the biggest absolute 24h mover', () => {
    const prices = [tp('SBTC', 5_000_000, 0.5), tp('SGOLD', 10_000, -3.2), tp('SUSDT', 71, 0.1)]
    const line = marketMoodLine(prices)
    expect(line).toContain('Золото') // |-3.2| is the biggest move
    expect(line).toContain('-3.20%')
    expect(line).toContain('📉')
  })

  it('marketMoodLine is null with no usable prices', () => {
    expect(marketMoodLine([])).toBeNull()
    expect(marketMoodLine(undefined)).toBeNull()
    expect(marketMoodLine([tp('SBTC', 0, 1)])).toBeNull() // price <= 0 filtered
    expect(marketMoodLine([tp('WAT', 5, 1)])).toBeNull() // unknown symbol filtered
  })

  it('petReaction returns the milestone message at a milestone', () => {
    expect(petReaction(10, undefined, () => 0)).toContain('любимый трейдер')
  })

  it('petReaction uses the market line on every 3rd pet when prices exist', () => {
    const prices = [tp('SETH', 143_000, 2.1)]
    expect(petReaction(3, prices, () => 0)).toContain('Эфир')
    // non-3rd pet → a quip, not the market line
    expect(PET_QUIPS).toContain(petReaction(4, prices, () => 0))
  })

  it('petReaction falls back to a quip when no market line is available', () => {
    expect(PET_QUIPS).toContain(petReaction(3, [], () => 0))
  })
})
