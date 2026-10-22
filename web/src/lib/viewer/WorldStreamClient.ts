import { appState } from '../stores.svelte'
import {
  PacketType, PROTOCOL_VERSION, parseEntities, parseHello, parsePose, parseUnload,
  type EntityState, type HelloPacket, type PosePacket
} from './protocol'

export type WsStatus = 'connected' | 'connecting' | 'disconnected'

export interface StreamHandlers {
  onStatus(status: WsStatus): void
  onHello(hello: HelloPacket): void
  /** Raw CHUNK frame; ownership passes to the handler (it is transferred to the mesher). */
  onChunk(buffer: ArrayBuffer): void
  onUnload(cx: number, cz: number): void
  onPose(pose: PosePacket): void
  onEntities(entities: EntityState[]): void
}

export interface ViewRequest {
  radius: number
  follow: boolean
  cx?: number
  cz?: number
  entities: boolean
}

/** WebSocket client for /v1/world/stream with reconnect backoff. Decodes small frames; forwards chunks raw. */
export class WorldStreamClient {
  private ws: WebSocket | null = null
  private closed = false
  private retry = 0
  private retryTimer: number | null = null
  private view: ViewRequest
  private handlers: StreamHandlers

  bytesReceived = 0
  chunksReceived = 0
  status: WsStatus = 'disconnected'

  constructor(handlers: StreamHandlers, initial: ViewRequest) {
    this.handlers = handlers
    this.view = initial
  }

  connect() {
    this.closed = false
    if (this.ws || !appState.token) return
    const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
    const url = `${protocol}//${window.location.host}/v1/world/stream?token=${encodeURIComponent(appState.token)}&radius=${this.view.radius}`
    this.setStatus('connecting')
    const ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    ws.onopen = () => {
      this.retry = 0
      this.setStatus('connected')
      this.sendView(this.view)
    }
    ws.onmessage = e => {
      if (e.data instanceof ArrayBuffer) this.handle(e.data)
    }
    ws.onclose = () => {
      this.ws = null
      this.setStatus('disconnected')
      if (!this.closed) this.scheduleReconnect()
    }
    ws.onerror = () => ws.close()
    this.ws = ws
  }

  disconnect() {
    this.closed = true
    if (this.retryTimer !== null) clearTimeout(this.retryTimer)
    this.retryTimer = null
    this.ws?.close()
    this.ws = null
  }

  sendView(view: ViewRequest) {
    this.view = view
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ type: 'view', ...view }))
    }
  }

  private scheduleReconnect() {
    const delay = Math.min(10_000, 500 * 2 ** this.retry++)
    this.retryTimer = window.setTimeout(() => {
      this.retryTimer = null
      this.connect()
    }, delay)
  }

  private setStatus(status: WsStatus) {
    this.status = status
    this.handlers.onStatus(status)
  }

  private handle(buffer: ArrayBuffer) {
    if (buffer.byteLength < 1) return
    this.bytesReceived += buffer.byteLength
    const view = new DataView(buffer)
    switch (view.getUint8(0)) {
      case PacketType.Hello: {
        const hello = parseHello(view)
        if (hello.version !== PROTOCOL_VERSION) {
          console.warn(`World stream protocol ${hello.version}, viewer expects ${PROTOCOL_VERSION}`)
        }
        this.handlers.onHello(hello)
        break
      }
      case PacketType.Chunk:
        this.chunksReceived++
        this.handlers.onChunk(buffer)
        break
      case PacketType.Unload: {
        const { cx, cz } = parseUnload(view)
        this.handlers.onUnload(cx, cz)
        break
      }
      case PacketType.Pose:
        this.handlers.onPose(parsePose(view))
        break
      case PacketType.Entities:
        this.handlers.onEntities(parseEntities(view))
        break
    }
  }
}
