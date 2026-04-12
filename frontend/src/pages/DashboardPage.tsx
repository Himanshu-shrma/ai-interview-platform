import { useState } from 'react'
import { Link } from 'react-router-dom'
import { format } from 'date-fns'
import {
  LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend,
} from 'recharts'
import { PageHeader } from '@/components/shared/PageHeader'
import { CategoryBadge } from '@/components/shared/CategoryBadge'
import { DifficultyBadge } from '@/components/shared/DifficultyBadge'
import { ScoreDisplay } from '@/components/shared/ScoreDisplay'
import { useInterviewList, useUserStats, useProgress } from '@/hooks/useInterviews'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Progress } from '@/components/ui/progress'
import { Skeleton } from '@/components/ui/skeleton'
import type { SessionSummaryDto } from '@/types'

const FREE_TIER_LIMIT = 3

// ── Dimension config ────────────────────────────────────────────────────────

const DIMENSIONS = [
  { key: 'problemSolving',  label: 'Problem Solving',  color: '#3b82f6' },
  { key: 'algorithmChoice', label: 'Algorithm',         color: '#8b5cf6' },
  { key: 'codeQuality',     label: 'Code Quality',      color: '#10b981' },
  { key: 'communication',   label: 'Communication',     color: '#f59e0b' },
  { key: 'efficiency',      label: 'Efficiency',        color: '#ef4444' },
  { key: 'testing',         label: 'Testing',           color: '#6366f1' },
  { key: 'initiative',      label: 'Initiative',        color: '#14b8a6' },
  { key: 'learningAgility', label: 'Learning Agility',  color: '#f97316' },
]

function dimLabel(key: string): string {
  return DIMENSIONS.find((d) => d.key === key)?.label ?? key
}

// ── Helpers ──────────────────────────────────────────────────────────────────

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
    case 'ACTIVE': return 'In Progress'
    case 'PENDING': return 'Pending'
    default: return status.charAt(0) + status.slice(1).toLowerCase()
  }
}

function formatDuration(secs?: number): string {
  if (!secs) return '-'
  return `${Math.round(secs / 60)} min`
}

// ── StatsCard ─────────────────────────────────────────────────────────────────

