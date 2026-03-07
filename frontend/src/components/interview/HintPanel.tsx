import { Card, CardContent } from '@/components/ui/card'

interface HintPanelProps {
  hint: string
  level: number
  hintsRemaining: number
}

export function HintPanel({ hint, level, hintsRemaining }: HintPanelProps) {
  const dots = Array.from({ length: 3 }, (_, i) => i < 3 - hintsRemaining)

  return (
    <Card className="border-yellow-200 bg-yellow-50 animate-in slide-in-from-bottom-2 duration-300">
      <CardContent className="py-3 space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium text-yellow-800">
            Hint (Level {level} of 3)
          </span>
          <span className="text-xs text-yellow-600 flex items-center gap-1">
            {dots.map((used, i) => (
              <span
                key={i}
                className={used ? 'text-yellow-600' : 'text-yellow-300'}
              >
                {used ? '\u25CF' : '\u25CB'}
              </span>
            ))}
            <span className="ml-1">{hintsRemaining} remaining</span>
          </span>
        </div>
        <p className="text-sm text-yellow-900">{hint}</p>
      </CardContent>
    </Card>
  )
}
