import { appState } from './stores.svelte'
import type { GameEvent } from './types'

export type EventCallback = (event: GameEvent) => void

class EventStreamService {
  private socket: WebSocket | null = null
  private listeners: Set<EventCallback> = new Set()
  private reconnectTimer: number | null = null
  private isExplicitlyClosed = false
  private wantsTicks = false

  connect() {
    this.isExplicitlyClosed = false
    const token = appState.token
    if (!token) {
      appState.wsStatus = 'disconnected'
      return
    }

    if (this.socket && (this.socket.readyState === WebSocket.OPEN || this.socket.readyState === WebSocket.CONNECTING)) {
      return
    }

    appState.wsStatus = 'connecting'

    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const host = window.location.host
    const wsUrl = `${protocol}//${host}/v1/events?token=${encodeURIComponent(token)}`

    try {
      this.socket = new WebSocket(wsUrl)

      this.socket.onopen = () => {
        appState.wsStatus = 'connected'
        // Subscribe to '*' (all game events - tick excluded by backend)
        const topics = this.wantsTicks ? ['*', 'tick'] : ['*']
        this.socket?.send(JSON.stringify({
          action: 'subscribe',
          topics
        }))
      }

      this.socket.onmessage = (event) => {
        try {
          const parsed = JSON.parse(event.data)
          if (parsed.topic === 'tick' && parsed.data?.tick) {
            appState.tick = parsed.data.tick
          }

          const gameEvent: GameEvent = {
            id: Math.random().toString(36).substring(2, 9),
            timestamp: parsed.timestamp || Date.now(),
            topic: parsed.topic || 'unknown',
            data: parsed.data || {}
          }

          this.listeners.forEach((callback) => callback(gameEvent))
        } catch {
          // ignore unparsable messages
        }
      }

      this.socket.onclose = () => {
        appState.wsStatus = 'disconnected'
        this.socket = null
        if (!this.isExplicitlyClosed && appState.token) {
          this.scheduleReconnect()
        }
      }

      this.socket.onerror = () => {
        appState.wsStatus = 'disconnected'
      }
    } catch {
      appState.wsStatus = 'disconnected'
      this.scheduleReconnect()
    }
  }

  setSubscribeTicks(includeTicks: boolean) {
    this.wantsTicks = includeTicks
    if (!this.socket || this.socket.readyState !== WebSocket.OPEN) return
    if (includeTicks) {
      this.socket.send(JSON.stringify({
        action: 'subscribe',
        topics: ['tick']
      }))
    } else {
      this.socket.send(JSON.stringify({
        action: 'unsubscribe',
        topics: ['tick']
      }))
    }
  }

  private scheduleReconnect() {
    if (this.reconnectTimer !== null) return
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      if (!this.isExplicitlyClosed && appState.token) {
        this.connect()
      }
    }, 3000)
  }

  disconnect() {
    this.isExplicitlyClosed = true
    if (this.reconnectTimer !== null) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    if (this.socket) {
      this.socket.close()
      this.socket = null
    }
    appState.wsStatus = 'disconnected'
  }

  subscribe(callback: EventCallback): () => void {
    this.listeners.add(callback)
    if (!this.socket && appState.token) {
      this.connect()
    }
    return () => {
      this.listeners.delete(callback)
    }
  }
}

export const eventStream = new EventStreamService()
