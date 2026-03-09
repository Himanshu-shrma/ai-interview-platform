import { useCallback, useRef, useState } from 'react'

export interface ConversationMessage {
  id: string
  role: 'AI' | 'CANDIDATE'
  content: string
  isStreaming: boolean
  timestamp: Date
}

let msgIdCounter = 0
function nextId() {
  return `msg-${++msgIdCounter}-${Date.now()}`
}

export function useConversation() {
  const [messages, setMessages] = useState<ConversationMessage[]>([])
  const streamingIdRef = useRef<string | null>(null)

  const addCandidateMessage = useCallback((content: string) => {
    setMessages((prev) => [
      ...prev,
      { id: nextId(), role: 'CANDIDATE', content, isStreaming: false, timestamp: new Date() },
    ])
  }, [])

  const startAiMessage = useCallback(() => {
    const id = nextId()
    streamingIdRef.current = id
    setMessages((prev) => [
      ...prev,
      { id, role: 'AI', content: '', isStreaming: true, timestamp: new Date() },
    ])
    return id
  }, [])

  const appendAiToken = useCallback((token: string) => {
    setMessages((prev) => {
      const last = prev[prev.length - 1]
      // If no streaming message exists, create one
      if (!last || last.role !== 'AI' || !last.isStreaming) {
        const id = nextId()
        streamingIdRef.current = id
        return [
          ...prev,
          { id, role: 'AI', content: token, isStreaming: true, timestamp: new Date() },
        ]
      }
      // Append to existing streaming message
      return prev.map((m) =>
        m.id === last.id ? { ...m, content: m.content + token } : m
      )
    })
  }, [])

  const finalizeAiMessage = useCallback(() => {
    streamingIdRef.current = null
    setMessages((prev) =>
      prev.map((m) => (m.isStreaming ? { ...m, isStreaming: false } : m))
    )
  }, [])

  const addAiMessage = useCallback((text: string) => {
    setMessages((prev) => [
      ...prev,
      { id: nextId(), role: 'AI', content: text, isStreaming: false, timestamp: new Date() },
    ])
  }, [])

  /** Replace all messages at once — used for session recovery after reconnect. */
  const replaceAll = useCallback((msgs: ConversationMessage[]) => {
    setMessages(msgs)
  }, [])

  return { messages, addCandidateMessage, startAiMessage, appendAiToken, finalizeAiMessage, addAiMessage, replaceAll }
}
