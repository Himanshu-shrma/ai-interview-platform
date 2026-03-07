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
