// ── User ──

export interface User {
  id: string
  clerkUserId: string
  email: string
  fullName: string
  plan: 'FREE' | 'PRO'
  createdAt: string
}

// ── Interview ──

export type InterviewCategory = 'CODING' | 'DSA' | 'BEHAVIORAL' | 'SYSTEM_DESIGN' | 'CASE_STUDY'
export type InterviewType = 'DSA' | 'CODING' | 'SYSTEM_DESIGN' | 'BEHAVIORAL'
export type Difficulty = 'EASY' | 'MEDIUM' | 'HARD'
export type SessionStatus = 'PENDING' | 'ACTIVE' | 'COMPLETED' | 'ABANDONED' | 'EXPIRED'

export interface InterviewConfig {
  category: InterviewCategory
  type?: InterviewType
  difficulty: Difficulty
  personality: string
  programmingLanguage?: string
  targetRole?: string
  targetCompany?: string
  durationMinutes: number
}

export interface CandidateQuestion {
  id: string
  title: string
  description: string
  category: string
  difficulty: string
  topicTags?: string[]
  examples?: unknown
  constraintsText?: string
  slug?: string
  timeComplexity?: string
  spaceComplexity?: string
  createdAt?: string
}

export interface InterviewSession {
  id: string
  userId: string
  status: SessionStatus
  type: string
  config: string
  startedAt?: string
  endedAt?: string
  durationSecs?: number
  createdAt?: string
}

// ── Session API DTOs ──

export type StartSessionRequest = InterviewConfig

export interface StartSessionResponse {
  sessionId: string
  wsUrl: string
}

export interface SessionSummaryDto {
  id: string
  status: SessionStatus
  type: string
  category: string
  difficulty: string
  createdAt?: string
  endedAt?: string
  durationSecs?: number
  overallScore?: number
}

export interface SessionDetailDto {
  id: string
  status: SessionStatus
  type: string
  category: string
  difficulty: string
  personality: string
  programmingLanguage?: string
  targetRole?: string
  targetCompany?: string
  durationMinutes: number
  createdAt?: string
  startedAt?: string
  endedAt?: string
  durationSecs?: number
  questions: CandidateQuestion[]
  overallScore?: number
}

// ── Evaluation Report ──

export interface ScoresDto {
  problemSolving: number
  algorithmChoice: number
  codeQuality: number
  communication: number
  efficiency: number
  testing: number
  overall: number
}

export interface ReportDto {
  reportId: string
  sessionId: string
  overallScore: number
  scores: ScoresDto
  strengths: string[]
  weaknesses: string[]
  suggestions: string[]
  narrativeSummary: string
  dimensionFeedback: Record<string, string>
  hintsUsed: number
  category: string
  difficulty: string
  questionTitle: string
  programmingLanguage?: string
  durationSeconds?: number
  completedAt: string
}

export interface ReportSummaryDto {
  reportId: string
  sessionId: string
  overallScore: number
  category: string
  difficulty: string
  completedAt: string
}

// ── Stats ──

export interface UserStatsDto {
  totalInterviews: number
  completedInterviews: number
  averageScore: number
  bestScore: number
  interviewsThisMonth: number
  freeInterviewsRemaining: number
  scoreByCategory: Record<string, number>
  scoreByDifficulty: Record<string, number>
}

// ── Pagination ──

export interface PagedResponse<T> {
  content: T[]
  page: number
  size: number
  total: number
}

// ── WebSocket Inbound (client → server) ──

export type WsInboundType =
  | 'CANDIDATE_MESSAGE'
  | 'CODE_RUN'
  | 'CODE_SUBMIT'
  | 'CODE_UPDATE'
  | 'REQUEST_HINT'
  | 'END_INTERVIEW'
  | 'PING'

export interface WsInboundMessage {
  type: WsInboundType
  [key: string]: unknown
}

// ── WebSocket Outbound (server → client) ──

export type WsOutboundType =
  | 'INTERVIEW_STARTED'
  | 'AI_MESSAGE'
  | 'AI_CHUNK'
  | 'STATE_CHANGE'
  | 'CODE_RUN_RESULT'
  | 'CODE_RESULT'
  | 'HINT_RESPONSE'
  | 'HINT_DELIVERED'
  | 'QUESTION_TRANSITION'
  | 'INTERVIEW_ENDED'
  | 'SESSION_END'
  | 'STATE_SYNC'
  | 'ERROR'
  | 'PONG'

export interface WsOutboundMessage {
  type: WsOutboundType
  [key: string]: unknown
}

export interface InterviewStartedMessage extends WsOutboundMessage {
  type: 'INTERVIEW_STARTED'
  sessionId: string
  state: string
}

export interface AiMessageMessage extends WsOutboundMessage {
  type: 'AI_MESSAGE'
  text: string
  state: string
}

export interface AiChunkMessage extends WsOutboundMessage {
  type: 'AI_CHUNK'
  delta: string
  done: boolean
}

export interface StateChangeMessage extends WsOutboundMessage {
  type: 'STATE_CHANGE'
  state: string
}

export interface CodeRunResultMessage extends WsOutboundMessage {
  type: 'CODE_RUN_RESULT'
  stdout: string | null
  stderr: string | null
  exitCode: number | null
}

export interface CodeResultMessage extends WsOutboundMessage {
  type: 'CODE_RESULT'
  status: string
  stdout: string | null
  stderr: string | null
  runtimeMs: number | null
  testResults: TestResult[] | null
}

export interface HintDeliveredMessage extends WsOutboundMessage {
  type: 'HINT_DELIVERED'
  hint: string
  level: number
  hintsRemaining: number
  refused: boolean
}

export interface QuestionTransitionMessage extends WsOutboundMessage {
  type: 'QUESTION_TRANSITION'
  questionIndex: number
  questionTitle: string
  questionDescription: string
  codeTemplates?: Record<string, string>
}

export interface SessionEndMessage extends WsOutboundMessage {
  type: 'SESSION_END'
  reportId: string
}

export interface StateSyncMessage extends WsOutboundMessage {
  type: 'STATE_SYNC'
  state: string
  currentQuestionIndex: number
  totalQuestions: number
  currentQuestion: { title: string; description: string; codeTemplates?: Record<string, string> } | null
  currentCode: string | null
  programmingLanguage: string | null
  hintsGiven: number
  messages: { role: string; content: string }[]
  showCodeEditor: boolean
}

export interface WsErrorMessage extends WsOutboundMessage {
  type: 'ERROR'
  code: string
  message: string
}

// ── Code Execution ──

export interface TestResult {
  passed: boolean
  input: string | null
  expected: string | null
  actual: string | null
  runtimeMs: number | null
}

// ── Code Snapshot (sent with every CANDIDATE_MESSAGE) ──

export interface CodeSnapshot {
  content: string | null
  language: string | null
  hasMeaningfulCode: boolean
  lineCount: number
  hasRunResults: boolean
  lastRunPassed: boolean | null
}
