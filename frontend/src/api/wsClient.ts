import type { WsDensitiesMessage } from '../types/density'

type MessageHandler = (msg: WsDensitiesMessage) => void
type StatusHandler = (connected: boolean) => void

class DensityWebSocketClient {
  private ws: WebSocket | null = null
  private workspaceId: string | null = null
  private messageHandlers: Set<MessageHandler> = new Set()
  private statusHandlers: Set<StatusHandler> = new Set()
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null
  private reconnectDelay = 1000
  private shouldConnect = false

  connect(workspaceId?: string) {
    this.shouldConnect = true
    this.workspaceId = workspaceId ?? null
    this.doConnect()
  }

  disconnect() {
    this.shouldConnect = false
    if (this.reconnectTimer) {
      clearTimeout(this.reconnectTimer)
      this.reconnectTimer = null
    }
    this.ws?.close()
    this.ws = null
  }

  setWorkspace(workspaceId: string) {
    this.workspaceId = workspaceId
    if (this.ws?.readyState === WebSocket.OPEN) {
      this.ws.send(JSON.stringify({ action: 'setWorkspace', workspaceId }))
    }
  }

  onMessage(handler: MessageHandler) {
    this.messageHandlers.add(handler)
    return () => this.messageHandlers.delete(handler)
  }

  onStatus(handler: StatusHandler) {
    this.statusHandlers.add(handler)
    return () => this.statusHandlers.delete(handler)
  }

  private doConnect() {
    const params = this.workspaceId
      ? `?workspaceId=${this.workspaceId}`
      : ''

    // В dev-режиме подключаемся напрямую к бэкенду (порт 8080),
    // в production — к тому же хосту
    const host = window.location.port === '3000'
      ? `${window.location.hostname}:8080`
      : window.location.host
    const wsUrl = `ws://${host}/ws/densities${params}`

    this.ws = new WebSocket(wsUrl)

    this.ws.onopen = () => {
      this.reconnectDelay = 1000
      this.statusHandlers.forEach(h => h(true))
    }

    this.ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data) as WsDensitiesMessage
        if (msg.type === 'densities') {
          this.messageHandlers.forEach(h => h(msg))
        }
      } catch {
        // ignore malformed messages
      }
    }

    this.ws.onclose = () => {
      this.statusHandlers.forEach(h => h(false))
      if (this.shouldConnect) {
        this.reconnectTimer = setTimeout(() => {
          this.reconnectDelay = Math.min(this.reconnectDelay * 2, 30_000)
          this.doConnect()
        }, this.reconnectDelay)
      }
    }

    this.ws.onerror = () => {
      this.ws?.close()
    }
  }
}

// Синглтон — одно подключение на приложение
export const wsClient = new DensityWebSocketClient()
