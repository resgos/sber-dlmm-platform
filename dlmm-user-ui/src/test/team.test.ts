import { describe, it, expect, beforeEach, vi } from 'vitest'

// Mock the apiClient module BEFORE importing the store so the store's
// imported `apiClient` reference points at the mock.
vi.mock('@/api/client', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}))

import { teamStore, canPerform } from '../store/teamStore'
import apiClient from '@/api/client'

const mockedApi = apiClient as unknown as {
  get: ReturnType<typeof vi.fn>
  post: ReturnType<typeof vi.fn>
  put: ReturnType<typeof vi.fn>
  delete: ReturnType<typeof vi.fn>
}

/** Flush queued microtasks (pending Promise then/catch handlers).
 *  Five cycles covers the deepest then/catch chain in the store —
 *  create() chains POST → GET → setState. */
async function flushPromises(): Promise<void> {
  for (let i = 0; i < 5; i++) await Promise.resolve()
}

/**
 * Sprint 11 G-21 — teamStore tests (backend swap-in).
 *
 * <p>Before: localStorage stub with synchronous semantics. After: the
 * store proxies to backend endpoints (POST/GET /api/v1/orgs +
 * /{id}/members). Sync public API is preserved for backward compat
 * with TeamPage.tsx, so tests stay mostly synchronous and use
 * flushPromises() to wait for the fire-and-forget axios calls'
 * then/catch handlers.
 */
