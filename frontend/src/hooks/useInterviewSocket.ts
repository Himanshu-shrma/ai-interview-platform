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
  const retryCountRef = useRef(0)
  const heartbeatRef = useRef<ReturnType<typeof setInterval> | null>(null)
  const [status, setStatus] = useState<ConnectionStatus>('disconnected')

  const updateStatus = useCallback(
    (s: ConnectionStatus) => {
      setStatus(s)
      onStatusChange?.(s)
    },
    [onStatusChange]
  )

  const clearHeartbeat = useCallback(() => {
    if (heartbeatRef.current) {
      clearInterval(heartbeatRef.current)
      heartbeatRef.current = null
    }
  }, [])

  const connect = useCallback(async () => {
    if (wsRef.current?.readyState === WebSocket.OPEN) return

    updateStatus('connecting')

    const token = await getToken()
    if (!token) {
      updateStatus('error')
      return
    }

    const ws = new WebSocket(`${WS_BASE_URL}/ws/interview/${sessionId}?token=${token}`)
    wsRef.current = ws

    ws.onopen = () => {
      retryCountRef.current = 0
      updateStatus('connected')

      // Start heartbeat
      clearHeartbeat()
      heartbeatRef.current = setInterval(() => {
        if (ws.readyState === WebSocket.OPEN) {
          ws.send(JSON.stringify({ type: 'PING' }))
        }
      }, HEARTBEAT_INTERVAL)
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data) as WsOutboundMessage
        onMessage(msg)
      } catch {
        // ignore malformed messages
      }
    }

    ws.onclose = () => {
      clearHeartbeat()

      if (retryCountRef.current < MAX_RETRIES) {
        const delay = BACKOFF_MS[retryCountRef.current] ?? BACKOFF_MS[BACKOFF_MS.length - 1]
        retryCountRef.current++
        updateStatus('connecting')
        setTimeout(() => connect(), delay)
      } else {
        updateStatus('disconnected')
      }
    }

    ws.onerror = () => {
      updateStatus('error')
      ws.close()
    }
  }, [sessionId, getToken, onMessage, updateStatus, clearHeartbeat])

  const send = useCallback((msg: WsInboundMessage) => {
    if (wsRef.current?.readyState === WebSocket.OPEN) {
      wsRef.current.send(JSON.stringify(msg))
    }
  }, [])

  const disconnect = useCallback(() => {
    retryCountRef.current = MAX_RETRIES // prevent auto-reconnect
    clearHeartbeat()
    wsRef.current?.close()
    wsRef.current = null
    updateStatus('disconnected')
  }, [clearHeartbeat, updateStatus])

  useEffect(() => {
    connect()
    return () => disconnect()
  }, [connect, disconnect])

  return { status, send, disconnect }
}
