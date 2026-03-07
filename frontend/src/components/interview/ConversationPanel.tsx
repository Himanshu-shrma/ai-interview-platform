import { useEffect, useRef, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Send } from 'lucide-react'
import { ThinkingIndicator } from './ThinkingIndicator'
import { cn } from '@/lib/utils'
import type { ConversationMessage } from '@/hooks/useConversation'
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from '@/components/ui/tooltip'

interface ConversationPanelProps {
  messages: ConversationMessage[]
  isAiThinking: boolean
  hintsGiven: number
  onSendMessage: (content: string) => void
  onRequestHint: () => void
  disabled: boolean
}

export function ConversationPanel({
  messages,
  isAiThinking,
  hintsGiven,
  onSendMessage,
  onRequestHint,
  disabled,
}: ConversationPanelProps) {
  const [input, setInput] = useState('')
  const scrollRef = useRef<HTMLDivElement>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)

  // Auto-scroll to bottom on new messages
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight
    }
  }, [messages, isAiThinking])

  function handleSend() {
    const text = input.trim()
    if (!text || disabled) return
    onSendMessage(text)
    setInput('')
    if (textareaRef.current) {
      textareaRef.current.style.height = 'auto'
    }
  }

  function handleKeyDown(e: React.KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      handleSend()
    }
  }

  function handleInput(e: React.ChangeEvent<HTMLTextAreaElement>) {
    setInput(e.target.value)
    // Auto-resize textarea
    const el = e.target
    el.style.height = 'auto'
    el.style.height = `${Math.min(el.scrollHeight, 120)}px`
  }

  const hintDots = Array.from({ length: 3 }, (_, i) => i < hintsGiven)

  return (
    <div className="flex flex-col h-full">
      {/* Messages */}
      <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-3">
        {messages.map((msg) => (
          <div
            key={msg.id}
            className={cn(
              'max-w-[85%] rounded-lg px-4 py-2.5 text-sm whitespace-pre-wrap',
              msg.role === 'AI'
                ? 'self-start bg-muted text-foreground'
                : 'self-end ml-auto bg-primary text-primary-foreground'
            )}
          >
            {msg.content}
            {msg.isStreaming && (
              <span className="inline-block w-1.5 h-4 ml-0.5 bg-foreground/70 animate-pulse" />
            )}
          </div>
        ))}
        {isAiThinking && <ThinkingIndicator />}
      </div>

      {/* Input area */}
      <div className="border-t p-3 space-y-2">
        <div className="flex gap-2">
          <textarea
            ref={textareaRef}
            className="flex-1 resize-none rounded-md border border-input bg-transparent px-3 py-2 text-sm ring-offset-background placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50"
            placeholder={disabled ? 'Interview ended' : 'Type your response...'}
            value={input}
            onChange={handleInput}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            rows={1}
          />
          <Button
            size="icon"
            onClick={handleSend}
            disabled={!input.trim() || disabled}
          >
            <Send className="h-4 w-4" />
          </Button>
        </div>

        <div className="flex items-center justify-between">
          <TooltipProvider>
            <Tooltip>
              <TooltipTrigger asChild>
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={onRequestHint}
                  disabled={hintsGiven >= 3 || disabled}
                  className="text-xs"
                >
                  Request Hint
                  <span className="ml-1.5 flex items-center gap-0.5">
                    {hintDots.map((used, i) => (
                      <span
                        key={i}
                        className={cn(
                          'inline-block h-1.5 w-1.5 rounded-full',
                          used ? 'bg-yellow-500' : 'bg-muted-foreground/30'
                        )}
                      />
                    ))}
                  </span>
                </Button>
              </TooltipTrigger>
              <TooltipContent>
                <p>Hints deduct from your problem solving score</p>
              </TooltipContent>
            </Tooltip>
          </TooltipProvider>
          <span className="text-xs text-muted-foreground">
            Shift+Enter for new line
          </span>
        </div>
      </div>
    </div>
  )
}
