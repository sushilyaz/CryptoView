import type { Exchange } from '../types/enums'

export const EXCHANGE_LABELS: Record<Exchange, string> = {
  BINANCE: 'Binance',
  BYBIT: 'Bybit',
  OKX: 'OKX',
  BITGET: 'Bitget',
  GATE: 'Gate',
  MEXC: 'MEXC',
  HYPERLIQUID: 'Hyperliquid',
  LIGHTER: 'Lighter',
  ASTER: 'Aster',
}

// Цвета для бирж (используются в UI)
export const EXCHANGE_COLORS: Record<Exchange, string> = {
  BINANCE: '#F0B90B',
  BYBIT: '#F7A600',
  OKX: '#FFFFFF',
  BITGET: '#00CED1',
  GATE: '#1890FF',
  MEXC: '#00D4AA',
  HYPERLIQUID: '#8B5CF6',
  LIGHTER: '#6366F1',
  ASTER: '#EC4899',
}

// Биржи у которых есть Futures
export const EXCHANGES_WITH_FUTURES: Exchange[] = [
  'BINANCE',
  'BYBIT',
  'OKX',
  'BITGET',
  'GATE',
  'HYPERLIQUID',
  'LIGHTER',
  'ASTER',
]

// Биржи у которых есть Spot
export const EXCHANGES_WITH_SPOT: Exchange[] = [
  'BINANCE',
  'BYBIT',
  'OKX',
  'BITGET',
  'GATE',
  'MEXC',
  'ASTER',
]

// Отключённые рынки (недоступны для включения)
export const DISABLED_MARKETS = new Set([
  'GATE_SPOT',
  'GATE_FUTURES',
  'MEXC_FUTURES',
  'LIGHTER_SPOT',
])