describe('teamStore (G-21 backend-backed)', () => {
  const ORG_ID = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
  const OWNER_ID = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'

  beforeEach(() => {
    teamStore.__resetForTests()
    mockedApi.get.mockReset()
    mockedApi.post.mockReset()
    mockedApi.put.mockReset()
    mockedApi.delete.mockReset()
  })

  it('starts with no org', () => {
    expect(teamStore.get().orgName).toBe('')
    expect(teamStore.get().members).toEqual([])
  })

  it('create initialises org + OWNER member optimistically, reconciles after backend', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: { id: ORG_ID, name: 'Транспортхолдинг', ownerId: 'u-1', createdAt: '2026-05-22T10:00:00' },
    })
    mockedApi.get.mockResolvedValueOnce({
      data: [{
        id: OWNER_ID, orgId: ORG_ID, userId: 'u-1',
        email: 'av@th.ru', name: 'Александр В.',
        role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-05-22T10:00:00',
      }],
    })
    const s = teamStore.create('Транспортхолдинг', 'av@th.ru', 'Александр В.')
    // Optimistic state visible synchronously.
    expect(s.orgName).toBe('Транспортхолдинг')
    expect(s.members).toHaveLength(1)
    expect(s.members[0].role).toBe('OWNER')
    expect(s.members[0].email).toBe('av@th.ru')
    expect(s.ownerId).toBe(s.members[0].id)

    await flushPromises()
    // Reconciled view from server.
    const reconciled = teamStore.get()
    expect(reconciled.orgName).toBe('Транспортхолдинг')
    expect(reconciled.members[0].id).toBe(OWNER_ID)
    expect(reconciled.ownerId).toBe(OWNER_ID)
    expect(mockedApi.post).toHaveBeenCalledWith('/orgs', {
      name: 'Транспортхолдинг',
      ownerEmail: 'av@th.ru',
      ownerName: 'Александр В.',
    })
  })

  it('create: reverts to DEFAULT_STATE if backend rejects', async () => {
    mockedApi.post.mockRejectedValueOnce({ response: { status: 409 } })
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    expect(teamStore.get().orgName).toBe('Org') // optimistic
    await flushPromises()
    expect(teamStore.get().orgName).toBe('')
    expect(teamStore.get().members).toEqual([])
  })

  it('invite adds a PENDING member optimistically, fires POST', async () => {
    // Seed an org via create.
    mockedApi.post.mockResolvedValueOnce({
      data: { id: ORG_ID, name: 'Org', ownerId: 'u-1', createdAt: '2026-05-22T10:00:00' },
    })
    mockedApi.get.mockResolvedValueOnce({
      data: [{
        id: OWNER_ID, orgId: ORG_ID, userId: 'u-1',
        email: 'a@a.ru', name: 'Alpha',
        role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-05-22T10:00:00',
      }],
    })
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    await flushPromises()

    mockedApi.post.mockResolvedValueOnce({
      data: {
        id: 'm-2', orgId: ORG_ID, userId: null,
        email: 'b@b.ru', name: 'Beta',
        role: 'FINANCE_MGR', status: 'PENDING', joinedAt: '2026-05-22T11:00:00',
      },
    })
    const m = teamStore.invite('b@b.ru', 'Beta', 'FINANCE_MGR')
    expect(m).not.toBeNull()
    expect(m!.status).toBe('PENDING')
    expect(teamStore.get().members).toHaveLength(2)

    await flushPromises()
    // Placeholder row replaced with canonical server row in-place — no
    // extra /members refetch.
    expect(teamStore.get().members.find((x) => x.email === 'b@b.ru')?.id).toBe('m-2')
    expect(mockedApi.post).toHaveBeenLastCalledWith(`/orgs/${ORG_ID}/members`, {
      email: 'b@b.ru',
      name: 'Beta',
      role: 'FINANCE_MGR',
    })
  })

  it('invite refuses duplicate emails (client-side guard, no HTTP fired)', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: { id: ORG_ID, name: 'Org', ownerId: 'u-1', createdAt: '2026-05-22T10:00:00' },
    })
    mockedApi.get.mockResolvedValueOnce({
      data: [{
        id: OWNER_ID, orgId: ORG_ID, userId: 'u-1',
        email: 'a@a.ru', name: 'Alpha',
        role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-05-22T10:00:00',
      }, {
        id: 'm-2', orgId: ORG_ID, userId: null,
        email: 'b@b.ru', name: 'Beta',
        role: 'FINANCE_MGR', status: 'PENDING', joinedAt: '2026-05-22T11:00:00',
      }],
    })
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    await flushPromises()

    expect(teamStore.invite('b@b.ru', 'Beta2', 'ACCOUNTANT')).toBeNull()
    expect(teamStore.get().members).toHaveLength(2)
    // Only the initial /orgs POST, no /members POST.
    expect(mockedApi.post.mock.calls.filter((c) => c[0] === `/orgs/${ORG_ID}/members`)).toHaveLength(0)
  })

  it('invite fails without an org', () => {
    expect(teamStore.invite('a@a.ru', 'A', 'VIEWER')).toBeNull()
    expect(mockedApi.post).not.toHaveBeenCalled()
  })

  it('acceptInvite flips PENDING → ACTIVE optimistically', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: { id: ORG_ID, name: 'Org', ownerId: 'u-1', createdAt: '2026-05-22T10:00:00' },
    })
    mockedApi.get.mockResolvedValueOnce({
      data: [{
        id: OWNER_ID, orgId: ORG_ID, userId: 'u-1',
        email: 'a@a.ru', name: 'Alpha',
        role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-05-22T10:00:00',
      }, {
        id: 'm-2', orgId: ORG_ID, userId: null,
        email: 'b@b.ru', name: 'Beta',
        role: 'FINANCE_MGR', status: 'PENDING', joinedAt: '2026-05-22T11:00:00',
      }],
    })
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    await flushPromises()

    mockedApi.post.mockResolvedValueOnce({ data: {} })
    teamStore.acceptInvite('m-2')
    expect(teamStore.get().members.find((x) => x.id === 'm-2')!.status).toBe('ACTIVE')
  })

  it('remove deletes non-owner optimistically; protects owner', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: { id: ORG_ID, name: 'Org', ownerId: 'u-1', createdAt: '2026-05-22T10:00:00' },
    })
    mockedApi.get.mockResolvedValueOnce({
      data: [{
        id: OWNER_ID, orgId: ORG_ID, userId: 'u-1',
        email: 'a@a.ru', name: 'Alpha',
        role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-05-22T10:00:00',
      }, {
        id: 'm-2', orgId: ORG_ID, userId: null,
        email: 'b@b.ru', name: 'Beta',
        role: 'FINANCE_MGR', status: 'PENDING', joinedAt: '2026-05-22T11:00:00',
      }],
    })
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    await flushPromises()

    mockedApi.delete.mockResolvedValueOnce({ data: {} })
    expect(teamStore.remove('m-2')).toBe(true)
    expect(teamStore.get().members).toHaveLength(1)
    // Try removing OWNER → false, no HTTP fired.
    expect(teamStore.remove(OWNER_ID)).toBe(false)
    expect(mockedApi.delete).toHaveBeenCalledTimes(1)
  })

  it('changeRole works for non-owner; cannot demote OWNER', async () => {
    mockedApi.post.mockResolvedValueOnce({
      data: { id: ORG_ID, name: 'Org', ownerId: 'u-1', createdAt: '2026-05-22T10:00:00' },
    })
    mockedApi.get.mockResolvedValueOnce({
      data: [{
        id: OWNER_ID, orgId: ORG_ID, userId: 'u-1',
        email: 'a@a.ru', name: 'Alpha',
        role: 'OWNER', status: 'ACTIVE', joinedAt: '2026-05-22T10:00:00',
      }, {
        id: 'm-2', orgId: ORG_ID, userId: null,
        email: 'b@b.ru', name: 'Beta',
        role: 'FINANCE_MGR', status: 'PENDING', joinedAt: '2026-05-22T11:00:00',
      }],
    })
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    await flushPromises()

    mockedApi.put.mockResolvedValueOnce({ data: {} })
    teamStore.changeRole('m-2', 'ACCOUNTANT')
    expect(teamStore.get().members.find((x) => x.id === 'm-2')!.role).toBe('ACCOUNTANT')
    // OWNER role cannot be changed via this call.
    expect(teamStore.changeRole(OWNER_ID, 'FINANCE_MGR')).toBe(false)
    expect(mockedApi.put).toHaveBeenCalledTimes(1)
  })
})

