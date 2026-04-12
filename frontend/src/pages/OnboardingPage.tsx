import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation } from '@tanstack/react-query'
import { postOnboarding } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import type { OnboardingRequest, OnboardingRecommendation } from '@/types'

// ── Option sets ───────────────────────────────────────────────────────────────

const ROLE_OPTIONS = [
  { value: 'swe',        label: 'Software Engineer',       sub: 'Mid-level SWE roles' },
  { value: 'senior_swe', label: 'Senior Engineer',         sub: 'Senior / L5+ roles' },
  { value: 'staff',      label: 'Staff / Principal',       sub: 'Staff / architect roles' },
  { value: 'switching',  label: 'Career Switcher',         sub: 'Breaking into tech' },
]

const URGENCY_OPTIONS = [
  { value: 'active',    label: 'Actively Interviewing', sub: 'Interviews scheduled or ongoing' },
  { value: 'preparing', label: 'Preparing',              sub: 'Starting to get ready' },
  { value: 'exploring', label: 'Just Exploring',         sub: 'No immediate timeline' },
]

const CHALLENGE_OPTIONS = [
  { value: 'algorithms',    label: 'Algorithms & Data Structures', sub: 'LeetCode-style problems' },
  { value: 'system_design', label: 'System Design',                sub: 'Architecture discussions' },
  { value: 'behavioral',    label: 'Behavioural Questions',         sub: 'STAR / leadership stories' },
  { value: 'communication', label: 'Communication',                 sub: 'Explaining my thinking clearly' },
]

// ── Sub-components ────────────────────────────────────────────────────────────

function ButtonGroup({
  label,
  options,
  value,
  onChange,
}: {
  label: string
  options: { value: string; label: string; sub: string }[]
  value: string
  onChange: (v: string) => void
}) {
  return (
    <div className="space-y-3">
      <p className="text-sm font-medium text-muted-foreground uppercase tracking-wide">{label}</p>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        {options.map((opt) => (
          <button
            key={opt.value}
            type="button"
            onClick={() => onChange(opt.value)}
            className={[
              'rounded-lg border-2 p-4 text-left transition-all',
              value === opt.value
                ? 'border-primary bg-primary/5 shadow-sm'
                : 'border-border hover:border-primary/50 hover:bg-muted/50',
            ].join(' ')}
          >
            <p className="font-semibold text-sm">{opt.label}</p>
            <p className="text-xs text-muted-foreground mt-0.5">{opt.sub}</p>
          </button>
        ))}
      </div>
    </div>
  )
}

function categoryLabel(cat: string) {
  const map: Record<string, string> = {
    CODING: 'Coding',
    BEHAVIORAL: 'Behavioural',
    SYSTEM_DESIGN: 'System Design',
  }
  return map[cat] ?? cat
}

function difficultyColor(diff: string) {
  if (diff === 'EASY') return 'text-green-600'
  if (diff === 'HARD') return 'text-red-600'
  return 'text-yellow-600'
}

// ── Main page ─────────────────────────────────────────────────────────────────

