import { describe, it, expect, beforeEach } from 'vitest'
import { teamStore, canPerform } from '../store/teamStore'

describe('teamStore (G-21)', () => {
  beforeEach(() => {
    teamStore.__resetForTests()
  })

  it('starts with no org', () => {
    expect(teamStore.get().orgName).toBe('')
    expect(teamStore.get().members).toEqual([])
  })

  it('create initialises org + OWNER member', () => {
    const s = teamStore.create('Транспортхолдинг', 'av@th.ru', 'Александр В.')
    expect(s.orgName).toBe('Транспортхолдинг')
    expect(s.members).toHaveLength(1)
    expect(s.members[0].role).toBe('OWNER')
    expect(s.members[0].email).toBe('av@th.ru')
    expect(s.ownerId).toBe(s.members[0].id)
  })

  it('invite adds a PENDING member', () => {
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    const m = teamStore.invite('b@b.ru', 'Beta', 'FINANCE_MGR')
    expect(m).not.toBeNull()
    expect(m!.status).toBe('PENDING')
    expect(teamStore.get().members).toHaveLength(2)
  })

  it('invite refuses duplicate emails', () => {
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    teamStore.invite('b@b.ru', 'Beta', 'FINANCE_MGR')
    expect(teamStore.invite('b@b.ru', 'Beta2', 'ACCOUNTANT')).toBeNull()
    expect(teamStore.get().members).toHaveLength(2)
  })

  it('invite fails without an org', () => {
    expect(teamStore.invite('a@a.ru', 'A', 'VIEWER')).toBeNull()
  })

  it('acceptInvite flips PENDING → ACTIVE', () => {
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    const m = teamStore.invite('b@b.ru', 'Beta', 'FINANCE_MGR')!
    expect(m.status).toBe('PENDING')
    teamStore.acceptInvite(m.id)
    expect(teamStore.get().members.find((x) => x.id === m.id)!.status).toBe('ACTIVE')
  })

  it('remove deletes non-owner; protects owner', () => {
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    const m = teamStore.invite('b@b.ru', 'Beta', 'FINANCE_MGR')!
    expect(teamStore.remove(m.id)).toBe(true)
    expect(teamStore.get().members).toHaveLength(1)
    // Try removing OWNER → false.
    const ownerId = teamStore.get().ownerId
    expect(teamStore.remove(ownerId)).toBe(false)
    expect(teamStore.get().members).toHaveLength(1)
  })

  it('changeRole works for non-owner', () => {
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    const m = teamStore.invite('b@b.ru', 'Beta', 'FINANCE_MGR')!
    teamStore.changeRole(m.id, 'ACCOUNTANT')
    expect(teamStore.get().members.find((x) => x.id === m.id)!.role).toBe('ACCOUNTANT')
  })

  it('changeRole cannot demote owner', () => {
    teamStore.create('Org', 'a@a.ru', 'Alpha')
    const ownerId = teamStore.get().ownerId
    expect(teamStore.changeRole(ownerId, 'FINANCE_MGR')).toBe(false)
    expect(teamStore.get().members[0].role).toBe('OWNER')
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
