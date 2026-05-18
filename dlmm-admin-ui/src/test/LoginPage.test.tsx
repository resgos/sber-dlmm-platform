import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const navigateMock = vi.fn()
const loginMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/api/services', () => ({
  auth: {
    login: (...args: unknown[]) => loginMock(...args),
  },
}))

import LoginPage from '../pages/LoginPage'
import { authStore } from '../store/authStore'

describe('LoginPage (admin-ui)', () => {
  beforeEach(() => {
    navigateMock.mockReset()
    loginMock.mockReset()
    authStore.logout()
  })

  function renderPage() {
    return render(
      <MemoryRouter initialEntries={['/login']}>
        <LoginPage />
      </MemoryRouter>,
    )
  }

  it('renders Sber brand heading and admin subtitle', () => {
    renderPage()
    expect(screen.getByText('СБЕР')).toBeInTheDocument()
    expect(screen.getByText('DLMM')).toBeInTheDocument()
    expect(screen.getByText('Панель администратора платформы')).toBeInTheDocument()
  })

  it('shows required-field validation when submitting empty form', async () => {
    renderPage()
    const submit = screen.getByRole('button', { name: 'Войти' })

    await userEvent.click(submit)

    // AntD Form renders the rule.message under each invalid field.
    expect(await screen.findByText('Введите электронную почту')).toBeInTheDocument()
    expect(await screen.findByText('Введите пароль')).toBeInTheDocument()
    expect(loginMock).not.toHaveBeenCalled()
  })

  it('successful login persists token + navigates to /dashboard', async () => {
    loginMock.mockResolvedValue({
      accessToken: 'fake-jwt',
      refreshToken: 'fake-refresh',
      user: { id: 'u-1', email: 'admin@sber.ru', role: 'ADMIN' },
    })
    renderPage()

    await userEvent.type(screen.getByLabelText('Электронная почта'), 'admin@sber.ru')
    await userEvent.type(screen.getByLabelText('Пароль'), 'correct-pw')
    await userEvent.click(screen.getByRole('button', { name: 'Войти' }))

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('admin@sber.ru', 'correct-pw'))
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/dashboard', { replace: true }))
    expect(authStore.getToken()).toBe('fake-jwt')
    expect(authStore.getUser()).toEqual({
      userId: 'u-1',
      email: 'admin@sber.ru',
      role: 'ADMIN',
    })
  })

  it('on 401 surfaces server error, keeps user on page, no token written', async () => {
    loginMock.mockRejectedValue({
      response: { data: { message: 'Неверный email или пароль' } },
    })
    renderPage()

    await userEvent.type(screen.getByLabelText('Электронная почта'), 'admin@sber.ru')
    await userEvent.type(screen.getByLabelText('Пароль'), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Войти' }))

    expect(await screen.findByText('Неверный email или пароль')).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
    expect(authStore.getToken()).toBeNull()
  })
})
