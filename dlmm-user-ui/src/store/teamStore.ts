// Sprint 11 G-21 — Team / sub-account / role-based permissions store.
//
// Dmitry-driven (medium-business demo): "У меня 3 финансовых менеджера.
// У каждого свой workflow... Я не дам всем троим один account / пароль.
// И audit log без user attribution мне регулятор не примет." — это
// был blocker для middle+enterprise tier.
//
// Frontend MVP scope: org store с members, ролями, invitations. Audit
// log на каждую транзакцию помечается WhoActedAs. Backend swap-in
// Sprint 12: POST/GET /api/v1/orgs/{id}/members + permission middleware
// в gateway которая проверяет role против endpoint'а.
//
// Role taxonomy (subset of классической ACL модели):
//   - OWNER       : всё; единственный кто может удалить org или
//                   изменить role других owner'ов
//   - FINANCE_MGR : открывать/закрывать позиции, делать свопы,
//                   подтверждать настройки автозабора. Полный
//                   transactional access без org-admin прав.
//   - ACCOUNTANT  : read-only + экспорт 1C/CSV. Не делает транзакций.
//   - AUDITOR     : strict read-only; видит транзакции, не видит ключи
//                   API или членов команды.
//   - VIEWER      : минимальный read; видит только свои данные, не
//                   общие org-balance.

const STORAGE_KEY = 'dlmm.user.team'

export type TeamRole = 'OWNER' | 'FINANCE_MGR' | 'ACCOUNTANT' | 'AUDITOR' | 'VIEWER'

export interface TeamMember {
  id: string
  email: string
  name: string
  role: TeamRole
  /** ISO timestamp when invitation accepted (или date of org creation
   *  for the OWNER). */
  joinedAt: string
  /** PENDING = приглашение отправлено но не принято. */
  status: 'ACTIVE' | 'PENDING'
}

export interface TeamState {
  /** Organisation display name (typed once at org creation). */
  orgName: string
  members: TeamMember[]
  /** ID of the OWNER who created the org. */
  ownerId: string
  /** When the org was created — used in audit "since when team
   *  collaboration was active". */
  createdAt: string
}

const DEFAULT_STATE: TeamState = {
  orgName: '',
  members: [],
  ownerId: '',
  createdAt: '',
}

// UI-CRITIQUE 2026-05-22 fix — cached snapshot, same reason as in
// positionAlertsStore: without cache, every useSyncExternalStore
// getSnapshot returned a new JSON.parse'd object → React saw
// "data changed" every render → infinite loop → error #185.
let cache: TeamState | null = null

function safeRead(): TeamState {
  if (cache !== null) return cache
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) {
      cache = DEFAULT_STATE
      return cache
    }
    const parsed = JSON.parse(raw)
    if (typeof parsed?.orgName !== 'string' || !Array.isArray(parsed?.members)) {
      cache = DEFAULT_STATE
      return cache
    }
    cache = parsed as TeamState
    return cache
  } catch {
    cache = DEFAULT_STATE
    return cache
  }
}

function safeWrite(state: TeamState): void {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(state)) } catch { /* ignore */ }
  cache = state
}

const listeners = new Set<() => void>()
function notify(): void {
  listeners.forEach((l) => {
    try { l() } catch { /* ignore */ }
  })
}

export const ROLE_LABELS: Record<TeamRole, string> = {
  OWNER: 'Владелец',
  FINANCE_MGR: 'Финансовый менеджер',
  ACCOUNTANT: 'Бухгалтер',
  AUDITOR: 'Аудитор',
  VIEWER: 'Наблюдатель',
}

export const ROLE_HINTS: Record<TeamRole, string> = {
  OWNER: 'Полный контроль: управление командой, биллинг, удаление организации',
  FINANCE_MGR: 'Открывать позиции, делать свопы, настраивать автозабор',
  ACCOUNTANT: 'Только просмотр + экспорт 1C/CSV',
  AUDITOR: 'Только просмотр всех транзакций (без доступа к ключам)',
  VIEWER: 'Минимальный просмотр своих операций',
}

