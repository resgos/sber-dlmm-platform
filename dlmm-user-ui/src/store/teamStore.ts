// Sprint 11 G-21 — Team / sub-account / role-based permissions store.
//
// Dmitry-driven (medium-business demo): "У меня 3 финансовых менеджера.
// У каждого свой workflow... Я не дам всем троим один account / пароль.
// И audit log без user attribution мне регулятор не примет." — это
// был blocker для middle+enterprise tier.
//
// History: wave-1 was a localStorage stub. Wave-2 (this file) swaps
// the stub for real backend endpoints (POST/GET /api/v1/orgs +
// /{id}/members) and gateway permission headers (X-Org-Id, X-Org-Role)
// from JWT claims. Public surface stays SYNC for backward compat with
// TeamPage.tsx — sync methods kick off async axios calls and reconcile
// via notify() when they complete; failures revert local state.
//
// Role taxonomy (mirrored 1:1 in dlmm-user-service OrgMember.Role
// enum so the strings match across the wire):
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

import apiClient from '@/api/client'

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
  /**
   * Sprint 11 G-21 wave-2 — server org UUID once we know it. Empty
   * string before /orgs/me has resolved or when the user has no org.
   * Held internally so mutation calls know which {id} to PUT/POST
   * against; not surfaced through the existing UI which uses orgName
   * as the "do I have an org?" sentinel.
   */
  orgId: string
}

const DEFAULT_STATE: TeamState = {
  orgName: '',
  members: [],
  ownerId: '',
  createdAt: '',
  orgId: '',
}

// UI-CRITIQUE 2026-05-22 fix — cached snapshot, same reason as in
// positionAlertsStore: without cache, every useSyncExternalStore
// getSnapshot returned a new object → React saw "data changed" every
// render → infinite loop → error #185.
//
// G-21 wave-2 additional invariant: cache also serves as the offline
// fallback while /orgs/me is in flight on first mount. Subsequent
// loadOrg() invalidates it and notifies subscribers with the real
// server state.
let cache: TeamState | null = null

function safeRead(): TeamState {
  if (cache !== null) return cache
  cache = DEFAULT_STATE
  return cache
}

