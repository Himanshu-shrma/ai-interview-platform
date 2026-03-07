import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

const categoryColors: Record<string, string> = {
  CODING: 'bg-blue-100 text-blue-800 border-blue-200',
  DSA: 'bg-purple-100 text-purple-800 border-purple-200',
  BEHAVIORAL: 'bg-green-100 text-green-800 border-green-200',
  SYSTEM_DESIGN: 'bg-orange-100 text-orange-800 border-orange-200',
  CASE_STUDY: 'bg-yellow-100 text-yellow-800 border-yellow-200',
}

const categoryLabels: Record<string, string> = {
  CODING: 'Coding',
  DSA: 'DSA',
  BEHAVIORAL: 'Behavioral',
  SYSTEM_DESIGN: 'System Design',
  CASE_STUDY: 'Case Study',
}

interface CategoryBadgeProps {
  category: string
  className?: string
}

export function CategoryBadge({ category, className }: CategoryBadgeProps) {
  return (
    <Badge
      variant="outline"
      className={cn(categoryColors[category] ?? 'bg-gray-100 text-gray-800', className)}
    >
      {categoryLabels[category] ?? category}
    </Badge>
  )
}
