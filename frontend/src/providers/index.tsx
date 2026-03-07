import { ClerkProvider, useAuth } from '@clerk/clerk-react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import { BrowserRouter } from 'react-router-dom'
import { useEffect, type ReactNode } from 'react'
import { setAuthTokenProvider } from '@/lib/api'

const CLERK_PUBLISHABLE_KEY = import.meta.env.VITE_CLERK_PUBLISHABLE_KEY

if (!CLERK_PUBLISHABLE_KEY) {
  throw new Error('Missing VITE_CLERK_PUBLISHABLE_KEY environment variable')
}

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
    },
  },
})

function AuthTokenBridge({ children }: { children: ReactNode }) {
  const { getToken } = useAuth()

  useEffect(() => {
    setAuthTokenProvider(() => getToken())
  }, [getToken])

  return <>{children}</>
}

export function Providers({ children }: { children: ReactNode }) {
  return (
    <BrowserRouter>
      <ClerkProvider publishableKey={CLERK_PUBLISHABLE_KEY}>
        <AuthTokenBridge>
          <QueryClientProvider client={queryClient}>
            {children}
            <ReactQueryDevtools initialIsOpen={false} />
          </QueryClientProvider>
        </AuthTokenBridge>
      </ClerkProvider>
    </BrowserRouter>
  )
}
