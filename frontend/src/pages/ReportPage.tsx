import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import {
  Radar,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  ResponsiveContainer,
} from 'recharts'
import { useReport } from '@/hooks/useReport'
import { CategoryBadge } from '@/components/shared/CategoryBadge'
import { DifficultyBadge } from '@/components/shared/DifficultyBadge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { Loader2 } from 'lucide-react'
import type { ReportDto, ScoresDto } from '@/types'

// ── Score count-up animation hook ──

function useCountUp(target: number, duration = 1200) {
  const [value, setValue] = useState(0)
  useEffect(() => {
    let cancelled = false
    const startTime = performance.now()
    function tick(now: number) {
      if (cancelled) return
      const elapsed = now - startTime
      const progress = Math.min(elapsed / duration, 1)
      const eased = 1 - (1 - progress) * (1 - progress)
      setValue(eased * target)
      if (progress < 1) requestAnimationFrame(tick)
    }
    requestAnimationFrame(tick)
    return () => { cancelled = true }
  }, [target, duration])
  return value
}

// ── Score color helpers ──

function scoreColor(score: number): string {
  if (score >= 9) return 'text-blue-600'
  if (score >= 7) return 'text-green-600'
  if (score >= 5) return 'text-yellow-600'
  return 'text-red-600'
}

function scoreBgColor(score: number): string {
  if (score >= 9) return 'bg-blue-600'
  if (score >= 7) return 'bg-green-600'
  if (score >= 5) return 'bg-yellow-600'
  return 'bg-red-600'
}

function scoreLabel(score: number): string {
  if (score >= 9) return 'Excellent'
  if (score >= 7) return 'Good'
  if (score >= 5) return 'Average'
  return 'Needs Work'
}

// ── Radar chart data ──

const dimensionLabels: Record<keyof Omit<ScoresDto, 'overall'>, string> = {
  problemSolving: 'Problem Solving',
  algorithmChoice: 'Algorithm',
  codeQuality: 'Code Quality',
  communication: 'Communication',
  efficiency: 'Efficiency',
  testing: 'Testing',
}

function buildRadarData(scores: ScoresDto) {
  return (Object.keys(dimensionLabels) as (keyof typeof dimensionLabels)[]).map((key) => ({
    dimension: dimensionLabels[key],
    score: scores[key],
    fullMark: 10,
  }))
}

// ── Dimension progress bar ──

function DimensionBar({
  label,
  score,
  feedback,
  delay,
}: {
  label: string
  score: number
  feedback?: string
  delay: number
}) {
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const timer = setTimeout(() => setWidth((score / 10) * 100), delay)
    return () => clearTimeout(timer)
  }, [score, delay])

  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium">{label}</span>
        <span className={cn('font-semibold tabular-nums', scoreColor(score))}>
          {score.toFixed(1)}/10
        </span>
      </div>
      <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
        <div
          className={cn('h-full rounded-full transition-all duration-700 ease-out', scoreBgColor(score))}
          style={{ width: `${width}%` }}
        />
      </div>
      {feedback && (
        <p className="text-xs text-muted-foreground">{feedback}</p>
      )}
    </div>
  )
}

// ── Format duration ──

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return m > 0 ? `${m}m ${s}s` : `${s}s`
}

// ── Loading skeleton ──

function ReportSkeleton() {
  return (
    <div className="mx-auto max-w-4xl space-y-6 p-6">
      <Skeleton className="h-10 w-64" />
      <div className="grid gap-6 md:grid-cols-2">
        <Skeleton className="h-48" />
        <Skeleton className="h-48" />
      </div>
      <Skeleton className="h-64" />
      <div className="grid gap-6 md:grid-cols-3">
        <Skeleton className="h-40" />
        <Skeleton className="h-40" />
        <Skeleton className="h-40" />
      </div>
    </div>
  )
}

// ── Main component ──

export default function ReportPage() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const { data: report, isLoading, isError, error, failureCount } = useReport(sessionId ?? '')

  // Still generating (retrying on 404)
  if (isLoading || (!report && !isError)) {
    if (failureCount > 0) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-4">
          <Loader2 className="h-10 w-10 animate-spin text-primary" />
          <h2 className="text-xl font-semibold">Generating your report...</h2>
          <p className="text-sm text-muted-foreground">
            This usually takes 10-15 seconds
          </p>
        </div>
      )
    }
    return <ReportSkeleton />
  }

  if (isError || !report) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4">
        <h2 className="text-xl font-semibold text-destructive">Failed to load report</h2>
        <p className="text-sm text-muted-foreground">
          {error?.message ?? 'Something went wrong.'}
        </p>
        <div className="flex gap-3">
          <Button variant="outline" asChild>
            <Link to="/dashboard">Back to Dashboard</Link>
          </Button>
          <Button onClick={() => window.location.reload()}>Try Again</Button>
        </div>
      </div>
    )
  }

  return <ReportContent report={report} />
}

