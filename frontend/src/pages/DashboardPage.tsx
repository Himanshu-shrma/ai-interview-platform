import { useState } from 'react'
import { Link } from 'react-router-dom'
import { format } from 'date-fns'
import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer } from 'recharts'
import { PageHeader } from '@/components/shared/PageHeader'
import { CategoryBadge } from '@/components/shared/CategoryBadge'
import { DifficultyBadge } from '@/components/shared/DifficultyBadge'
import { ScoreDisplay } from '@/components/shared/ScoreDisplay'
import { useInterviewList, useUserStats, useReportList } from '@/hooks/useInterviews'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import type { SessionSummaryDto } from '@/types'

const FREE_TIER_LIMIT = 3

function statusBadgeVariant(status: string) {
  switch (status) {
    case 'ACTIVE':
      return 'bg-blue-100 text-blue-800 border-blue-200'
    case 'COMPLETED':
      return 'bg-gray-100 text-gray-800 border-gray-200'
    case 'ABANDONED':
    case 'EXPIRED':
      return 'bg-red-100 text-red-800 border-red-200'
    default:
      return 'bg-gray-100 text-gray-800 border-gray-200'
  }
}

function statusLabel(status: string) {
  switch (status) {
    case 'ACTIVE':
      return 'In Progress'
    case 'PENDING':
      return 'Pending'
    default:
      return status.charAt(0) + status.slice(1).toLowerCase()
  }
}

function formatDuration(secs?: number): string {
  if (!secs) return '-'
  const mins = Math.round(secs / 60)
  return `${mins} min`
}

function StatsCard() {
  const { data: stats, isLoading } = useUserStats()

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <Skeleton className="h-6 w-32" />
        </CardHeader>
        <CardContent className="space-y-4">
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
          <Skeleton className="h-10 w-full" />
        </CardContent>
      </Card>
    )
  }

  const used = stats ? stats.interviewsThisMonth : 0
  const remaining = stats?.freeInterviewsRemaining ?? FREE_TIER_LIMIT
  const isPro = remaining > FREE_TIER_LIMIT
  const usagePercent = isPro ? 0 : (used / FREE_TIER_LIMIT) * 100

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Your Stats</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4">
        <div className="grid grid-cols-3 gap-4 text-center">
          <div>
            <div className="text-2xl font-bold">{stats?.totalInterviews ?? 0}</div>
            <div className="text-xs text-muted-foreground">Total</div>
          </div>
          <div>
            <div className="text-2xl font-bold">
              {stats?.averageScore ? stats.averageScore.toFixed(1) : '-'}
            </div>
            <div className="text-xs text-muted-foreground">Avg Score</div>
          </div>
          <div>
            <div className="text-2xl font-bold">
              {stats?.bestScore ? stats.bestScore.toFixed(1) : '-'}
            </div>
            <div className="text-xs text-muted-foreground">Best</div>
          </div>
        </div>

        <div className="space-y-1">
          {isPro ? (
            <div className="text-sm font-medium text-green-600">Pro Plan — Unlimited</div>
          ) : (
            <>
              <div className="flex justify-between text-sm">
                <span className="text-muted-foreground">Free interviews</span>
                <span className="font-medium">
                  {used} of {FREE_TIER_LIMIT} used
                </span>
              </div>
              <Progress
                value={usagePercent}
                className={usagePercent >= 100 ? '[&>[data-state]]:bg-red-500' : ''}
              />
            </>
          )}
        </div>

        <Button asChild className="w-full" size="lg">
          <Link to="/interview/setup">Start New Interview</Link>
        </Button>
      </CardContent>
    </Card>
  )
}

function InterviewRow({ session }: { session: SessionSummaryDto }) {
  const date = session.createdAt ? format(new Date(session.createdAt), 'MMM d, yyyy') : '-'

  return (
    <div className="flex items-center justify-between gap-4 rounded-lg border p-4">
      <div className="flex flex-col gap-2 min-w-0">
        <div className="flex flex-wrap items-center gap-2">
          <CategoryBadge category={session.category} />
          <DifficultyBadge difficulty={session.difficulty} />
          <Badge variant="outline" className={statusBadgeVariant(session.status)}>
            {statusLabel(session.status)}
          </Badge>
        </div>
        <div className="flex items-center gap-3 text-sm text-muted-foreground">
          <span>{date}</span>
          <span>{formatDuration(session.durationSecs)}</span>
        </div>
      </div>

      <div className="flex items-center gap-4 shrink-0">
        {session.status === 'COMPLETED' && session.overallScore != null && (
          <ScoreDisplay score={session.overallScore} />
        )}

        {session.status === 'COMPLETED' && (
          <Button variant="outline" size="sm" asChild>
            <Link to={`/report/${session.id}`}>View Report</Link>
          </Button>
        )}
        {session.status === 'ACTIVE' && (
          <Button variant="default" size="sm" asChild>
            <Link to={`/interview/${session.id}`}>Resume</Link>
          </Button>
        )}
        {(session.status === 'ABANDONED' || session.status === 'EXPIRED') && (
          <Button variant="ghost" size="sm" asChild>
            <Link to={`/report/${session.id}`}>View Report</Link>
          </Button>
        )}
      </div>
    </div>
  )
}

