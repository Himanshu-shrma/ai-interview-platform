import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

const difficultyColors: Record<string, string> = {
  EASY: 'bg-green-100 text-green-800 border-green-200',
  MEDIUM: 'bg-yellow-100 text-yellow-800 border-yellow-200',
  HARD: 'bg-red-100 text-red-800 border-red-200',
}

const difficultyLabels: Record<string, string> = {
  EASY: 'Easy',
  MEDIUM: 'Medium',
  HARD: 'Hard',
}

interface DifficultyBadgeProps {
  difficulty: string
  className?: string
}

export function DifficultyBadge({ difficulty, className }: DifficultyBadgeProps) {
  return (
    <Badge
      variant="outline"
      className={cn(difficultyColors[difficulty] ?? 'bg-gray-100 text-gray-800', className)}
    >
      {difficultyLabels[difficulty] ?? difficulty}
    </Badge>
  )
}