function ReportContent({ report }: { report: ReportDto }) {
  const animatedScore = useCountUp(report.overallScore)
  const radarData = buildRadarData(report.scores)

  return (
    <div className="mx-auto max-w-4xl space-y-8 p-6 pb-16">
      {/* Header */}
      <div className="space-y-2">
        <h1 className="text-2xl font-bold">Interview Report</h1>
        <div className="flex items-center gap-2 text-sm text-muted-foreground">
          <span>{report.questionTitle}</span>
          <CategoryBadge category={report.category} />
          <DifficultyBadge difficulty={report.difficulty} />
        </div>
      </div>

      {/* Hero score + Radar chart */}
      <div className="grid gap-6 md:grid-cols-2">
        <Card>
          <CardContent className="flex flex-col items-center justify-center p-8">
            <span className="text-sm font-medium text-muted-foreground uppercase tracking-wide">
              Overall Score
            </span>
            <span className={cn('text-6xl font-bold tabular-nums mt-2', scoreColor(report.overallScore))}>
              {animatedScore.toFixed(1)}
            </span>
            <span className="text-sm text-muted-foreground mt-1">out of 10</span>
            <span className={cn(
              'mt-3 rounded-full px-3 py-1 text-xs font-semibold',
              report.overallScore >= 7
                ? 'bg-green-100 text-green-800'
                : report.overallScore >= 5
                  ? 'bg-yellow-100 text-yellow-800'
                  : 'bg-red-100 text-red-800'
            )}>
              {scoreLabel(report.overallScore)}
            </span>
            {report.hintsUsed > 0 && (
              <span className="mt-2 text-xs text-muted-foreground">
                {report.hintsUsed} hint{report.hintsUsed > 1 ? 's' : ''} used
              </span>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4">
            <ResponsiveContainer width="100%" height={280}>
              <RadarChart data={radarData} cx="50%" cy="50%" outerRadius="75%">
                <PolarGrid />
                <PolarAngleAxis dataKey="dimension" tick={{ fontSize: 11 }} />
                <PolarRadiusAxis angle={30} domain={[0, 10]} tick={{ fontSize: 10 }} />
                <Radar
                  name="Score"
                  dataKey="score"
                  stroke="hsl(221, 83%, 53%)"
                  fill="hsl(221, 83%, 53%)"
                  fillOpacity={0.2}
                  strokeWidth={2}
                />
              </RadarChart>
            </ResponsiveContainer>
          </CardContent>
        </Card>
      </div>

      {/* Per-dimension progress bars */}
      <Card>
        <CardHeader>
          <CardTitle>Score Breakdown</CardTitle>
        </CardHeader>
        <CardContent className="space-y-4">
          {(Object.keys(dimensionLabels) as (keyof typeof dimensionLabels)[]).map((key, i) => (
            <DimensionBar
              key={key}
              label={dimensionLabels[key]}
              score={report.scores[key]}
              feedback={report.dimensionFeedback[key]}
              delay={i * 100}
            />
          ))}
        </CardContent>
      </Card>

      {/* Narrative summary */}
      {report.narrativeSummary && (
        <Card>
          <CardHeader>
            <CardTitle>Summary</CardTitle>
          </CardHeader>
          <CardContent>
            <p className="text-sm leading-relaxed whitespace-pre-line">
              {report.narrativeSummary}
            </p>
          </CardContent>
        </Card>
      )}

      {/* Strengths / Weaknesses / Suggestions */}
      <div className="grid gap-6 md:grid-cols-3">
        {report.strengths.length > 0 && (
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-green-700 text-sm">Strengths</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="space-y-2 text-sm">
                {report.strengths.map((s, i) => (
                  <li key={i} className="flex items-start gap-2">
                    <span className="text-green-600 shrink-0">+</span>
                    <span>{s}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}

        {report.weaknesses.length > 0 && (
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-red-700 text-sm">Areas to Improve</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="space-y-2 text-sm">
                {report.weaknesses.map((w, i) => (
                  <li key={i} className="flex items-start gap-2">
                    <span className="text-red-600 shrink-0">-</span>
                    <span>{w}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}

        {report.suggestions.length > 0 && (
          <Card>
            <CardHeader className="pb-3">
              <CardTitle className="text-blue-700 text-sm">Suggestions</CardTitle>
            </CardHeader>
            <CardContent>
              <ul className="space-y-2 text-sm">
                {report.suggestions.map((s, i) => (
                  <li key={i} className="flex items-start gap-2">
                    <span className="text-blue-600 shrink-0">*</span>
                    <span>{s}</span>
                  </li>
                ))}
              </ul>
            </CardContent>
          </Card>
        )}
      </div>

      {/* Session details */}
      <Card>
        <CardHeader>
          <CardTitle className="text-sm">Session Details</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
            {report.programmingLanguage && (
              <div>
                <span className="text-muted-foreground">Language</span>
                <p className="font-medium capitalize">{report.programmingLanguage}</p>
              </div>
            )}
            {report.durationSeconds != null && (
              <div>
                <span className="text-muted-foreground">Duration</span>
                <p className="font-medium">{formatDuration(report.durationSeconds)}</p>
              </div>
            )}
            <div>
              <span className="text-muted-foreground">Difficulty</span>
              <p className="font-medium capitalize">{report.difficulty.toLowerCase()}</p>
            </div>
            <div>
              <span className="text-muted-foreground">Completed</span>
              <p className="font-medium">
                {new Date(report.completedAt).toLocaleDateString()}
              </p>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* CTAs */}
      <div className="flex items-center justify-center gap-4">
        <Button variant="outline" asChild>
          <Link to="/dashboard">Back to Dashboard</Link>
        </Button>
        <Button asChild>
          <Link to="/interview/setup">Start Another Interview</Link>
        </Button>
      </div>
    </div>
  )
}
