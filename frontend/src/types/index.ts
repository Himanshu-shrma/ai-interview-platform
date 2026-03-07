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
export type SessionStatus = 'CREATED' | 'IN_PROGRESS' | 'EVALUATING' | 'COMPLETED' | 'ABANDONED'

export interface InterviewConfig {
  category: InterviewCategory
  difficulty: Difficulty
  language: string
  questionCount: number
}

export interface CandidateQuestion {
  id: string
  title: string
  description: string
  examples: string[]
  constraints: string[]
  difficulty: Difficulty
  category: InterviewCategory
  starterCode?: string
}

export interface InterviewSession {
  id: string
  userId: string
  status: SessionStatus
  config: InterviewConfig
  currentQuestionIndex: number
  totalQuestions: number
  createdAt: string
  completedAt?: string
}

// ── Session API DTOs ──

export interface StartSessionRequest {
  category: InterviewCategory
  difficulty: Difficulty
  language: string
  questionCount: number
}

export interface StartSessionResponse {
  sessionId: string
  wsUrl: string
}

export interface SessionSummaryDto {
  id: string
  category: InterviewCategory
  difficulty: Difficulty
  status: SessionStatus
  questionCount: number
  createdAt: string
  completedAt?: string
  overallScore?: number
}

export interface SessionDetailDto {
  id: string
  category: InterviewCategory
  difficulty: Difficulty
  language: string
  status: SessionStatus
  currentQuestionIndex: number
  totalQuestions: number
  createdAt: string
  completedAt?: string
}

// ── Evaluation Report ──

export interface DimensionFeedback {
  dimension: string
  score: number
  maxScore: number
  feedback: string
}

export interface EvaluationReport {
  id: string
  sessionId: string
  overallScore: number
  summary: string
  dimensionFeedback: DimensionFeedback[]
  strengths: string[]
  improvements: string[]
  hintsUsed: number
  completedAt: string
}

// ── WebSocket Messages ──

export type WsInboundType =
  | 'START_INTERVIEW'
  | 'CANDIDATE_MESSAGE'
  | 'CODE_SUBMIT'
  | 'HINT_REQUEST'
  | 'NEXT_QUESTION'
  | 'END_INTERVIEW'
  | 'SESSION_PING'

export type WsOutboundType =
  | 'INTERVIEWER_MESSAGE'
  | 'QUESTION'
  | 'CODE_RESULT'
  | 'HINT'
  | 'STATE_CHANGE'
  | 'SESSION_END'
  | 'ERROR'
  | 'SESSION_PONG'

export interface WsInboundMessage {
  type: WsInboundType
  [key: string]: unknown
}

export interface WsOutboundMessage {
  type: WsOutboundType
  [key: string]: unknown
}

export interface InterviewerMessage extends WsOutboundMessage {
  type: 'INTERVIEWER_MESSAGE'
  content: string
}

export interface QuestionMessage extends WsOutboundMessage {
  type: 'QUESTION'
  question: CandidateQuestion
  questionIndex: number
  totalQuestions: number
}

export interface CodeResultMessage extends WsOutboundMessage {
  type: 'CODE_RESULT'
  results: TestResult[]
  allPassed: boolean
}

export interface HintMessage extends WsOutboundMessage {
  type: 'HINT'
  content: string
  hintsUsed: number
  maxHints: number
}

export interface StateChangeMessage extends WsOutboundMessage {
  type: 'STATE_CHANGE'
  state: string
}

export interface SessionEndMessage extends WsOutboundMessage {
  type: 'SESSION_END'
  reportId: string
}

// ── Code Execution ──

export interface TestResult {
  input: string
  expectedOutput: string
  actualOutput: string
  passed: boolean
  time: string
  memory: number
  stderr?: string
}

// ── Stats ──

export interface UserStats {
  totalInterviews: number
  completedInterviews: number
  averageScore: number
  interviewsByCategory: Record<InterviewCategory, number>
}

// ── Pagination ──

export interface PaginatedResponse<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
