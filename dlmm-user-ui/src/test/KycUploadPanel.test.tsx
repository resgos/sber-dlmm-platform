import { render, screen, fireEvent } from '@testing-library/react'
import { describe, it, expect, beforeEach } from 'vitest'
import KycUploadPanel from '../components/KycUploadPanel'

describe('KycUploadPanel (P2-8 + F-21)', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('renders the Dragger for NOT_SUBMITTED users', () => {
    render(<KycUploadPanel kycStatus="NOT_SUBMITTED" />)
    expect(screen.getByText(/Документы для верификации/)).toBeInTheDocument()
    // Three required documents.
    expect(screen.getByText(/Паспорт — главный разворот/)).toBeInTheDocument()
    expect(screen.getByText(/Паспорт — страница с регистрацией/)).toBeInTheDocument()
    expect(screen.getByText(/Селфи с паспортом/)).toBeInTheDocument()
    // Submit button is rendered (disabled until all uploaded).
    expect(screen.getByRole('button', { name: /Отправить на верификацию/ })).toBeDisabled()
  })

  it('renders the Dragger for REJECTED users (resubmit flow)', () => {
    render(<KycUploadPanel kycStatus="REJECTED" />)
    expect(screen.getByText(/Документы для верификации/)).toBeInTheDocument()
  })

  it('F-21: REJECTED users see the admin-supplied rejection reason if provided', () => {
    render(<KycUploadPanel kycStatus="REJECTED" rejectionReason="Фото паспорта засвечено, отправьте новый снимок" />)
    expect(screen.getByText(/Фото паспорта засвечено/)).toBeInTheDocument()
    expect(screen.getByText(/Предыдущая заявка отклонена/)).toBeInTheDocument()
  })

  it('F-21: REJECTED users without a reason see a generic guidance card', () => {
    render(<KycUploadPanel kycStatus="REJECTED" />)
    expect(screen.getByText(/Проверьте качество фото/)).toBeInTheDocument()
  })

  it('shows the PENDING info card and no Dragger for PENDING users', () => {
    render(<KycUploadPanel kycStatus="PENDING" />)
    expect(screen.getByText(/Документы на рассмотрении/)).toBeInTheDocument()
    expect(screen.queryByText(/Перетащите файл/)).not.toBeInTheDocument()
  })

  it('shows the VERIFIED info card and "request re-verification" CTA for VERIFIED users', () => {
    render(<KycUploadPanel kycStatus="VERIFIED" />)
    expect(screen.getByText(/Документы приняты и верифицированы/)).toBeInTheDocument()
    // F-21 — re-verification CTA replaces the old "contact support" copy.
    expect(screen.getByRole('button', { name: /Запросить переверификацию/ })).toBeInTheDocument()
    expect(screen.queryByText(/Перетащите файл/)).not.toBeInTheDocument()
  })

  it('F-21: clicking "Запросить переверификацию" → confirm reveals the Dragger', async () => {
    render(<KycUploadPanel kycStatus="VERIFIED" />)
    fireEvent.click(screen.getByRole('button', { name: /Запросить переверификацию/ }))
    // The Popconfirm renders the "Продолжить" button in a popover.
    const confirmButton = await screen.findByRole('button', { name: /Продолжить/ })
    fireEvent.click(confirmButton)
    expect(await screen.findByText(/Переверификация документов/)).toBeInTheDocument()
  })

  it('F-21: cooldown gate blocks submission within the cooldown window', () => {
    // Stamp the cooldown timestamp at "5 minutes ago" — well within
    // the 60-min cooldown horizon.
    localStorage.setItem('dlmm.user.kycLastSubmittedAt', String(Date.now() - 5 * 60 * 1000))
    render(<KycUploadPanel kycStatus="NOT_SUBMITTED" />)
    expect(screen.getByText(/Следующая отправка возможна через/)).toBeInTheDocument()
  })

  it('F-21: cooldown gate clears once the window has passed', () => {
    // Stamp the timestamp at 2 hours ago — past the 1h cooldown.
    localStorage.setItem('dlmm.user.kycLastSubmittedAt', String(Date.now() - 2 * 60 * 60 * 1000))
    render(<KycUploadPanel kycStatus="NOT_SUBMITTED" />)
    expect(screen.queryByText(/Следующая отправка возможна через/)).not.toBeInTheDocument()
  })
})
