import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import {
  Radar,
  RadarChart,
  PolarGrid,
  PolarAngleAxis,
  PolarRadiusAxis,
  ResponsiveContainer,
} from 'recharts'
import { useMutation } from '@tanstack/react-query'
import { useReport } from '@/hooks/useReport'
import { useProgress } from '@/hooks/useInterviews'
import { CategoryBadge } from '@/components/shared/CategoryBadge'
import { DifficultyBadge } from '@/components/shared/DifficultyBadge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import { ExternalLink, Loader2, Share2, X } from 'lucide-react'
import { submitOutcome } from '@/lib/api'
import type { ReportDto, ScoresDto, NextStep, StudyResource, OutcomeRequest } from '@/types'

// ── Score count-up animation hook ──

function useCountUp(target: number, duration = 1200) {
  const [value, setValue] = useState(0)
  useEffect(() => {
    let start: number
    let frame: number
    const animate = (timestamp: number) => {
      if (!start) start = timestamp
      const progress = Math.min((timestamp - start) / duration, 1)
      const eased = 1 - (1 - progress) * (1 - progress)
      setValue(Math.floor(eased * target * 10) / 10)
      if (progress < 1) frame = requestAnimationFrame(animate)
    }
    frame = requestAnimationFrame(animate)
    return () => cancelAnimationFrame(frame)
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
  initiative: 'Initiative',
  learningAgility: 'Learning Agility',
}

const DIMENSION_WEIGHTS: Record<keyof Omit<ScoresDto, 'overall'>, number> = {
  problemSolving: 20,
  algorithmChoice: 15,
  codeQuality: 15,
  communication: 15,
  efficiency: 10,
  testing: 10,
  initiative: 10,
  learningAgility: 5,
}

const DIMENSION_EXPLANATIONS: Record<keyof Omit<ScoresDto, 'overall'>, string> = {
  problemSolving: 'How well you understood the problem, identified constraints, and broke it into sub-problems.',
  algorithmChoice: 'Selection of appropriate data structures and algorithms, with clear rationale for trade-offs.',
  codeQuality: 'Code readability, correctness, proper naming, and clean structure.',
  communication: 'Clarity of thought process explanation, asking good questions, and articulating decisions.',
  efficiency: 'Awareness of time/space complexity, optimization attempts, and performance considerations.',
  testing: 'Edge case identification, debugging approach, and verification of solution correctness.',
  initiative: 'Going beyond the minimum — proactive edge cases, voluntary optimizations, genuine curiosity.',
  learningAgility: 'How effectively you learned during the interview — self-correction, adapting after hints, asking why.',
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
  explanation,
  weight,
}: Readonly<{
  label: string
  score: number
  feedback?: string
  delay: number
  explanation?: string
  weight?: number
}>) {
  const [width, setWidth] = useState(0)
  useEffect(() => {
    const timer = setTimeout(() => setWidth((score / 10) * 100), delay)
    return () => clearTimeout(timer)
  }, [score, delay])

  return (
    <div className="space-y-1">
      <div className="flex items-center justify-between text-sm">
        <span className="font-medium group relative cursor-help flex items-center gap-1.5">
          {label}
          {weight != null && (
            <span className="text-[10px] text-muted-foreground font-normal">{weight}%</span>
          )}
          {explanation && (
            <span className="invisible group-hover:visible absolute left-0 top-full z-10 mt-1 w-64 rounded-md bg-popover p-2 text-xs text-popover-foreground shadow-md border">
              {explanation}
            </span>
          )}
        </span>
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

// ── Study plan helpers ──

function resourceUrl(r: StudyResource): string {
  if (r.type === 'leetcode' && r.id) {
    const slug = r.title.toLowerCase().replace(/\s+/g, '-')
    return `https://leetcode.com/problems/${slug}/`
  }
  return r.url ?? '#'
}

function resourceLabel(r: StudyResource): string {
  if (r.type === 'leetcode') return `LeetCode${r.id ? ` #${r.id}` : ''}: ${r.title}`
  if (r.type === 'youtube') return r.title
  return r.title
}

function priorityStyles(p: string): { border: string; badge: string } {
  if (p === 'HIGH')   return { border: 'border-l-4 border-l-red-500',    badge: 'bg-red-100 text-red-700' }
  if (p === 'MEDIUM') return { border: 'border-l-4 border-l-yellow-500', badge: 'bg-yellow-100 text-yellow-700' }
  return                       { border: 'border-l-4 border-l-green-500', badge: 'bg-green-100 text-green-700' }
}

function StudyPlanCard({ step, onPractice }: Readonly<{ step: NextStep; onPractice: (topic: string) => void }>) {
  const topic    = step.topic    || step.area
  const gap      = step.gap      || step.specificGap
  const evidence = step.evidence || step.evidenceFromInterview
  const styles   = priorityStyles(step.priority)

  return (
    <div className={cn('rounded-lg border p-4 space-y-2', styles.border)}>
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <span className="font-semibold text-sm">{topic}</span>
        <div className="flex items-center gap-2">
          {step.estimatedHours > 0 && (
            <span className="text-xs text-muted-foreground">{step.estimatedHours}h study</span>
          )}
          <span className={cn('text-xs px-2 py-0.5 rounded-full font-medium', styles.badge)}>
            {step.priority}
          </span>
        </div>
      </div>
      {gap && <p className="text-sm text-muted-foreground">{gap}</p>}
      {evidence && (
        <blockquote className="border-l-2 border-muted-foreground/30 pl-3 text-xs italic text-muted-foreground">
          {evidence}
        </blockquote>
      )}
      {step.actionItem && <p className="text-sm">{step.actionItem}</p>}
      {step.resources && step.resources.length > 0 && (
        <div className="flex flex-wrap gap-2 pt-1">
          {step.resources.map((r) => (
            <a
              key={`${r.type}-${r.id ?? r.url ?? r.title}`}
              href={resourceUrl(r)}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-1 rounded-md border px-2.5 py-1 text-xs font-medium hover:bg-muted transition-colors"
            >
              {resourceLabel(r)}
              <ExternalLink className="h-3 w-3 shrink-0" />
            </a>
          ))}
        </div>
      )}
      {(!step.resources || step.resources.length === 0) && step.resource && (
        <p className="text-xs text-blue-600">{step.resource}</p>
      )}
      <div className="pt-1">
        <Button size="sm" variant="outline" onClick={() => onPractice(topic)}>
          Practice this topic →
        </Button>
      </div>
    </div>
  )
}

// ── Post-session feedback form (appears 10s after load, dismissible) ──

function FeedbackForm({ sessionId, onDismiss }: Readonly<{ sessionId: string; onDismiss: () => void }>) {
  const [level, setLevel] = useState<string | null>(null)
  const [feltRealistic, setFeltRealistic] = useState<OutcomeRequest['feltRealistic'] | null>(null)
  const [nps, setNps] = useState<number | null>(null)
  const [submitted, setSubmitted] = useState(false)

  const mutation = useMutation({
    mutationFn: (req: OutcomeRequest) => submitOutcome(sessionId, req),
    onSuccess: () => setSubmitted(true),
  })

  function handleSubmit() {
    mutation.mutate({
      level: level ?? undefined,
      feltRealistic: feltRealistic ?? undefined,
      nps: nps ?? undefined,
    })
  }

  const canSubmit = level !== null || feltRealistic !== null || nps !== null

  return (
    <div className="fixed bottom-6 right-6 z-50 w-80 rounded-xl border bg-card shadow-xl">
      <div className="flex items-center justify-between p-4 pb-2">
        <span className="font-semibold text-sm">Quick Feedback</span>
        <button
          onClick={onDismiss}
          className="text-muted-foreground hover:text-foreground transition-colors"
          aria-label="Dismiss feedback form"
        >
          <X className="h-4 w-4" />
        </button>
      </div>

      {submitted ? (
        <div className="p-4 pt-2 text-center">
          <p className="text-sm font-medium text-green-600">Thanks for your feedback! 🙏</p>
          <p className="text-xs text-muted-foreground mt-1">This helps us improve the platform.</p>
        </div>
      ) : (
        <div className="p-4 pt-2 space-y-4">
          {/* Q1: Question difficulty */}
          <div>
            <p className="text-xs font-medium mb-2">Was the question right for your level?</p>
            <div className="flex gap-1.5">
              {(['too_easy', 'about_right', 'too_hard'] as const).map((v) => (
                <button
                  key={v}
                  onClick={() => setLevel(v)}
                  className={cn(
                    'flex-1 rounded-md border px-1.5 py-1 text-xs font-medium transition-colors',
                    level === v
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'hover:bg-muted'
                  )}
                >
                  {v === 'too_easy' ? 'Too Easy' : v === 'about_right' ? 'About Right' : 'Too Hard'}
                </button>
              ))}
            </div>
          </div>

          {/* Q2: AI fairness */}
          <div>
            <p className="text-xs font-medium mb-2">Was the AI a fair interviewer?</p>
            <div className="flex gap-1.5">
              {(['yes', 'somewhat', 'no'] as const).map((v) => (
                <button
                  key={v}
                  onClick={() => setFeltRealistic(v)}
                  className={cn(
                    'flex-1 rounded-md border px-2 py-1 text-xs font-medium transition-colors capitalize',
                    feltRealistic === v
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'hover:bg-muted'
                  )}
                >
                  {v === 'yes' ? 'Yes' : v === 'somewhat' ? 'Somewhat' : 'No'}
                </button>
              ))}
            </div>
          </div>

          {/* Q3: NPS */}
          <div>
            <p className="text-xs font-medium mb-2">Would you recommend this? (1–10)</p>
            <div className="flex gap-1 flex-wrap">
              {Array.from({ length: 10 }, (_, i) => i + 1).map((n) => (
                <button
                  key={n}
                  onClick={() => setNps(n)}
                  className={cn(
                    'w-7 h-7 rounded-md border text-xs font-medium transition-colors',
                    nps === n
                      ? 'bg-primary text-primary-foreground border-primary'
                      : 'hover:bg-muted'
                  )}
                >
                  {n}
                </button>
              ))}
            </div>
          </div>

          <div className="flex gap-2">
            <Button
              size="sm"
              onClick={handleSubmit}
              disabled={!canSubmit || mutation.isPending}
              className="flex-1"
            >
              {mutation.isPending ? 'Saving...' : 'Submit'}
            </Button>
            <Button size="sm" variant="ghost" onClick={onDismiss}>
              Skip
            </Button>
          </div>
        </div>
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

  if (isLoading || (!report && !isError)) {
    if (failureCount > 0) {
      return (
        <div className="flex min-h-screen flex-col items-center justify-center gap-4">
          <Loader2 className="h-10 w-10 animate-spin text-primary" />
          <h2 className="text-xl font-semibold">Generating your report...</h2>
          <p className="text-sm text-muted-foreground">This usually takes 10-15 seconds</p>
        </div>
      )
    }
    return <ReportSkeleton />
  }

  if (isError || !report) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-4">
        <h2 className="text-xl font-semibold text-destructive">Failed to load report</h2>
        <p className="text-sm text-muted-foreground">{error?.message ?? 'Something went wrong.'}</p>
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

function ReportContent({ report }: Readonly<{ report: ReportDto }>) {
  const navigate = useNavigate()
  const animatedScore = useCountUp(report.overallScore)
  const radarData = buildRadarData(report.scores)

  // Progress data for score context, delta, and next CTA
  const { data: progress } = useProgress()

  // Feedback form — appears 10s after report loads
  const [feedbackVisible, setFeedbackVisible] = useState(false)
  const [feedbackDismissed, setFeedbackDismissed] = useState(false)
  useEffect(() => {
    const timer = setTimeout(() => setFeedbackVisible(true), 10_000)
    return () => clearTimeout(timer)
  }, [])

  // Delta from last session
  const sessions = progress?.sessions ?? []
  const currentIdx = sessions.findIndex((s) => s.sessionId === report.sessionId)
  const prevSession = currentIdx > 0 ? sessions[currentIdx - 1] : null
  const overallDelta = prevSession != null
    ? +(report.overallScore - prevSession.overallScore).toFixed(1)
    : null
  const mostImproved = progress?.mostImproved

  // Next session recommendation
  const needsAttentionDim = progress?.needsAttention?.dimension
  const needsAttentionLabel = needsAttentionDim
    ? (dimensionLabels[needsAttentionDim as keyof typeof dimensionLabels] ?? needsAttentionDim)
    : null
  const nextDifficulty = report.overallScore >= 7 ? 'HARD' : 'MEDIUM'

  // LinkedIn share
  const shareText = `I scored ${report.overallScore.toFixed(1)}/10 on a ${report.difficulty.toLowerCase()} ${report.category.toLowerCase().replace('_', ' ')} interview on AI Interview Platform!`
  const linkedInShareUrl =
    `https://www.linkedin.com/shareArticle?mini=true` +
    `&url=${encodeURIComponent('https://aiinterviewplatform.com')}` +
    `&title=${encodeURIComponent('AI Interview Platform')}` +
    `&summary=${encodeURIComponent(shareText)}`

  function handlePracticeTopic(topic: string) {
    navigate('/interview/setup', { state: { topic } })
  }

  function handleNextSession() {
    navigate('/interview/setup', {
      state: {
        topic: needsAttentionLabel ? `Focus on ${needsAttentionLabel}` : undefined,
        difficulty: nextDifficulty,
        category: report.category,
      },
    })
  }

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
            {/* Score context: percentile */}
            {progress?.platformPercentile != null && (
              <p className="mt-2 text-xs text-muted-foreground text-center">
                Top {100 - progress.platformPercentile}% of {report.difficulty.toLowerCase()} difficulty interviews
              </p>
            )}
            {report.hintsUsed > 0 && (
              <span className="mt-2 text-xs text-muted-foreground">
                {report.hintsUsed} hint{report.hintsUsed > 1 ? 's' : ''} used
              </span>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-4">
            <ResponsiveContainer width="100%" height={300}>
              <RadarChart data={radarData} cx="50%" cy="50%" outerRadius="70%">
                <PolarGrid />
                <PolarAngleAxis dataKey="dimension" tick={{ fontSize: 10 }} />
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

      {/* Delta from last session */}
      {overallDelta !== null && (
        <div className={cn(
          'rounded-lg border px-4 py-3 flex items-center gap-3 text-sm',
          overallDelta >= 0 ? 'border-green-200 bg-green-50' : 'border-red-200 bg-red-50'
        )}>
          <span className={cn(
            'font-bold text-lg tabular-nums shrink-0',
            overallDelta >= 0 ? 'text-green-700' : 'text-red-700'
          )}>
            {overallDelta >= 0 ? '+' : ''}{overallDelta}
          </span>
          <span className={cn('text-sm', overallDelta >= 0 ? 'text-green-800' : 'text-red-800')}>
            from your last interview
            {mostImproved && overallDelta > 0 && (
              <span className="text-muted-foreground">
                {'. '}Biggest improvement:{' '}
                {dimensionLabels[mostImproved.dimension as keyof typeof dimensionLabels] ?? mostImproved.dimension}
                {' ('}+{mostImproved.delta.toFixed(1)}{')'}
              </span>
            )}
          </span>
        </div>
      )}

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
              explanation={DIMENSION_EXPLANATIONS[key]}
              weight={DIMENSION_WEIGHTS[key]}
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
            <p className="text-sm leading-relaxed whitespace-pre-line">{report.narrativeSummary}</p>
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

      {/* Study Plan — next steps */}
      {report.nextSteps && report.nextSteps.length > 0 && (
        <Card>
          <CardHeader>
            <CardTitle>Your Study Plan</CardTitle>
          </CardHeader>
          <CardContent className="space-y-3">
            {report.nextSteps.map((step: NextStep) => (
              <StudyPlanCard
                key={step.topic || step.area}
                step={step}
                onPractice={handlePracticeTopic}
              />
            ))}
          </CardContent>
        </Card>
      )}

      {/* Share card */}
      <Card className="border-dashed">
        <CardContent className="flex items-center justify-between gap-4 p-4 flex-wrap">
          <div className="min-w-0">
            <p className="text-sm font-medium">Share your result</p>
            <p className="text-xs text-muted-foreground mt-0.5 truncate">{shareText}</p>
          </div>
          <Button
            size="sm"
            variant="outline"
            className="shrink-0 flex items-center gap-1.5"
            onClick={() => window.open(linkedInShareUrl, '_blank', 'noopener,noreferrer,width=600,height=600')}
          >
            <Share2 className="h-4 w-4" />
            LinkedIn
          </Button>
        </CardContent>
      </Card>

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
              <p className="font-medium">{new Date(report.completedAt).toLocaleDateString()}</p>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Next session CTA + back to dashboard */}
      <div className="space-y-3">
        {needsAttentionLabel && (
          <Card className="border-primary/30 bg-primary/5">
            <CardContent className="flex items-center justify-between p-4 flex-wrap gap-3">
              <div>
                <p className="text-sm font-medium">Based on this session</p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  Try {report.category} {nextDifficulty} focusing on {needsAttentionLabel}
                </p>
              </div>
              <Button size="sm" onClick={handleNextSession}>
                Practice Now →
              </Button>
            </CardContent>
          </Card>
        )}
        <div className="flex items-center justify-center gap-4">
          <Button variant="outline" asChild>
            <Link to="/dashboard">Back to Dashboard</Link>
          </Button>
          <Button asChild>
            <Link to="/interview/setup">Start Another Interview</Link>
          </Button>
        </div>
      </div>

      {/* Post-session feedback form (fixed bottom-right, appears after 10s) */}
      {feedbackVisible && !feedbackDismissed && (
        <FeedbackForm
          sessionId={report.sessionId}
          onDismiss={() => setFeedbackDismissed(true)}
        />
      )}
    </div>
  )
}
