import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'
import type { CodeResultMessage, CodeRunResultMessage, TestResult } from '@/types'

interface TestResultsProps {
  codeResult: CodeResultMessage | CodeRunResultMessage | null
  isRunning: boolean
}

function TestCaseRow({ test, index }: { test: TestResult; index: number }) {
  const [expanded, setExpanded] = useState(!test.passed)

  return (
    <div className="border rounded-md">
      <button
        type="button"
        className="flex w-full items-center justify-between px-3 py-2 text-sm hover:bg-muted/50"
        onClick={() => setExpanded(!expanded)}
      >
        <span className="flex items-center gap-2">
          <span>{test.passed ? '\u2705' : '\u274C'}</span>
          <span>Test {index + 1}</span>
          <span className="text-muted-foreground">
            {test.passed ? 'Passed' : 'Failed'}
          </span>
        </span>
        {test.runtimeMs != null && (
          <span className="text-xs text-muted-foreground">{test.runtimeMs}ms</span>
        )}
      </button>
      {expanded && !test.passed && (
        <div className="border-t px-3 py-2 space-y-1 text-xs font-mono bg-muted/30">
          {test.input != null && (
            <div>
              <span className="text-muted-foreground">Input: </span>
              <span>{test.input}</span>
            </div>
          )}
          {test.expected != null && (
            <div>
              <span className="text-muted-foreground">Expected: </span>
              <span className="text-green-600">{test.expected}</span>
            </div>
          )}
          {test.actual != null && (
            <div>
              <span className="text-muted-foreground">Got: </span>
              <span className="text-red-600">{test.actual}</span>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

export function TestResults({ codeResult, isRunning }: TestResultsProps) {
  if (isRunning) {
    return (
      <div className="p-3 space-y-2">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-8 w-full" />
        <Skeleton className="h-8 w-full" />
      </div>
    )
  }

  if (!codeResult) return null

  // CODE_RUN_RESULT — simple stdout/stderr
  if (codeResult.type === 'CODE_RUN_RESULT') {
    const result = codeResult as CodeRunResultMessage
    return (
      <div className="p-3 space-y-2">
        <span className="text-sm font-medium">Output</span>
        {result.stdout && (
          <pre className="rounded bg-zinc-900 px-3 py-2 text-xs text-zinc-100 font-mono whitespace-pre-wrap">
            {result.stdout}
          </pre>
        )}
        {result.stderr && (
          <pre className="rounded bg-red-950 px-3 py-2 text-xs text-red-300 font-mono whitespace-pre-wrap">
            {result.stderr}
          </pre>
        )}
      </div>
    )
  }

  // CODE_RESULT — with test results
  const result = codeResult as CodeResultMessage
  const hasTests = result.testResults && result.testResults.length > 0
  const allPassed = hasTests && result.testResults!.every((t) => t.passed)

  return (
    <div className="p-3 space-y-3">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-sm font-medium">Results</span>
          <Badge
            variant="outline"
            className={cn(
              allPassed
                ? 'bg-green-100 text-green-800 border-green-200'
                : 'bg-red-100 text-red-800 border-red-200'
            )}
          >
            {result.status}
          </Badge>
        </div>
        {result.runtimeMs != null && (
          <span className="text-xs text-muted-foreground">
            Ran in {result.runtimeMs}ms
          </span>
        )}
      </div>

      {/* Compile / runtime errors */}
      {result.stderr && (
        <pre className="rounded bg-red-950 px-3 py-2 text-xs text-red-300 font-mono whitespace-pre-wrap">
          {result.stderr}
        </pre>
      )}

      {/* stdout (if no test results) */}
      {!hasTests && result.stdout && (
        <pre className="rounded bg-zinc-900 px-3 py-2 text-xs text-zinc-100 font-mono whitespace-pre-wrap">
          {result.stdout}
        </pre>
      )}

      {/* Test case rows */}
      {hasTests && (
        <div className="space-y-2">
          {result.testResults!.map((test, i) => (
            <TestCaseRow key={i} test={test} index={i} />
          ))}
        </div>
      )}
    </div>
  )
}
