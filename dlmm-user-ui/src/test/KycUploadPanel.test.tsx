import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import KycUploadPanel from '../components/KycUploadPanel'

describe('KycUploadPanel (P2-8)', () => {
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

  it('shows the PENDING info card and no Dragger for PENDING users', () => {
    render(<KycUploadPanel kycStatus="PENDING" />)
    expect(screen.getByText(/Документы на рассмотрении/)).toBeInTheDocument()
    expect(screen.queryByText(/Перетащите файл/)).not.toBeInTheDocument()
  })

  it('shows the VERIFIED info card and no Dragger for VERIFIED users', () => {
    render(<KycUploadPanel kycStatus="VERIFIED" />)
    expect(screen.getByText(/Документы приняты и верифицированы/)).toBeInTheDocument()
    expect(screen.queryByText(/Перетащите файл/)).not.toBeInTheDocument()
  })
})
