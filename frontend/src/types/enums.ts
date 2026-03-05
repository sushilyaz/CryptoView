export type Exchange =
  | 'BINANCE'
  | 'BYBIT'
  | 'OKX'
  | 'BITGET'
  | 'GATE'
  | 'MEXC'
  | 'HYPERLIQUID'
  | 'LIGHTER'
  | 'ASTER'

export type MarketType = 'SPOT' | 'FUTURES'

export type Side = 'BID' | 'ASK'

export type DensitySortType =
  | 'DURATION_DESC'
  | 'SIZE_USD_DESC'
  | 'DISTANCE_ASC'