describe('canPerform (G-21 ACL)', () => {
  it('OWNER can do everything', () => {
    expect(canPerform('OWNER', 'INVITE_MEMBER')).toBe(true)
    expect(canPerform('OWNER', 'EXECUTE_SWAP')).toBe(true)
    expect(canPerform('OWNER', 'EXPORT_DATA')).toBe(true)
    expect(canPerform('OWNER', 'DELETE_ORG')).toBe(true)
  })

  it('FINANCE_MGR can transact but not manage team', () => {
    expect(canPerform('FINANCE_MGR', 'EXECUTE_SWAP')).toBe(true)
    expect(canPerform('FINANCE_MGR', 'OPEN_POSITION')).toBe(true)
    expect(canPerform('FINANCE_MGR', 'EXPORT_DATA')).toBe(true)
    expect(canPerform('FINANCE_MGR', 'INVITE_MEMBER')).toBe(false)
    expect(canPerform('FINANCE_MGR', 'CHANGE_BILLING')).toBe(false)
  })

  it('ACCOUNTANT is read + export only', () => {
    expect(canPerform('ACCOUNTANT', 'EXPORT_DATA')).toBe(true)
    expect(canPerform('ACCOUNTANT', 'EXECUTE_SWAP')).toBe(false)
    expect(canPerform('ACCOUNTANT', 'INVITE_MEMBER')).toBe(false)
  })

  it('AUDITOR cannot execute or export', () => {
    expect(canPerform('AUDITOR', 'EXECUTE_SWAP')).toBe(false)
    expect(canPerform('AUDITOR', 'EXPORT_DATA')).toBe(false)
    expect(canPerform('AUDITOR', 'OPEN_POSITION')).toBe(false)
  })

  it('VIEWER has no write powers', () => {
    expect(canPerform('VIEWER', 'EXECUTE_SWAP')).toBe(false)
    expect(canPerform('VIEWER', 'OPEN_POSITION')).toBe(false)
    expect(canPerform('VIEWER', 'EXPORT_DATA')).toBe(false)
  })
})
