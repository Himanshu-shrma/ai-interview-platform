import { useEffect, useState } from 'react'
import { cn } from '@/lib/utils'

interface TimerDisplayProps {
  durationMinutes: number
  startedAt?: string
  onTimeExpired?: () => void
}

export function TimerDisplay({ durationMinutes, startedAt, onTimeExpired }: TimerDisplayProps) {
  const [remaining, setRemaining] = useState(() => {
    if (!startedAt) return durationMinutes * 60
    const elapsed = Math.floor((Date.now() - new Date(startedAt).getTime()) / 1000)
    return Math.max(0, durationMinutes * 60 - elapsed)
  })

  useEffect(() => {
    if (remaining <= 0) {
      onTimeExpired?.()
      return
    }

    const timer = setInterval(() => {
      setRemaining((prev) => {
        const next = prev - 1
        if (next <= 0) {
          onTimeExpired?.()
          return 0
        }
        return next
      })
    }, 1000)

    return () => clearInterval(timer)
  }, [remaining <= 0, onTimeExpired])

  const mins = Math.floor(remaining / 60)
  const secs = remaining % 60
  const display = `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`

  const isWarning = remaining > 0 && remaining <= 300
  const isCritical = remaining > 0 && remaining <= 120

  return (
    <span
      className={cn(
        'font-mono text-sm tabular-nums font-medium',
        isCritical && 'text-red-600 animate-pulse',
        isWarning && !isCritical && 'text-yellow-600 animate-pulse [animation-duration:2s]',
        !isWarning && !isCritical && 'text-muted-foreground'
      )}
    >
      {display}
    </span>
  )
}
