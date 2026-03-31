import { useState } from 'react'
import Editor from '@monaco-editor/react'
import { Button } from '@/components/ui/button'
import { Loader2, Play, Upload } from 'lucide-react'

const languageMap: Record<string, string> = {
  python: 'python',
  python3: 'python',
  java: 'java',
  javascript: 'javascript',
  typescript: 'typescript',
  cpp: 'cpp',
  'c++': 'cpp',
  c: 'c',
  go: 'go',
  rust: 'rust',
  kotlin: 'kotlin',
  ruby: 'ruby',
  swift: 'swift',
  scala: 'scala',
}

interface CodeEditorProps {
  code: string
  language: string
  onCodeChange: (code: string) => void
  onRun: (code: string, stdin?: string) => void
  onSubmit: (code: string) => void
  isRunning: boolean
  showRunSubmit?: boolean
}

export function CodeEditor({
  code,
  language,
  onCodeChange,
  onRun,
  onSubmit,
  isRunning,
  showRunSubmit = true,
}: CodeEditorProps) {
  const [showStdin, setShowStdin] = useState(false)
  const [stdin, setStdin] = useState('')

  const monacoLang = languageMap[language.toLowerCase()] ?? 'plaintext'

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div className="flex items-center justify-between border-b px-3 py-2">
        <span className="text-xs font-medium text-muted-foreground uppercase">
          {language}
        </span>
        <div className="flex items-center gap-2">
          {showRunSubmit && (
            <>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => setShowStdin(!showStdin)}
                className="text-xs"
              >
                {showStdin ? 'Hide Input' : 'Custom Input'}
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => onRun(code, stdin || undefined)}
                disabled={isRunning}
              >
                {isRunning ? (
                  <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
                ) : (
                  <Play className="mr-1 h-3.5 w-3.5" />
                )}
                Run
              </Button>
            </>
          )}
          {showRunSubmit && (
          <Button
            size="sm"
            onClick={() => onSubmit(code)}
            disabled={isRunning}
            className="bg-green-600 hover:bg-green-700"
          >
            {isRunning ? (
              <Loader2 className="mr-1 h-3.5 w-3.5 animate-spin" />
            ) : (
              <Upload className="mr-1 h-3.5 w-3.5" />
            )}
            Submit
          </Button>
          )}
        </div>
      </div>

      {/* Editor */}
      <div className="flex-1 min-h-0">
        <Editor
          language={monacoLang}
          theme="vs-dark"
          value={code}
          onChange={(val) => onCodeChange(val ?? '')}
          options={{
            fontSize: 14,
            minimap: { enabled: false },
            scrollBeyondLastLine: false,
            wordWrap: 'on',
            tabSize: 4,
            automaticLayout: true,
          }}
        />
      </div>

      {/* stdin */}
      {showStdin && (
        <div className="border-t">
          <div className="px-3 py-1 text-xs font-medium text-muted-foreground">Custom Input</div>
          <textarea
            className="w-full resize-none border-0 bg-zinc-900 px-3 py-2 text-sm text-zinc-100 font-mono focus:outline-none"
            rows={3}
            placeholder="Enter stdin here..."
            value={stdin}
            onChange={(e) => setStdin(e.target.value)}
          />
        </div>
      )}
    </div>
  )
}
