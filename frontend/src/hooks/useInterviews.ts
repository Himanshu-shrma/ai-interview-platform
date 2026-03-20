import { useQuery, useMutation } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { listSessions, getSession, startSession, getStats, getLanguages, listReports } from '@/lib/api'
import type { StartSessionRequest } from '@/types'

export function useInterviewList(page = 0) {
  return useQuery({
    queryKey: ['interviews', page],
    queryFn: () => listSessions(page),
    staleTime: 30_000,
  })
}

export function useInterviewDetail(sessionId: string) {
  return useQuery({
    queryKey: ['interview', sessionId],
    queryFn: () => getSession(sessionId),
    enabled: !!sessionId,
  })
}

export function useStartInterview() {
  const navigate = useNavigate()

  return useMutation({
    mutationFn: (req: StartSessionRequest) => startSession(req),
    onSuccess: (data) => {
      navigate(`/interview/${data.sessionId}`)
    },
  })
}

export function useUserStats() {
  return useQuery({
    queryKey: ['userStats'],
    queryFn: () => getStats(),
    staleTime: 60_000,
  })
}

export function useLanguages() {
  return useQuery({
    queryKey: ['languages'],
    queryFn: () => getLanguages(),
    staleTime: 5 * 60_000,
  })
}

export function useReportList(page = 0, size = 20) {
  return useQuery({
    queryKey: ['reports', page, size],
    queryFn: () => listReports(page, size),
    staleTime: 60_000,
  })
}
