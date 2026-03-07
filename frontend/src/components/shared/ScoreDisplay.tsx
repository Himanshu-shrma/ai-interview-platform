import { cn } from '@/lib/utils'

function scoreColor(score: number): string {
  if (score >= 9) return 'text-blue-600'
  if (score >= 7) return 'text-green-600'
  if (score >= 5) return 'text-yellow-600'
  return 'text-red-600'
}

interface ScoreDisplayProps {
  score: number
  max?: number
  className?: string
}

export function ScoreDisplay({ score, max = 10, className }: ScoreDisplayProps) {
  return (
    <span className={cn('text-2xl font-bold tabular-nums', scoreColor(score), className)}>
      {score.toFixed(1)}
      <span className="text-sm font-normal text-muted-foreground">/{max}</span>
    </span>
  )
}
