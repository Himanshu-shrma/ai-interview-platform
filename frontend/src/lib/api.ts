import axios from 'axios'
import type {
  StartSessionRequest,
  StartSessionResponse,
  SessionSummaryDto,
  SessionDetailDto,
  ReportDto,
  ReportSummaryDto,
  UserStatsDto,
  PagedResponse,
  User,
  OnboardingRequest,
  OnboardingRecommendation,
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
  const { data } = await api.get<User>('/api/v1/users/me')
  return data
}

export async function postOnboarding(req: OnboardingRequest): Promise<OnboardingRecommendation> {
  const { data } = await api.post<OnboardingRecommendation>('/api/v1/users/me/onboarding', req)
  return data
}

export async function getStats(): Promise<UserStatsDto> {
  const { data } = await api.get<UserStatsDto>('/api/v1/users/me/stats')
  return data
}

// ── Languages ──

export interface LanguagesResponse {
  languages: string[]
}

export async function getLanguages(): Promise<string[]> {
  const { data } = await api.get<LanguagesResponse>('/api/v1/code/languages')
  return data.languages
}

// ── Sessions ──

export async function startSession(req: StartSessionRequest): Promise<StartSessionResponse> {
  const { data } = await api.post<StartSessionResponse>('/api/v1/interviews/sessions', req)
  return data
}

export async function endSession(sessionId: string): Promise<void> {
  await api.post(`/api/v1/interviews/sessions/${sessionId}/end`)
}

export async function getSession(sessionId: string): Promise<SessionDetailDto> {
  const { data } = await api.get<SessionDetailDto>(`/api/v1/interviews/sessions/${sessionId}`)
  return data
}

export async function listSessions(
  page = 0,
  size = 20
): Promise<PagedResponse<SessionSummaryDto>> {
  const { data } = await api.get<PagedResponse<SessionSummaryDto>>(
    '/api/v1/interviews/sessions',
    { params: { page, size } }
  )
  return data
}

// ── Reports ──

export async function getReport(sessionId: string): Promise<ReportDto> {
  const { data } = await api.get<ReportDto>(`/api/v1/reports/${sessionId}`)
  return data
}

export async function listReports(
  page = 0,
  size = 10
): Promise<ReportSummaryDto[]> {
  const { data } = await api.get<ReportSummaryDto[]>('/api/v1/reports', {
    params: { page, size },
  })
  return data
}

export default api
