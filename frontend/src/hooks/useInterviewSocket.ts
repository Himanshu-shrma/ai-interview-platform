import { useCallback, useEffect, useRef, useState } from 'react'
import { useAuth } from '@clerk/clerk-react'
import type { WsInboundMessage, WsOutboundMessage } from '@/types'

const WS_BASE_URL = import.meta.env.VITE_WS_BASE_URL || 'ws://localhost:8080'
const MAX_RETRIES = 3
const BACKOFF_MS = [2000, 4000, 8000]
const HEARTBEAT_INTERVAL = 25_000

type ConnectionStatus = 'connecting' | 'connected' | 'disconnected' | 'error'

interface UseInterviewSocketOptions {
  sessionId: string
  onMessage: (msg: WsOutboundMessage) => void
  onStatusChange?: (status: ConnectionStatus) => void
}

export function useInterviewSocket({
  sessionId,
  onMessage,
  onStatusChange,
}: UseInterviewSocketOptions) {
  const { getToken } = useAuth()
  const wsRef = useRef<WebSocket | null>(null)
  const queueRef = useRef<WsInboundMessage[]>([])
  const [status, setStatus] = useState<ConnectionStatus>('disconnected')

  // Stable refs for callbacks — prevents effect re-runs when parent
  // re-renders with new callback references.
  const onMessageRef = useRef(onMessage)
  onMessageRef.current = onMessage
  const onStatusChangeRef = useRef(onStatusChange)
  onStatusChangeRef.current = onStatusChange
  const getTokenRef = useRef(getToken)
  getTokenRef.current = getToken

  // Single effect manages the entire WS lifecycle for a given sessionId.
  // Uses a LOCAL `cancelled` flag (not a ref) so each effect invocation
  // has its own closure — critical for React StrictMode double-mount.
  useEffect(() => {
    let cancelled = false
    let retryCount = 0
    let heartbeatId: ReturnType<typeof setInterval> | null = null
    let retryTimeoutId: ReturnType<typeof setTimeout> | null = null

    function updateSt(s: ConnectionStatus) {
      if (cancelled) return
      setStatus(s)
      onStatusChangeRef.current?.(s)
    }

    function clearHb() {
      if (heartbeatId) {
        clearInterval(heartbeatId)
        heartbeatId = null
      }
    }

    async function connect() {
      if (cancelled) return

      // If there's already an open socket from a previous connect() in this
      // same effect invocation, don't open another one.
      if (wsRef.current?.readyState === WebSocket.OPEN) return

      updateSt('connecting')

      const token = await getTokenRef.current()
      // Re-check after async gap — cleanup may have run while awaiting token
      if (!token || cancelled) {
        if (!cancelled) updateSt('error')
        return
      }

      const ws = new WebSocket(`${WS_BASE_URL}/ws/interview/${sessionId}?token=${token}`)
      wsRef.current = ws

      ws.onopen = () => {
        if (cancelled) { ws.close(); return }
        retryCount = 0
        updateSt('connected')

        // Flush queued messages
        while (queueRef.current.length > 0) {
          const queued = queueRef.current.shift()!
          ws.send(JSON.stringify(queued))
        }

        // Start heartbeat
        clearHb()
        heartbeatId = setInterval(() => {
          if (ws.readyState === WebSocket.OPEN) {
            ws.send(JSON.stringify({ type: 'PING' }))
          }
        }, HEARTBEAT_INTERVAL)
      }

      ws.onmessage = (event) => {
        try {
          const msg = JSON.parse(event.data) as WsOutboundMessage
          onMessageRef.current(msg)
        } catch {
          // ignore malformed messages
        }
      }

      ws.onclose = () => {
        clearHb()
        if (cancelled) return

        if (retryCount < MAX_RETRIES) {
          const delay = BACKOFF_MS[retryCount] ?? BACKOFF_MS[BACKOFF_MS.length - 1]
          retryCount++
          updateSt('connecting')
          retryTimeoutId = setTimeout(() => connect(), delay)
        } else {
          updateSt('disconnected')
        }
      }

      ws.onerror = () => {
        if (cancelled) return
        updateSt('error')
        ws.close()
      }
    }

    connect()

    return () => {
      cancelled = true
      clearHb()
      if (retryTimeoutId) clearTimeout(retryTimeoutId)
      if (wsRef.current) {
        wsRef.current.onclose = null // prevent retry from firing during cleanup
        wsRef.current.close()
        wsRef.current = null
      }
      setStatus('disconnected')
    }
  }, [sessionId]) // eslint-disable-line react-hooks/exhaustive-deps
  // sessionId is the only real dependency — callbacks accessed via refs

  const send = useCallback((msg: WsInboundMessage) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(msg))
    } else {
      queueRef.current.push(msg)
    }
  }, [])

  const disconnect = useCallback(() => {
    // Close the current connection; the effect cleanup handles the rest
    // when the component unmounts or sessionId changes.
    if (wsRef.current) {
      wsRef.current.onclose = null
      wsRef.current.close()
      wsRef.current = null
    }
    setStatus('disconnected')
  }, [])

  return { status, send, disconnect }
}
