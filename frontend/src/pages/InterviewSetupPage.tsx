import { useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Code2, MessageSquare, Server, GitBranch, Loader2 } from 'lucide-react'
import { PageHeader } from '@/components/shared/PageHeader'
import { useStartInterview, useLanguages } from '@/hooks/useInterviews'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { cn } from '@/lib/utils'
import type { InterviewCategory, Difficulty } from '@/types'
import { isAxiosError } from 'axios'

// ── Category card definitions ──

const categories: {
  value: InterviewCategory
  label: string
  subtitle: string
  icon: React.ElementType
  beta?: boolean
}[] = [
  {
    value: 'CODING',
    label: 'Coding Interview',
    subtitle: 'DSA problems, algorithms, data structures',
    icon: Code2,
  },
  {
    value: 'DSA',
    label: 'DSA Focus',
    subtitle: 'Pure data structures and algorithms',
    icon: GitBranch,
  },
  {
    value: 'BEHAVIORAL',
    label: 'Behavioral Interview',
    subtitle: 'STAR method, leadership, teamwork',
    icon: MessageSquare,
    beta: true,
  },
  {
    value: 'SYSTEM_DESIGN',
    label: 'System Design',
    subtitle: 'Architecture, scalability, trade-offs',
    icon: Server,
    beta: true,
  },
]

// ── Personality card definitions ──

const personalities = [
  {
    value: 'faang_senior',
    label: 'FAANG Senior',
    description: 'Direct, technical, Big-O focused',
  },
  {
    value: 'friendly',
    label: 'Friendly Mentor',
    description: 'Supportive, encouraging',
  },
  {
    value: 'startup',
    label: 'Startup Engineer',
    description: 'Pragmatic, ship-focused',
  },
  {
    value: 'adaptive',
    label: 'Adaptive',
    description: 'Matches your level',
  },
]

const difficulties: { value: Difficulty; label: string; color: string }[] = [
  { value: 'EASY', label: 'Easy', color: 'bg-green-500' },
  { value: 'MEDIUM', label: 'Medium', color: 'bg-yellow-500' },
  { value: 'HARD', label: 'Hard', color: 'bg-red-500' },
]

const durations = [30, 45, 60]

const needsLanguage = (cat?: InterviewCategory) => cat === 'CODING' || cat === 'DSA'