/** Returns true if `role` may perform `action`. Pure-function. */
export function canPerform(role: TeamRole, action: 'INVITE_MEMBER' | 'REMOVE_MEMBER' | 'CHANGE_ROLE' | 'OPEN_POSITION' | 'EXECUTE_SWAP' | 'EXPORT_DATA' | 'CHANGE_BILLING' | 'DELETE_ORG'): boolean {
  switch (action) {
    case 'INVITE_MEMBER':
    case 'REMOVE_MEMBER':
    case 'CHANGE_ROLE':
    case 'CHANGE_BILLING':
      return role === 'OWNER'
    case 'DELETE_ORG':
      return role === 'OWNER'
    case 'OPEN_POSITION':
    case 'EXECUTE_SWAP':
      return role === 'OWNER' || role === 'FINANCE_MGR'
    case 'EXPORT_DATA':
      return role === 'OWNER' || role === 'FINANCE_MGR' || role === 'ACCOUNTANT'
  }
}

export const teamStore = {
  get(): TeamState {
    return safeRead()
  },

  /**
   * Initialise an organisation. Called once when the user first
   * opens /team and clicks "Создать организацию". The current user
   * becomes OWNER.
   */
  create(orgName: string, ownerEmail: string, ownerName: string): TeamState {
    const ownerId = crypto.randomUUID()
    const state: TeamState = {
      orgName: orgName.trim(),
      members: [
        {
          id: ownerId,
          email: ownerEmail,
          name: ownerName,
          role: 'OWNER',
          joinedAt: new Date().toISOString(),
          status: 'ACTIVE',
        },
      ],
      ownerId,
      createdAt: new Date().toISOString(),
    }
    safeWrite(state)
    notify()
    return state
  },

  invite(email: string, name: string, role: TeamRole): TeamMember | null {
    const state = safeRead()
    if (!state.orgName) return null
    if (state.members.some((m) => m.email === email)) return null
    const member: TeamMember = {
      id: crypto.randomUUID(),
      email,
      name,
      role,
      joinedAt: new Date().toISOString(),
      status: 'PENDING',
    }
    safeWrite({ ...state, members: [...state.members, member] })
    notify()
    return member
  },

  /** Owner cannot remove themselves OR another owner (must be transferred first). */
  remove(memberId: string): boolean {
    const state = safeRead()
    const member = state.members.find((m) => m.id === memberId)
    if (!member) return false
    if (member.role === 'OWNER') return false // protect owner
    safeWrite({ ...state, members: state.members.filter((m) => m.id !== memberId) })
    notify()
    return true
  },

  changeRole(memberId: string, role: TeamRole): boolean {
    const state = safeRead()
    const idx = state.members.findIndex((m) => m.id === memberId)
    if (idx === -1) return false
    if (state.members[idx].role === 'OWNER' && role !== 'OWNER') return false // owner role transfer is separate flow
    const next = [...state.members]
    next[idx] = { ...next[idx], role }
    safeWrite({ ...state, members: next })
    notify()
    return true
  },

  /** Marks PENDING → ACTIVE (called when invite link clicked). For
   *  the frontend MVP the user can simulate via "Mark as accepted"
   *  button in the team table. */
  acceptInvite(memberId: string): boolean {
    const state = safeRead()
    const idx = state.members.findIndex((m) => m.id === memberId)
    if (idx === -1) return false
    if (state.members[idx].status === 'ACTIVE') return false
    const next = [...state.members]
    next[idx] = { ...next[idx], status: 'ACTIVE', joinedAt: new Date().toISOString() }
    safeWrite({ ...state, members: next })
    notify()
    return true
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  __resetForTests(): void {
    try { localStorage.removeItem(STORAGE_KEY) } catch { /* ignore */ }
    notify()
  },
}
