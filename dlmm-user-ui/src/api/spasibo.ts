import apiClient from './client'
import { toRaw, fromRaw, fromRawN } from './scale'

export interface SpasiboOperation {
  id: string
  opType: 'MINT' | 'CONVERT'
  userId: string
  points: number
  rubAmount: number | null
  reference: string
  status: 'COMPLETED' | 'FAILED'
  errorMessage: string | null
  createdAt: string
}

export interface SpasiboConvertRequest {
  points: number
  reference: string
}

/**
 * Sprint 5 #5.4 — SberSpasibo conversion API client. The "mint webhook"
 * (#5.3) endpoint is for SberSpasibo BU server-to-server traffic, not
 * end-user — not exposed here.
 */
export const spasibo = {
  convertToRub: async (req: SpasiboConvertRequest): Promise<SpasiboOperation> => {
    // points is a human SSPAS amount (validated against the scaled balance) —
    // scale up to raw; scale the echoed points + credited SRUB back to human.
    const { data } = await apiClient.post<SpasiboOperation>('/spasibo/convert', {
      ...req,
      points: toRaw(req.points),
    })
    return { ...data, points: fromRaw(data.points), rubAmount: fromRawN(data.rubAmount) }
  },
}
