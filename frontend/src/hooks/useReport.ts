import { useQuery } from '@tanstack/react-query'
import { getReport } from '@/lib/api'
import { AxiosError } from 'axios'

export function useReport(sessionId: string) {
  return useQuery({
    queryKey: ['report', sessionId],
    queryFn: () => getReport(sessionId),
    enabled: !!sessionId,
    staleTime: Infinity,
    retry: (failureCount, error) => {
      // Retry on 404 (report still generating) up to 10 times
      if (error instanceof AxiosError && error.response?.status === 404) {
        return failureCount < 10
      }
      // Don't retry other errors
      return false
    },
    retryDelay: 3000,
  })
}
