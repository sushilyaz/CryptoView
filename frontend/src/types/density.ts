import type { Exchange, MarketType, Side } from './enums'

export interface DensityItem {
  symbol: string
  exchange: Exchange
  marketType: MarketType
  side: Side
  price: number
  quantity: number
  volumeUsd: number
  distancePercent: number
  lastPrice: number
  firstSeenAt: string   // ISO-8601
  lastSeenAt: string    // ISO-8601
  durationSeconds: number
  comment?: string
  volume15min?: number
}

// Группировка по базовому тикеру для отображения карточки
export interface SymbolGroup {
  symbol: string           // базовый тикер (ETH, BTC)
  rawSymbols: string[]     // оригинальные символы (ETHUSDT, ETH)
  densities: DensityItem[]
  isFavorite: boolean
  comment?: string
}

// Сообщение из WebSocket
export interface WsDensitiesMessage {
  type: 'densities'
  timestamp: string
  count: number
  data: DensityItem[]
}