export default function OnboardingPage() {
  const navigate = useNavigate()

  const [step, setStep] = useState<1 | 2 | 3>(1)
  const [answers, setAnswers] = useState<OnboardingRequest>({
    roleTarget:       '',
    urgency:          '',
    biggestChallenge: '',
  })
  const [recommendation, setRecommendation] = useState<OnboardingRecommendation | null>(null)

  const canProceed =
    answers.roleTarget !== '' &&
    answers.urgency !== '' &&
    answers.biggestChallenge !== ''

  const mutation = useMutation({
    mutationFn: () => postOnboarding(answers),
    onSuccess: (rec) => {
      setRecommendation(rec)
      setStep(2)
    },
  })

  function handleGetRecommendation() {
    mutation.mutate()
  }

  function handleStartInterview() {
    if (!recommendation) return
    const params = new URLSearchParams({
      category:    recommendation.category,
      difficulty:  recommendation.difficulty,
      personality: recommendation.personality,
    })
    navigate(`/interview/setup?${params.toString()}`)
  }

  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-background p-4">
      <div className="w-full max-w-2xl space-y-6">

        {/* Header */}
        <div className="text-center space-y-1">
          <h1 className="text-3xl font-bold tracking-tight">Welcome!</h1>
          <p className="text-muted-foreground">
            {step === 1 && 'Answer 3 quick questions so we can personalise your first session.'}
            {step === 2 && "Here's what we recommend for you."}
            {step === 3 && "You're all set — let's go!"}
          </p>
        </div>

        {/* Step 1 — Questions */}
        {step === 1 && (
          <Card>
            <CardHeader>
              <CardTitle className="text-lg">Tell us about yourself</CardTitle>
            </CardHeader>
            <CardContent className="space-y-6">
              <ButtonGroup
                label="What role are you targeting?"
                options={ROLE_OPTIONS}
                value={answers.roleTarget}
                onChange={(v) => setAnswers((a) => ({ ...a, roleTarget: v }))}
              />
              <ButtonGroup
                label="How urgent is your job search?"
                options={URGENCY_OPTIONS}
                value={answers.urgency}
                onChange={(v) => setAnswers((a) => ({ ...a, urgency: v }))}
              />
              <ButtonGroup
                label="What's your biggest challenge?"
                options={CHALLENGE_OPTIONS}
                value={answers.biggestChallenge}
                onChange={(v) => setAnswers((a) => ({ ...a, biggestChallenge: v }))}
              />

              <Button
                className="w-full"
                disabled={!canProceed || mutation.isPending}
                onClick={handleGetRecommendation}
              >
                {mutation.isPending ? 'Thinking…' : 'Get My Recommendation →'}
              </Button>

              {mutation.isError && (
                <p className="text-sm text-red-600 text-center">
                  Something went wrong. Please try again.
                </p>
              )}
            </CardContent>
          </Card>
        )}

        {/* Step 2 — Recommendation card */}
        {step === 2 && recommendation && (
          <Card className="border-primary/40">
            <CardHeader>
              <CardTitle className="text-lg">Your personalised plan</CardTitle>
            </CardHeader>
            <CardContent className="space-y-5">
              <div className="flex gap-4 flex-wrap">
                <div className="rounded-md bg-muted px-4 py-2 text-center">
                  <p className="text-xs text-muted-foreground uppercase tracking-wide">Type</p>
                  <p className="font-semibold mt-0.5">{categoryLabel(recommendation.category)}</p>
                </div>
                <div className="rounded-md bg-muted px-4 py-2 text-center">
                  <p className="text-xs text-muted-foreground uppercase tracking-wide">Difficulty</p>
                  <p className={`font-semibold mt-0.5 ${difficultyColor(recommendation.difficulty)}`}>
                    {recommendation.difficulty}
                  </p>
                </div>
                <div className="rounded-md bg-muted px-4 py-2 text-center">
                  <p className="text-xs text-muted-foreground uppercase tracking-wide">Interviewer</p>
                  <p className="font-semibold mt-0.5">{recommendation.personality}</p>
                </div>
              </div>

              <div className="rounded-lg bg-muted/60 border p-4 text-sm text-muted-foreground leading-relaxed">
                {recommendation.rationale}
              </div>

              <Button className="w-full" size="lg" onClick={() => setStep(3)}>
                Start My First Interview →
              </Button>

              <button
                type="button"
                className="w-full text-xs text-muted-foreground hover:underline"
                onClick={() => setStep(1)}
              >
                ← Change my answers
              </button>
            </CardContent>
          </Card>
        )}

        {/* Step 3 — Confirmation / launch */}
        {step === 3 && recommendation && (
          <Card>
            <CardContent className="pt-6 space-y-4 text-center">
              <div className="text-5xl">🎯</div>
              <p className="text-lg font-semibold">Ready when you are.</p>
              <p className="text-sm text-muted-foreground">
                Your first session: <strong>{categoryLabel(recommendation.category)}</strong> ·{' '}
                <strong className={difficultyColor(recommendation.difficulty)}>
                  {recommendation.difficulty}
                </strong>{' '}
                · {recommendation.personality}
              </p>
              <Button className="w-full" size="lg" onClick={handleStartInterview}>
                Start My First Interview
              </Button>
              <button
                type="button"
                className="w-full text-xs text-muted-foreground hover:underline"
                onClick={() => navigate('/dashboard')}
              >
                Skip for now — go to dashboard
              </button>
            </CardContent>
          </Card>
        )}

        {/* Step indicator */}
        <div className="flex justify-center gap-2">
          {([1, 2, 3] as const).map((s) => (
            <div
              key={s}
              className={[
                'h-2 rounded-full transition-all',
                step >= s ? 'w-6 bg-primary' : 'w-2 bg-muted-foreground/30',
              ].join(' ')}
            />
          ))}
        </div>
      </div>
    </div>
  )
}
