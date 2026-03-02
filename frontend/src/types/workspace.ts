import type { Exchange, MarketType, DensitySortType } from './enums'

export interface ExchangeMarketKey {
  exchange: Exchange
  marketType: MarketType
}

export interface Workspace {
  id: string
  name: string
  enabledMarkets: ExchangeMarketKey[]           // пусто = все
  minDensityOverrides: Record<string, number>   // "BINANCE_SPOT" -> 600000
  symbolMinDensityOverrides: Record<string, number>  // "BTCUSDT" -> 500000
  blacklistedSymbols: string[]
  favoritedSymbols: string[]
  symbolComments: Record<string, string>
  sortType: DensitySortType
  newBadgeDurationMinutes: number               // сколько минут показывать NEW
  maxDistancePercent: number
}

export interface WorkspaceRequest {
  name: string
  enabledMarkets: ExchangeMarketKey[]
  minDensityOverrides: Record<string, number>
  symbolMinDensityOverrides: Record<string, number>
  blacklistedSymbols: string[]
  favoritedSymbols: string[]
  symbolComments: Record<string, string>
  sortType: DensitySortType
  newBadgeDurationMinutes: number
  maxDistancePercent: number
}