function safeWrite(state: TeamState): void {
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

// ── Wire types — mirror OrgController record shapes ────────────────────────

interface OrgResponse {
  id: string
  name: string
  ownerId: string
  createdAt: string
}

interface MemberResponse {
  id: string
  orgId: string
  userId: string | null
  email: string
  name: string
  role: TeamRole
  status: 'ACTIVE' | 'PENDING'
  joinedAt: string
}

/** Extract HTTP status from an axios-thrown error without taking on
 *  a strong axios typing dep here — store stays instance-agnostic. */
function statusOf(err: unknown): number | undefined {
  return (err as { response?: { status?: number } })?.response?.status
}

function warn(...args: unknown[]): void {
  if (typeof console !== 'undefined') console.warn(...args)
}

/** Map server-side member shape into the in-store shape (almost 1:1;
 *  `userId` is dropped since the existing UI keys members by their
 *  membership row id, not their user id). */
function toTeamMember(m: MemberResponse): TeamMember {
  return {
    id: m.id,
    email: m.email,
    name: m.name,
    role: m.role,
    status: m.status,
    joinedAt: m.joinedAt,
  }
}

/** Combine an org + members response into a TeamState. */
function combine(org: OrgResponse, members: MemberResponse[]): TeamState {
  // OWNER row's id is what the UI uses to compare against `state.ownerId`
  // when rendering the "OWNER" badge — pick the first ACTIVE OWNER
  // membership, fall back to any OWNER. If neither exists (shouldn't
  // happen post-create), default to empty so the badge simply doesn't
  // render rather than crashing.
  const owner = members.find((m) => m.role === 'OWNER' && m.status === 'ACTIVE')
              ?? members.find((m) => m.role === 'OWNER')
  return {
    orgName: org.name,
    members: members.map(toTeamMember),
    ownerId: owner?.id ?? '',
    createdAt: org.createdAt,
    orgId: org.id,
  }
}

/** Fetch members for a known orgId and reconcile cache. Used after
 *  every mutation so subscribers see the canonical server view. */
async function reloadMembers(orgId: string): Promise<void> {
  try {
    const { data } = await apiClient.get<MemberResponse[]>(`/orgs/${orgId}/members`)
    const cur = safeRead()
    safeWrite({ ...cur, members: data.map(toTeamMember) })
    notify()
  } catch (err) {
    warn('[teamStore] reloadMembers failed:', statusOf(err) ?? err)
  }
}

export const teamStore = {
  /** Synchronous read used by useSyncExternalStore. First-mount
   *  returns DEFAULT_STATE until loadOrg() resolves. */
  get(): TeamState {
    return safeRead()
  },

  /**
   * Pull the authoritative server state for the caller's org.
   * Call once on TeamPage mount. Promise resolves with the freshly-
   * read state — components rarely need to await this directly
   * because subscribe/get will deliver the value on the next render.
   *
   * 404 on /orgs/me is the normal "user has no org" path — we reset
   * to DEFAULT_STATE so the onboarding card renders.
   */
  async loadOrg(): Promise<TeamState> {
    try {
      const { data: org } = await apiClient.get<OrgResponse>('/orgs/me')
      let members: MemberResponse[] = []
      try {
        const r = await apiClient.get<MemberResponse[]>(`/orgs/${org.id}/members`)
        members = r.data
      } catch (err) {
        // /members fails (e.g. permission edge case) — keep org info,
        // empty member list. Better than wiping the org from the UI.
        warn('[teamStore] members fetch failed:', statusOf(err) ?? err)
      }
      const next = combine(org, members)
      safeWrite(next)
      notify()
      return next
    } catch (err) {
      const status = statusOf(err)
      if (status === 404) {
        // No org — explicit reset to default so subscribers re-render
        // to the empty-state card.
        safeWrite(DEFAULT_STATE)
        notify()
        return DEFAULT_STATE
      }
      warn('[teamStore] loadOrg failed:', status ?? err)
      // Keep whatever cache we had so the UI doesn't flash to "no org"
      // on a transient error.
      return safeRead()
    }
  },

  /**
   * Initialise an organisation. Sync-returning for backward compat —
   * fires POST /orgs in background, optimistic state shown immediately.
   * On backend rejection, the local state is reverted to DEFAULT_STATE
   * and a warning logged.
   */
  create(orgName: string, ownerEmail: string, ownerName: string): TeamState {
    // Synthesise a placeholder OWNER row + org so the UI advances out
    // of the empty-state card immediately. Real ids land via reload
    // after POST /orgs returns.
    const placeholderOwnerId = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : String(Math.random()).slice(2)
    const optimistic: TeamState = {
      orgName: orgName.trim(),
      members: [{
        id: placeholderOwnerId,
        email: ownerEmail,
        name: ownerName,
        role: 'OWNER',
        joinedAt: new Date().toISOString(),
        status: 'ACTIVE',
      }],
      ownerId: placeholderOwnerId,
      createdAt: new Date().toISOString(),
      orgId: '',
    }
    safeWrite(optimistic)
    notify()

    apiClient.post<OrgResponse>('/orgs', {
      name: orgName.trim(),
      ownerEmail,
      ownerName,
    }).then(({ data: org }) => {
      // Pull the canonical member list — POST /orgs returns only the
      // org, not members.
      apiClient.get<MemberResponse[]>(`/orgs/${org.id}/members`).then(({ data: members }) => {
        const reconciled = combine(org, members)
        safeWrite(reconciled)
        notify()
      }).catch((err) => {
        // We at least have the org back — partial state is fine,
        // subsequent loadOrg() will fill the gap.
        warn('[teamStore] post-create members fetch failed:', statusOf(err) ?? err)
        safeWrite({ ...safeRead(), orgId: org.id, orgName: org.name, createdAt: org.createdAt })
        notify()
      })
    }).catch((err) => {
      // Backend rejected — revert optimistic state so the UI returns
      // to the empty-state card rather than showing a phantom org.
      warn('[teamStore] create failed:', statusOf(err) ?? err)
      safeWrite(DEFAULT_STATE)
      notify()
    })

    return optimistic
  },

  /**
   * Invite a new member. Sync-returning the optimistic PENDING row
   * so existing UI code can immediately surface "invitation sent".
   * Returns null if no org exists yet OR the email is already known
   * locally (we don't fire a redundant POST in that case). On backend
   * rejection (409 conflict / 403 not OWNER) the optimistic row is
   * removed and a reload pulled.
   */
  invite(email: string, name: string, role: TeamRole): TeamMember | null {
    const state = safeRead()
    if (!state.orgName) return null
    if (state.members.some((m) => m.email.toLowerCase() === email.trim().toLowerCase())) return null
    const orgId = state.orgId
    if (!orgId) {
      // We don't have an orgId yet — usually means create() is still
      // in flight. Refuse rather than dropping the invite silently;
      // the user can retry in a second.
      warn('[teamStore] invite called before orgId known; refusing')
      return null
    }
    const placeholderId = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : String(Math.random()).slice(2)
    const member: TeamMember = {
      id: placeholderId,
      email: email.trim().toLowerCase(),
      name,
      role,
      joinedAt: new Date().toISOString(),
      status: 'PENDING',
    }
    safeWrite({ ...state, members: [...state.members, member] })
    notify()

    apiClient.post<MemberResponse>(`/orgs/${orgId}/members`, {
      email: member.email,
      name,
      role,
    }).then(({ data }) => {
      // Swap the placeholder row for the canonical server row in-place;
      // saves a roundtrip vs. a full /members refetch and keeps array
      // order stable for the UI.
      const cur = safeRead()
      safeWrite({
        ...cur,
        members: cur.members.map((m) => m.id === placeholderId ? toTeamMember(data) : m),
      })
      notify()
    }).catch((err) => {
      warn('[teamStore] invite failed:', statusOf(err) ?? err)
      // Drop the optimistic row, then resync from server (authoritative
      // truth — the failure might be a 409 because the email already
      // exists server-side from a previous session).
      const cur = safeRead()
      safeWrite({ ...cur, members: cur.members.filter((m) => m.id !== placeholderId) })
      notify()
      reloadMembers(orgId)
    })

    return member
  },

  /** Owner cannot remove themselves OR another owner (must be transferred first). */
  remove(memberId: string): boolean {
    const state = safeRead()
    const member = state.members.find((m) => m.id === memberId)
    if (!member) return false
    if (member.role === 'OWNER') return false // protect owner
    const orgId = state.orgId
    if (!orgId) {
      warn('[teamStore] remove called before orgId known; refusing')
      return false
    }
    // Optimistic local removal.
    safeWrite({ ...state, members: state.members.filter((m) => m.id !== memberId) })
    notify()

    apiClient.delete(`/orgs/${orgId}/members/${memberId}`).then(() => {
      // No-op — already removed locally.
    }).catch((err) => {
      warn('[teamStore] remove failed:', statusOf(err) ?? err)
      // Resync — backend is authoritative.
      reloadMembers(orgId)
    })

    return true
  },

  changeRole(memberId: string, role: TeamRole): boolean {
    const state = safeRead()
    const idx = state.members.findIndex((m) => m.id === memberId)
    if (idx === -1) return false
    if (state.members[idx].role === 'OWNER' && role !== 'OWNER') return false // owner role transfer is separate flow
    const orgId = state.orgId
    if (!orgId) {
      warn('[teamStore] changeRole called before orgId known; refusing')
      return false
    }
    const previous = state.members[idx].role
    const next = [...state.members]
    next[idx] = { ...next[idx], role }
    safeWrite({ ...state, members: next })
    notify()

    apiClient.put(`/orgs/${orgId}/members/${memberId}/role`, { role }).then(() => {
      // No-op.
    }).catch((err) => {
      warn('[teamStore] changeRole failed:', statusOf(err) ?? err)
      // Revert + resync.
      const cur = safeRead()
      const i = cur.members.findIndex((m) => m.id === memberId)
      if (i !== -1) {
        const reverted = [...cur.members]
        reverted[i] = { ...reverted[i], role: previous }
        safeWrite({ ...cur, members: reverted })
        notify()
      }
      reloadMembers(orgId)
    })

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
    const orgId = state.orgId
    if (!orgId) {
      warn('[teamStore] acceptInvite called before orgId known; refusing')
      return false
    }
    const next = [...state.members]
    next[idx] = { ...next[idx], status: 'ACTIVE', joinedAt: new Date().toISOString() }
    safeWrite({ ...state, members: next })
    notify()

    apiClient.post(`/orgs/${orgId}/members/${memberId}/accept`).then(() => {
      // No-op.
    }).catch((err) => {
      // 403 is the most likely failure — caller is not the invitee.
      // This endpoint is genuinely only useful when the invited user
      // is logged in themselves; the "Имитировать принятие" button is
      // a demo affordance that the production flow doesn't have.
      warn('[teamStore] acceptInvite failed:', statusOf(err) ?? err)
      reloadMembers(orgId)
    })

    return true
  },

  subscribe(listener: () => void): () => void {
    listeners.add(listener)
    return () => listeners.delete(listener)
  },

  __resetForTests(): void {
    // Same reason as autoClaimStore — drop the module-level snapshot
    // cache so tests start fresh.
    cache = null
    notify()
  },
}
