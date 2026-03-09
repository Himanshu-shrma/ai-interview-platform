import { lazy, Suspense, useCallback, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { useInterviewDetail } from '@/hooks/useInterviews'
import { useInterviewSocket } from '@/hooks/useInterviewSocket'
import { useConversation } from '@/hooks/useConversation'
import { ConversationPanel } from '@/components/interview/ConversationPanel'
const CodeEditor = lazy(() => import('@/components/interview/CodeEditor').then(m => ({ default: m.CodeEditor })))
import { TestResults } from '@/components/interview/TestResults'
import { TimerDisplay } from '@/components/interview/TimerDisplay'
import { HintPanel } from '@/components/interview/HintPanel'
import { CategoryBadge } from '@/components/shared/CategoryBadge'
import { DifficultyBadge } from '@/components/shared/DifficultyBadge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from '@/components/ui/dialog'
import { Loader2 } from 'lucide-react'
import type {
  WsOutboundMessage,
  AiChunkMessage,
  AiMessageMessage,
  StateChangeMessage,
  CodeResultMessage,
  CodeRunResultMessage,
  HintDeliveredMessage,
  QuestionTransitionMessage,
  SessionEndMessage,
  WsErrorMessage,
} from '@/types'

export default function InterviewPage() {
  const { sessionId } = useParams<{ sessionId: string }>()
  const navigate = useNavigate()
  const { data: session } = useInterviewDetail(sessionId ?? '')

  // Conversation state
  const { messages, addCandidateMessage, appendAiToken, finalizeAiMessage, addAiMessage } =
    useConversation()

  // Interview state
  const [currentState, setCurrentState] = useState('INTERVIEW_STARTING')
  const [currentCode, setCurrentCode] = useState('')
  const [currentLanguage, setCurrentLanguage] = useState('python')
  const [codeResult, setCodeResult] = useState<CodeResultMessage | CodeRunResultMessage | null>(null)
  const [isCodeRunning, setIsCodeRunning] = useState(false)
  const [hintState, setHintState] = useState<{
    hint: string
    level: number
    hintsRemaining: number
  } | null>(null)
  const [hintsGiven, setHintsGiven] = useState(0)
  const [isAiThinking, setIsAiThinking] = useState(false)
  const [showCodeEditor, setShowCodeEditor] = useState(false)
  const [reportId, setReportId] = useState<string | null>(null)
  const [endDialogOpen, setEndDialogOpen] = useState(false)
  const [currentQuestionIndex, setCurrentQuestionIndex] = useState(0)

  // Track whether AI is mid-stream
  const isStreamingRef = useRef(false)

  // Ref for session data — avoids putting `session` in handleMessage deps
  const sessionRef = useRef(session)
  sessionRef.current = session

  // WS message handler
  const handleMessage = useCallback(
    (msg: WsOutboundMessage) => {
      switch (msg.type) {
        case 'INTERVIEW_STARTED': {
          // Initialize language from session
          if (sessionRef.current?.programmingLanguage) {
            setCurrentLanguage(sessionRef.current.programmingLanguage)
          }
          break
        }

        case 'AI_CHUNK': {
          const chunk = msg as AiChunkMessage
          if (chunk.done) {
            isStreamingRef.current = false
            finalizeAiMessage()
          } else {
            setIsAiThinking(false)
            isStreamingRef.current = true
            appendAiToken(chunk.delta)
          }
          break
        }

        case 'AI_MESSAGE': {
          const aiMsg = msg as AiMessageMessage
          setIsAiThinking(false)
          isStreamingRef.current = false
          addAiMessage(aiMsg.text)
          break
        }

        case 'STATE_CHANGE': {
          const sc = msg as StateChangeMessage
          setCurrentState(sc.state)
          if (sc.state === 'CODING_CHALLENGE') {
            setShowCodeEditor(true)
          }
          break
        }

        case 'CODE_RESULT': {
          setIsCodeRunning(false)
          setCodeResult(msg as CodeResultMessage)
          break
        }

        case 'CODE_RUN_RESULT': {
          setIsCodeRunning(false)
          setCodeResult(msg as CodeRunResultMessage)
          break
        }

        case 'HINT_DELIVERED': {
          const hint = msg as HintDeliveredMessage
          if (!hint.refused) {
            setHintState({
              hint: hint.hint,
              level: hint.level,
              hintsRemaining: hint.hintsRemaining,
            })
            setHintsGiven(3 - hint.hintsRemaining)
          }
          break
        }

        case 'QUESTION_TRANSITION': {
          const qt = msg as QuestionTransitionMessage
          // Reset per-question UI state for the new question
          setCurrentQuestionIndex(qt.questionIndex)
          setCurrentCode('')
          setCodeResult(null)
          setHintState(null)
          setHintsGiven(0)
          setShowCodeEditor(false)
          addAiMessage(`Moving to Question ${qt.questionIndex + 1}: **${qt.questionTitle}**`)
          break
        }

        case 'SESSION_END': {
          const end = msg as SessionEndMessage
          setReportId(end.reportId)
          setTimeout(() => {
            navigate(`/report/${sessionId}`)
          }, 2000)
          break
        }

        case 'ERROR': {
          const err = msg as WsErrorMessage
          console.error(`[WS Error] ${err.code}: ${err.message}`)
          break
        }

        case 'PONG':
          break

        default:
          break
      }
    },
    [finalizeAiMessage, appendAiToken, addAiMessage, navigate, sessionId]
  )

  const { send, status } = useInterviewSocket({
    sessionId: sessionId ?? '',
    onMessage: handleMessage,
  })

  // Actions
  function handleSendMessage(content: string) {
    addCandidateMessage(content)
    setIsAiThinking(true)
    send({ type: 'CANDIDATE_MESSAGE', text: content })
  }

  function handleRequestHint() {
    send({ type: 'REQUEST_HINT', hintLevel: hintsGiven + 1 })
  }

  function handleCodeRun(code: string, stdin?: string) {
    setIsCodeRunning(true)
    setCodeResult(null)
    send({
      type: 'CODE_RUN',
      code,
      language: currentLanguage,
      ...(stdin ? { stdin } : {}),
    })
  }

  function handleCodeSubmit(code: string) {
    setIsCodeRunning(true)
    setCodeResult(null)
    const sqId = session?.questions?.[0]?.id
    send({
      type: 'CODE_SUBMIT',
      code,
      language: currentLanguage,
      ...(sqId ? { sessionQuestionId: sqId } : {}),
    })
  }

  function handleEndInterview() {
    setEndDialogOpen(false)
    send({ type: 'END_INTERVIEW', reason: 'CANDIDATE_ENDED' })
  }

  function handleTimeExpired() {
    send({ type: 'END_INTERVIEW', reason: 'TIME_EXPIRED' })
  }

  const isEnded = currentState === 'EVALUATING' || currentState === 'INTERVIEW_END' || !!reportId
  const isEvaluating = currentState === 'EVALUATING' && !reportId
  const headerTitle = session?.category
    ? `${session.category.charAt(0) + session.category.slice(1).toLowerCase().replace('_', ' ')} Interview`
    : 'Interview Session'

  // Loading screen
  if (!session) {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <Loader2 className="h-8 w-8 animate-spin text-muted-foreground" />
      </div>
    )
  }

  return (
    <div className="flex h-screen flex-col bg-background">
      {/* Header */}
      <header className="flex items-center justify-between border-b px-4 py-2 shrink-0">
        <div className="flex items-center gap-3 min-w-0">
          <h1 className="text-sm font-semibold truncate">{headerTitle}</h1>
          {session.questions.length > 1 && (
            <span className="text-xs text-muted-foreground font-medium">
              Q{currentQuestionIndex + 1}/{session.questions.length}
            </span>
          )}
          <CategoryBadge category={session.category} />
          <DifficultyBadge difficulty={session.difficulty} />
          {status !== 'connected' && (
            <span className="text-xs text-yellow-600 animate-pulse">
              {status === 'connecting' ? 'Reconnecting...' : 'Disconnected'}
            </span>
          )}
        </div>
        <div className="flex items-center gap-3 shrink-0">
          <TimerDisplay
            durationMinutes={session.durationMinutes}
            startedAt={session.startedAt}
            onTimeExpired={handleTimeExpired}
          />
          <Dialog open={endDialogOpen} onOpenChange={setEndDialogOpen}>
            <DialogTrigger asChild>
              <Button variant="destructive" size="sm" disabled={isEnded}>
                End Interview
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle>End Interview?</DialogTitle>
                <DialogDescription>
                  Your interview will be evaluated and you'll receive a detailed report.
                  This cannot be undone.
                </DialogDescription>
              </DialogHeader>
              <DialogFooter>
                <Button variant="outline" onClick={() => setEndDialogOpen(false)}>
                  Continue Interview
                </Button>
                <Button variant="destructive" onClick={handleEndInterview}>
                  End Interview
                </Button>
              </DialogFooter>
            </DialogContent>
          </Dialog>
        </div>
      </header>

      {/* Evaluating overlay */}
      {isEvaluating && (
        <div className="absolute inset-0 z-50 flex flex-col items-center justify-center bg-background/80 backdrop-blur-sm">
          <Loader2 className="h-12 w-12 animate-spin text-primary mb-4" />
          <h2 className="text-xl font-semibold">Generating your report...</h2>
          <p className="text-sm text-muted-foreground mt-1">
            This usually takes 10-15 seconds
          </p>
        </div>
      )}

      {/* Main content */}
      <div className="flex flex-1 min-h-0">
        {/* Conversation panel */}
        <div className={`flex flex-col border-r ${showCodeEditor ? 'w-1/2 lg:w-2/5' : 'w-full max-w-3xl mx-auto'}`}>
          {/* Hint panel */}
          {hintState && (
            <div className="px-4 pt-3">
              <HintPanel
                hint={hintState.hint}
                level={hintState.level}
                hintsRemaining={hintState.hintsRemaining}
              />
            </div>
          )}
          <ConversationPanel
            messages={messages}
            isAiThinking={isAiThinking}
            hintsGiven={hintsGiven}
            onSendMessage={handleSendMessage}
            onRequestHint={handleRequestHint}
            disabled={isEnded}
          />
        </div>

        {/* Code panel */}
        {showCodeEditor && (
          <div className="flex flex-1 flex-col min-w-0">
            <div className="flex-1 min-h-0">
              <Suspense fallback={<div className="flex items-center justify-center h-full text-muted-foreground">Loading editor...</div>}>
                <CodeEditor
                  code={currentCode}
                  language={currentLanguage}
                  onCodeChange={setCurrentCode}
                  onRun={handleCodeRun}
                  onSubmit={handleCodeSubmit}
                  isRunning={isCodeRunning}
                />
              </Suspense>
            </div>
            {(codeResult || isCodeRunning) && (
              <div className="border-t max-h-[30%] overflow-y-auto">
                <TestResults codeResult={codeResult} isRunning={isCodeRunning} />
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
