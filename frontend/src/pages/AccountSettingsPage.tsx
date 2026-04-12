import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { getMe, getMemory, resetMemory, setMemoryEnabled } from '@/lib/api'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'

export default function AccountSettingsPage() {
  const queryClient = useQueryClient()
  const [resetConfirm, setResetConfirm] = useState(false)

  const { data: user } = useQuery({ queryKey: ['me'], queryFn: getMe, staleTime: 60_000 })
  const { data: memory } = useQuery({ queryKey: ['memory'], queryFn: getMemory })

  const toggleMutation = useMutation({
    mutationFn: (enabled: boolean) => setMemoryEnabled(enabled),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me'] })
    },
  })

  const resetMutation = useMutation({
    mutationFn: resetMemory,
    onSuccess: () => {
      setResetConfirm(false)
      queryClient.invalidateQueries({ queryKey: ['memory'] })
    },
  })

  const memoryEnabled = user?.memoryEnabled ?? true

  return (
    <div className="min-h-screen bg-background p-6">
      <div className="max-w-2xl mx-auto space-y-6">
        <h1 className="text-2xl font-bold tracking-tight">Account Settings</h1>

        {/* AI Memory card */}
        <Card>
          <CardHeader>
            <CardTitle className="text-lg">AI Memory</CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <div className="flex items-center justify-between gap-4">
              <div>
                <p className="font-medium text-sm">Remember me across sessions</p>
                <p className="text-xs text-muted-foreground mt-0.5">
                  The AI will track your progress and avoid repeating questions you've already seen.
                </p>
              </div>
              <button
                type="button"
                role="switch"
                aria-checked={memoryEnabled}
                disabled={toggleMutation.isPending}
                onClick={() => toggleMutation.mutate(!memoryEnabled)}
                className={[
                  'relative inline-flex h-6 w-11 items-center rounded-full transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:cursor-not-allowed disabled:opacity-50',
                  memoryEnabled ? 'bg-primary' : 'bg-input',
                ].join(' ')}
              >
                <span
                  className={[
                    'inline-block h-4 w-4 rounded-full bg-white shadow-sm transition-transform',
                    memoryEnabled ? 'translate-x-6' : 'translate-x-1',
                  ].join(' ')}
                />
              </button>
            </div>

            {memory && memory.sessionCount > 0 && (
              <div className="rounded-lg bg-muted/60 border p-4 space-y-2 text-sm">
                <p className="font-medium text-muted-foreground uppercase tracking-wide text-xs">What the AI knows about you</p>
                <p><span className="font-semibold">Sessions completed:</span> {memory.sessionCount}</p>
                {memory.topDimension && (
                  <p><span className="font-semibold">Top strength:</span> {memory.topDimension}</p>
                )}
                {memory.weaknesses.length > 0 && (
                  <p><span className="font-semibold">Consistent challenges:</span> {memory.weaknesses.join(', ')}</p>
                )}
                {memory.questionsSeen.length > 0 && (
                  <p><span className="font-semibold">Questions on record:</span> {memory.questionsSeen.length}</p>
                )}
              </div>
            )}

            {memory && memory.sessionCount === 0 && (
              <p className="text-sm text-muted-foreground">
                Complete your first interview to start building your memory profile.
              </p>
            )}

            {!resetConfirm ? (
              <Button
                variant="outline"
                size="sm"
                className="text-destructive border-destructive/30 hover:bg-destructive/5"
                onClick={() => setResetConfirm(true)}
                disabled={!memory || memory.sessionCount === 0}
              >
                Reset memory profile
              </Button>
            ) : (
              <div className="flex items-center gap-3">
                <p className="text-sm text-muted-foreground">This will wipe all session history. Are you sure?</p>
                <Button
                  variant="destructive"
                  size="sm"
                  disabled={resetMutation.isPending}
                  onClick={() => resetMutation.mutate()}
                >
                  {resetMutation.isPending ? 'Resetting…' : 'Yes, reset'}
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setResetConfirm(false)}>
                  Cancel
                </Button>
              </div>
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