function StatsCard() {
  const { data: stats, isLoading } = useUserStats()

  if (isLoading) {
    return (
      <Card>
        <CardHeader><Skeleton className="h-6 w-32" /></CardHeader>
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
      <CardHeader><CardTitle className="text-lg">Your Stats</CardTitle></CardHeader>
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
                <span className="font-medium">{used} of {FREE_TIER_LIMIT} used</span>
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

// ── InterviewRow ──────────────────────────────────────────────────────────────

function InterviewRow({ session }: Readonly<{ session: SessionSummaryDto }>) {
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

// ── Progress section ──────────────────────────────────────────────────────────

/**
 * Multi-dimension progress chart with checkbox toggles, insight cards, and
 * rolling-average table. Shows an empty state when the user has fewer than 2
 * completed sessions.
 */
function ProgressDashboard() {
  const { data: progress, isLoading } = useProgress()
  const [visibleDims, setVisibleDims] = useState<Set<string>>(
    new Set(DIMENSIONS.map((d) => d.key)),
  )

  if (isLoading) {
    return (
      <Card>
        <CardHeader><Skeleton className="h-6 w-40" /></CardHeader>
        <CardContent><Skeleton className="h-48 w-full" /></CardContent>
      </Card>
    )
  }

  if (!progress || progress.sessionCount < 2) {
    return (
      <Card>
        <CardHeader><CardTitle className="text-lg">Progress</CardTitle></CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground py-6 text-center">
            Complete 2 interviews to see your progress chart.
          </p>
        </CardContent>
      </Card>
    )
  }

  // Build chart data: pivot dimensionTrends into [{session:"#1", problemSolving:7, ...}]
  const maxLen = Math.max(
    ...Object.values(progress.dimensionTrends).map((arr) => arr.length),
    0,
  )
  const chartData = Array.from({ length: maxLen }, (_, i) => {
    const point: Record<string, number | string> = {
      session: `#${i + 1}`,
    }
    for (const { key } of DIMENSIONS) {
      const val = progress.dimensionTrends[key]?.[i]
      if (val !== undefined) point[key] = parseFloat(val.toFixed(1))
    }
    return point
  })

  function toggleDim(key: string) {
    setVisibleDims((prev) => {
      const next = new Set(prev)
      if (next.has(key)) {
        if (next.size > 1) next.delete(key) // keep at least one visible
      } else {
        next.add(key)
      }
      return next
    })
  }

  // Dimensions that have data
  const activeDims = DIMENSIONS.filter((d) => progress.dimensionTrends[d.key])

  return (
    <div className="space-y-4">
      {/* ── Insight cards ── */}
      <div className="grid gap-4 sm:grid-cols-2">
        {progress.mostImproved && (
          <Card className="border-green-200 bg-green-50">
            <CardContent className="py-4">
              <p className="text-xs font-semibold uppercase tracking-wide text-green-700 mb-1">
                Most Improved
              </p>
              <p className="font-medium text-green-900">
                +{progress.mostImproved.delta.toFixed(1)} {dimLabel(progress.mostImproved.dimension)}
              </p>
              <p className="text-xs text-green-700 mt-0.5">
                over last {progress.mostImproved.sessionCount} sessions
              </p>
            </CardContent>
          </Card>
        )}
        {progress.needsAttention && (
          <Card className="border-amber-200 bg-amber-50">
            <CardContent className="py-4">
              <p className="text-xs font-semibold uppercase tracking-wide text-amber-700 mb-1">
                Needs Attention
              </p>
              <p className="font-medium text-amber-900">
                {dimLabel(progress.needsAttention.dimension)}
                {progress.needsAttention.delta <= 0
                  ? ': unchanged or declining'
                  : ': below average'}
              </p>
              <p className="text-xs text-amber-700 mt-0.5">
                avg {(progress.rollingAverage[progress.needsAttention.dimension] ?? 0).toFixed(1)}/10
                over last {progress.needsAttention.sessionCount} sessions
              </p>
            </CardContent>
          </Card>
        )}
        {progress.platformPercentile !== null && (
          <Card className="border-indigo-200 bg-indigo-50 sm:col-span-2">
            <CardContent className="py-4">
              <p className="text-xs font-semibold uppercase tracking-wide text-indigo-700 mb-1">
                Platform Percentile
              </p>
              <p className="font-medium text-indigo-900">
                Top {100 - progress.platformPercentile}% of all candidates
              </p>
            </CardContent>
          </Card>
        )}
      </div>

      {/* ── Multi-line chart ── */}
      <Card>
        <CardHeader>
          <CardTitle className="text-lg">Score Trends by Dimension</CardTitle>
        </CardHeader>
        <CardContent>
          {/* Dimension toggle checkboxes */}
          <div className="flex flex-wrap gap-2 mb-4">
            {activeDims.map(({ key, label, color }) => (
              <button
                key={key}
                type="button"
                onClick={() => toggleDim(key)}
                className={[
                  'flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-medium transition-opacity',
                  visibleDims.has(key) ? 'opacity-100' : 'opacity-40',
                ].join(' ')}
                style={{ borderColor: color, color }}
              >
                <span
                  className="inline-block h-2 w-2 rounded-full"
                  style={{ background: color }}
                />
                {label}
              </button>
            ))}
          </div>

          <ResponsiveContainer width="100%" height={260}>
            <LineChart data={chartData}>
              <XAxis dataKey="session" tick={{ fontSize: 11 }} />
              <YAxis domain={[0, 10]} tick={{ fontSize: 11 }} width={24} />
              <Tooltip
                formatter={(val, name) => [
                  typeof val === 'number' ? val.toFixed(1) : val,
                  dimLabel(String(name)),
                ]}
              />
              <Legend
                formatter={(value: string) => dimLabel(value)}
                wrapperStyle={{ fontSize: 11 }}
              />
              {activeDims
                .filter(({ key }) => visibleDims.has(key))
                .map(({ key, color }) => (
                  <Line
                    key={key}
                    type="monotone"
                    dataKey={key}
                    stroke={color}
                    strokeWidth={2}
                    dot={{ r: 3 }}
                    activeDot={{ r: 5 }}
                    connectNulls
                  />
                ))}
            </LineChart>
          </ResponsiveContainer>
        </CardContent>
      </Card>

      {/* ── Rolling average table ── */}
      {Object.keys(progress.rollingAverage).length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle className="text-base">5-Session Rolling Average</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="grid grid-cols-2 gap-x-6 gap-y-2 sm:grid-cols-4">
              {activeDims
                .filter(({ key }) => progress.rollingAverage[key] !== undefined)
                .map(({ key, label, color }) => (
                  <div key={key} className="flex items-center justify-between py-1">
                    <span className="text-xs text-muted-foreground" style={{ color }}>
                      {label}
                    </span>
                    <span className="text-sm font-semibold tabular-nums">
                      {(progress.rollingAverage[key] ?? 0).toFixed(1)}
                    </span>
                  </div>
                ))}
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function DashboardPage() {
  const [page, setPage] = useState(0)
  const { data, isLoading, isFetching } = useInterviewList(page)

  const sessions = data?.content ?? []
  const hasMore = data ? (page + 1) * data.size < data.total : false

  return (
    <div className="min-h-screen bg-background">
      <PageHeader />

      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8 space-y-8">
        <h1 className="text-2xl font-bold">Dashboard</h1>

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
          <div>
            <StatsCard />
          </div>
        </div>

        {/* Full-width progress section */}
        <div>
          <h2 className="text-lg font-semibold mb-4">Your Progress</h2>
          <ProgressDashboard />
        </div>
      </main>
    </div>
  )
}
