// Форматирование объёма в USD: 1 500 000 -> $1.5M, 500 000 -> $500K
export function formatVolumeUsd(value: number): string {
  if (value >= 1_000_000) {
    return `$${(value / 1_000_000).toFixed(2)}M`
  }
  if (value >= 1_000) {
    return `$${(value / 1_000).toFixed(0)}K`
  }
  return `$${value.toFixed(0)}`
}

// Форматирование цены
export function formatPrice(value: number): string {
  if (value >= 1000) return value.toLocaleString('en-US', { maximumFractionDigits: 2 })
  if (value >= 1) return value.toFixed(4)
  return value.toFixed(6)
}

// Форматирование расстояния до спреда
export function formatDistance(value: number): string {
  return `${value.toFixed(2)}%`
}

// Форматирование времени жизни: 300 сек -> "5m", 3600 -> "1h 0m"
export function formatDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  if (hours > 0) {
    const remainMinutes = minutes % 60
    return remainMinutes > 0 ? `${hours}h ${remainMinutes}m` : `${hours}h`
  }
  return `${minutes}m`
}

// Извлечь базовый тикер из символа: ETHUSDT → ETH, BTCUSDC → BTC
export function getBaseTicker(symbol: string): string {
  if (symbol.endsWith('USDT')) return symbol.slice(0, -4)
  if (symbol.endsWith('USDC')) return symbol.slice(0, -4)
  if (symbol.endsWith('USD')) return symbol.slice(0, -3)
  return symbol // Hyperliquid уже отправляет BTC, ETH
}

// Является ли плотность "новой" (firstSeenAt в пределах newBadgeMinutes минут)
export function isNew(firstSeenAt: string, newBadgeMinutes: number): boolean {
  const firstSeen = new Date(firstSeenAt).getTime()
  const cutoff = Date.now() - newBadgeMinutes * 60 * 1000
  return firstSeen > cutoff
}
