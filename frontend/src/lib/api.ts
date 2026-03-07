import axios from 'axios'
import type {
  StartSessionRequest,
  StartSessionResponse,
  SessionSummaryDto,
  SessionDetailDto,
  EvaluationReport,
  UserStats,
  PaginatedResponse,
  User,
} from '@/types'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080',
  headers: { 'Content-Type': 'application/json' },
})

// Clerk JWT interceptor — attached in providers after Clerk loads
let getTokenFn: (() => Promise<string | null>) | null = null

export function setAuthTokenProvider(fn: () => Promise<string | null>) {
  getTokenFn = fn
}

api.interceptors.request.use(async (config) => {
  if (getTokenFn) {
    const token = await getTokenFn()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
  }
  return config
})

// ── User ──

export async function getMe(): Promise<User> {
  const { data } = await api.get<User>('/api/users/me')
  return data
}

export async function getStats(): Promise<UserStats> {
  const { data } = await api.get<UserStats>('/api/users/me/stats')
  return data
}

// ── Languages ──

export async function getLanguages(): Promise<string[]> {
  const { data } = await api.get<string[]>('/api/languages')
  return data
}

// ── Sessions ──

export async function startSession(req: StartSessionRequest): Promise<StartSessionResponse> {
  const { data } = await api.post<StartSessionResponse>('/api/sessions', req)
  return data
}

export async function endSession(sessionId: string): Promise<void> {
  await api.post(`/api/sessions/${sessionId}/end`)
}

export async function getSession(sessionId: string): Promise<SessionDetailDto> {
  const { data } = await api.get<SessionDetailDto>(`/api/sessions/${sessionId}`)
  return data
}

export async function listSessions(
  page = 0,
  size = 10
): Promise<PaginatedResponse<SessionSummaryDto>> {
  const { data } = await api.get<PaginatedResponse<SessionSummaryDto>>('/api/sessions', {
    params: { page, size },
  })
  return data
}

// ── Reports ──

export async function getReport(sessionId: string): Promise<EvaluationReport> {
  const { data } = await api.get<EvaluationReport>(`/api/reports/${sessionId}`)
  return data
}

export async function listReports(
  page = 0,
  size = 10
): Promise<PaginatedResponse<EvaluationReport>> {
  const { data } = await api.get<PaginatedResponse<EvaluationReport>>('/api/reports', {
    params: { page, size },
  })
  return data
}

export default api
