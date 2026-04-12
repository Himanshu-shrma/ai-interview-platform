import { Routes, Route, Navigate, useLocation } from 'react-router-dom'
import { SignIn, SignUp, useAuth } from '@clerk/clerk-react'
import { useQuery } from '@tanstack/react-query'
import { type ReactNode } from 'react'
import DashboardPage from '@/pages/DashboardPage'
import InterviewSetupPage from '@/pages/InterviewSetupPage'
import InterviewPage from '@/pages/InterviewPage'
import ReportPage from '@/pages/ReportPage'
import OnboardingPage from '@/pages/OnboardingPage'
import { getMe } from '@/lib/api'

function ProtectedRoute({ children }: Readonly<{ children: ReactNode }>) {
  const { isSignedIn, isLoaded } = useAuth()
  const location = useLocation()

  const { data: user, isLoading: userLoading } = useQuery({
    queryKey: ['me'],
    queryFn: getMe,
    enabled: isLoaded && !!isSignedIn,
    staleTime: 60_000,
  })

  if (!isLoaded || (isSignedIn && userLoading)) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="animate-pulse text-muted-foreground">Loading...</div>
      </div>
    )
  }

  if (!isSignedIn) {
    return <Navigate to="/sign-in" replace />
  }

  // Redirect new users to onboarding — but never redirect from /onboarding itself
  if (user && !user.onboardingCompleted && location.pathname !== '/onboarding') {
    return <Navigate to="/onboarding" replace />
  }

  return <>{children}</>
}

function LandingPage() {
  const { isSignedIn } = useAuth()

  if (isSignedIn) {
    return <Navigate to="/dashboard" replace />
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-4">
      <h1 className="text-4xl font-bold">AI Interview Platform</h1>
      <p className="text-muted-foreground">Practice technical interviews with an AI interviewer</p>
    </div>
  )
}

export default function App() {
  return (
    <Routes>
      <Route path="/" element={<LandingPage />} />
      <Route
        path="/sign-in/*"
        element={
          <div className="flex min-h-screen items-center justify-center">
            <SignIn routing="path" path="/sign-in" />
          </div>
        }
      />
      <Route
        path="/sign-up/*"
        element={
          <div className="flex min-h-screen items-center justify-center">
            <SignUp routing="path" path="/sign-up" />
          </div>
        }
      />
      <Route
        path="/onboarding"
        element={
          <ProtectedRoute>
            <OnboardingPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/dashboard"
        element={
          <ProtectedRoute>
            <DashboardPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/interview/setup"
        element={
          <ProtectedRoute>
            <InterviewSetupPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/interview/:sessionId"
        element={
          <ProtectedRoute>
            <InterviewPage />
          </ProtectedRoute>
        }
      />
      <Route
        path="/report/:sessionId"
        element={
          <ProtectedRoute>
            <ReportPage />
          </ProtectedRoute>
        }
      />
    </Routes>
  )
}