export default function InterviewSetupPage() {
  const [category, setCategory] = useState<InterviewCategory | undefined>()
  const [difficulty, setDifficulty] = useState<Difficulty | undefined>()
  const [personality, setPersonality] = useState('friendly')
  const [language, setLanguage] = useState('python')
  const [targetRole, setTargetRole] = useState('')
  const [targetCompany, setTargetCompany] = useState('')
  const [durationMinutes, setDurationMinutes] = useState(45)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  const { data: languages } = useLanguages()
  const startMutation = useStartInterview()

  const canSubmit =
    category && difficulty && (!needsLanguage(category) || language) && !startMutation.isPending

  function handleSubmit() {
    if (!category || !difficulty) return
    setErrorMessage(null)

    startMutation.mutate(
      {
        category,
        difficulty,
        personality,
        programmingLanguage: needsLanguage(category) ? language : undefined,
        targetRole: targetRole.trim() || undefined,
        targetCompany: targetCompany.trim() || undefined,
        durationMinutes,
      },
      {
        onError: (err) => {
          if (isAxiosError(err) && err.response?.status === 429) {
            setErrorMessage(
              "You've used all 3 free interviews this month. Upgrade to Pro for unlimited interviews."
            )
          } else {
            setErrorMessage('Something went wrong. Please try again.')
          }
        },
      }
    )
  }

  return (
    <div className="min-h-screen bg-background">
      <PageHeader />

      <main className="mx-auto max-w-[600px] px-4 py-8">
        <Button variant="ghost" size="sm" asChild className="mb-4">
          <Link to="/dashboard">
            <ArrowLeft className="mr-1 h-4 w-4" />
            Back to Dashboard
          </Link>
        </Button>

        <h1 className="text-2xl font-bold mb-1">Configure Your Interview</h1>
        <p className="text-muted-foreground mb-6">Customize your practice session</p>

        <div className="space-y-8">
          {/* Category */}
          <section>
            <Label className="text-sm font-medium mb-3 block">Interview Category</Label>
            <div className="grid grid-cols-2 gap-3">
              {categories.map((cat) => {
                const Icon = cat.icon
                const selected = category === cat.value
                return (
                  <button
                    key={cat.value}
                    type="button"
                    onClick={() => setCategory(cat.value)}
                    className={cn(
                      'relative flex flex-col items-start gap-1 rounded-lg border-2 p-4 text-left transition-colors',
                      selected
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:border-muted-foreground/50'
                    )}
                  >
                    {cat.beta && (
                      <Badge variant="secondary" className="absolute right-2 top-2 text-[10px]">
                        Beta
                      </Badge>
                    )}
                    <Icon className="h-5 w-5 text-muted-foreground" />
                    <span className="font-medium text-sm">{cat.label}</span>
                    <span className="text-xs text-muted-foreground">{cat.subtitle}</span>
                  </button>
                )
              })}
            </div>
          </section>

          {/* Difficulty */}
          <section>
            <Label className="text-sm font-medium mb-3 block">Difficulty</Label>
            <div className="flex gap-2">
              {difficulties.map((d) => (
                <Button
                  key={d.value}
                  type="button"
                  variant={difficulty === d.value ? 'default' : 'outline'}
                  className={cn(difficulty === d.value && d.color, 'flex-1')}
                  onClick={() => setDifficulty(d.value)}
                >
                  {d.label}
                </Button>
              ))}
            </div>
          </section>

          {/* Target Role */}
          <section>
            <Label htmlFor="targetRole" className="text-sm font-medium mb-2 block">
              Target Role <span className="text-muted-foreground font-normal">(optional)</span>
            </Label>
            <Input
              id="targetRole"
              placeholder="e.g. Senior Backend Engineer"
              value={targetRole}
              onChange={(e) => setTargetRole(e.target.value)}
            />
          </section>

          {/* Target Company */}
          <section>
            <Label htmlFor="targetCompany" className="text-sm font-medium mb-2 block">
              Target Company <span className="text-muted-foreground font-normal">(optional)</span>
            </Label>
            <Input
              id="targetCompany"
              placeholder="e.g. Google, Amazon, Meta"
              value={targetCompany}
              onChange={(e) => setTargetCompany(e.target.value)}
            />
            <p className="text-xs text-muted-foreground mt-1">
              We'll tailor the question style
            </p>
          </section>

          {/* Personality */}
          <section>
            <Label className="text-sm font-medium mb-3 block">Interviewer Personality</Label>
            <div className="grid grid-cols-2 gap-3">
              {personalities.map((p) => {
                const selected = personality === p.value
                return (
                  <button
                    key={p.value}
                    type="button"
                    onClick={() => setPersonality(p.value)}
                    className={cn(
                      'flex flex-col items-start gap-0.5 rounded-lg border-2 p-3 text-left transition-colors',
                      selected
                        ? 'border-primary bg-primary/5'
                        : 'border-border hover:border-muted-foreground/50'
                    )}
                  >
                    <span className="font-medium text-sm">{p.label}</span>
                    <span className="text-xs text-muted-foreground">{p.description}</span>
                  </button>
                )
              })}
            </div>
          </section>

          {/* Language — only for CODING/DSA */}
          {needsLanguage(category) && (
            <section className="animate-in fade-in slide-in-from-top-2 duration-200">
              <Label className="text-sm font-medium mb-2 block">Programming Language</Label>
              <Select value={language} onValueChange={setLanguage}>
                <SelectTrigger>
                  <SelectValue placeholder="Select a language" />
                </SelectTrigger>
                <SelectContent>
                  {(languages ?? ['python', 'java', 'javascript']).map((lang) => (
                    <SelectItem key={lang} value={lang}>
                      {lang.charAt(0).toUpperCase() + lang.slice(1)}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </section>
          )}

          {/* Duration */}
          <section>
            <Label className="text-sm font-medium mb-3 block">Duration</Label>
            <div className="flex gap-2">
              {durations.map((d) => (
                <Button
                  key={d}
                  type="button"
                  variant={durationMinutes === d ? 'default' : 'outline'}
                  className="flex-1"
                  onClick={() => setDurationMinutes(d)}
                >
                  {d} min
                </Button>
              ))}
            </div>
          </section>

          {/* Error message */}
          {errorMessage && (
            <Card className="border-red-200 bg-red-50">
              <CardContent className="py-3 text-sm text-red-700">{errorMessage}</CardContent>
            </Card>
          )}

          {/* Submit */}
          <Button
            className="w-full"
            size="lg"
            disabled={!canSubmit}
            onClick={handleSubmit}
          >
            {startMutation.isPending ? (
              <>
                <Loader2 className="mr-2 h-4 w-4 animate-spin" />
                Preparing your interview...
              </>
            ) : (
              'Start Interview'
            )}
          </Button>
        </div>
      </main>
    </div>
  )
}