function InterviewListSkeleton() {
  return (
    <div className="space-y-3">
      {[1, 2, 3].map((i) => (
        <div key={i} className="flex items-center justify-between rounded-lg border p-4">
          <div className="space-y-2">
            <div className="flex gap-2">
              <Skeleton className="h-5 w-16" />
              <Skeleton className="h-5 w-14" />
              <Skeleton className="h-5 w-20" />
            </div>
            <Skeleton className="h-4 w-40" />
          </div>
          <Skeleton className="h-9 w-24" />
        </div>
      ))}
    </div>
  )
}

function EmptyState() {
  return (
    <div className="flex flex-col items-center justify-center rounded-lg border border-dashed py-16 text-center">
      <h3 className="text-lg font-semibold mb-1">No interviews yet</h3>
      <p className="text-sm text-muted-foreground mb-4">
        Start your first practice interview to get personalized feedback
      </p>
      <Button asChild>
        <Link to="/interview/setup">Start Your First Interview</Link>
      </Button>
    </div>
  )
}

function ProgressChart() {
  const { data: reports } = useReportList(0, 20)

  if (!reports || reports.length < 2) return null

  const chartData = [...reports]
    .reverse()
    .map((r, i) => ({
      name: `#${i + 1}`,
      score: r.overallScore,
      date: format(new Date(r.completedAt), 'MMM d'),
    }))

  return (
    <Card>
      <CardHeader>
        <CardTitle className="text-lg">Score Trend</CardTitle>
      </CardHeader>
      <CardContent>
        <ResponsiveContainer width="100%" height={200}>
          <LineChart data={chartData}>
            <XAxis dataKey="date" tick={{ fontSize: 11 }} />
            <YAxis domain={[0, 10]} tick={{ fontSize: 11 }} />
            <Tooltip />
            <Line
              type="monotone"
              dataKey="score"
              stroke="hsl(221, 83%, 53%)"
              strokeWidth={2}
              dot={{ r: 4 }}
            />
          </LineChart>
        </ResponsiveContainer>
      </CardContent>
    </Card>
  )
}

function DifficultyRecommendation() {
  const { data: stats } = useUserStats()
  const { data: reports } = useReportList(0, 5)

  if (!stats || !reports || reports.length < 2) return null

  // Look at the last 3 scores to determine recommendation
  const recentScores = reports.slice(0, 3).map(r => r.overallScore)
  const avgRecent = recentScores.reduce((a, b) => a + b, 0) / recentScores.length
  const lastDifficulty = reports[0]?.difficulty ?? 'MEDIUM'

  let recommendation: string | null = null
  let description: string | null = null

  if (avgRecent >= 7.5 && lastDifficulty !== 'HARD') {
    const next = lastDifficulty === 'EASY' ? 'Medium' : 'Hard'
    recommendation = `Try ${next} difficulty`
    description = `You're averaging ${avgRecent.toFixed(1)}/10 — time to level up!`
  } else if (avgRecent < 4 && lastDifficulty !== 'EASY') {
    const next = lastDifficulty === 'HARD' ? 'Medium' : 'Easy'
    recommendation = `Try ${next} difficulty`
    description = `Build your confidence with easier problems first.`
  }

  if (!recommendation) return null

  return (
    <Card className="border-blue-200 bg-blue-50">
      <CardContent className="py-4">
        <p className="font-medium text-sm text-blue-900">{recommendation}</p>
        <p className="text-xs text-blue-700 mt-1">{description}</p>
      </CardContent>
    </Card>
  )
}

export default function DashboardPage() {
  const [page, setPage] = useState(0)
  const { data, isLoading, isFetching } = useInterviewList(page)

  const sessions = data?.content ?? []
  const hasMore = data ? (page + 1) * data.size < data.total : false

  return (
    <div className="min-h-screen bg-background">
      <PageHeader />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        <h1 className="text-2xl font-bold mb-6">Dashboard</h1>

        <div className="grid gap-6 lg:grid-cols-3">
          {/* Interview history */}
          <div className="lg:col-span-2 space-y-4">
            <h2 className="text-lg font-semibold">Interview History</h2>

            {isLoading ? (
              <InterviewListSkeleton />
            ) : sessions.length === 0 ? (
              <EmptyState />
            ) : (
              <div className="space-y-3">
                {sessions.map((session) => (
                  <InterviewRow key={session.id} session={session} />
                ))}
              </div>
            )}

            {hasMore && (
              <Button
                variant="outline"
                className="w-full"
                onClick={() => setPage((p) => p + 1)}
                disabled={isFetching}
              >
                {isFetching ? 'Loading...' : 'Load More'}
              </Button>
            )}
          </div>

          {/* Stats sidebar */}
          <div className="space-y-6">
            <StatsCard />
            <DifficultyRecommendation />
            <ProgressChart />
          </div>
        </div>
      </main>
    </div>
  )
}
