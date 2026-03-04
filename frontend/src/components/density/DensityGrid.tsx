import { useMemo } from 'react'
import { useDensityStore } from '../../stores/densityStore'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { SymbolCard } from './SymbolCard'
import type { SymbolGroup } from '../../types/density'
import { getBaseTicker } from '../../utils/formatters'

export function DensityGrid() {
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const connected = useDensityStore(s => s.connected)
  const displayedDensities = useDensityStore(s => s.displayedDensities)
  const isPaused = useDensityStore(s => s.isPaused)
  const setPaused = useDensityStore(s => s.setPaused)

  const favoritedSymbols = activeWorkspace?.favoritedSymbols ?? []
  const symbolComments = activeWorkspace?.symbolComments ?? {}

  const groups = useMemo(() => {
    // Группируем по базовому тикеру (ETH, BTC)
    const map = new Map<string, typeof displayedDensities>()
    const rawSymbolsMap = new Map<string, Set<string>>()

    for (const d of displayedDensities) {
      const ticker = getBaseTicker(d.symbol)
      const existing = map.get(ticker) ?? []
      existing.push(d)
      map.set(ticker, existing)

      const rawSet = rawSymbolsMap.get(ticker) ?? new Set()
      rawSet.add(d.symbol)
      rawSymbolsMap.set(ticker, rawSet)
    }

    const result: SymbolGroup[] = []
    for (const [ticker, items] of map) {
      result.push({
        symbol: ticker,
        rawSymbols: [...(rawSymbolsMap.get(ticker) ?? [])],
        densities: items,
        isFavorite: favoritedSymbols.includes(ticker),
        comment: symbolComments[ticker],
      })
    }

    // Избранные — вперёд
    result.sort((a, b) => {
      if (a.isFavorite && !b.isFavorite) return -1
      if (!a.isFavorite && b.isFavorite) return 1
      return 0
    })

    return result
  }, [displayedDensities, favoritedSymbols, symbolComments])

  const newBadgeMinutes = activeWorkspace?.newBadgeDurationMinutes ?? 5

  if (!connected) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        <div className="text-center">
          <div className="text-4xl mb-3">&#x231B;</div>
          <div>Подключение к серверу...</div>
        </div>
      </div>
    )
  }

  if (groups.length === 0) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        <div className="text-center">
          <div className="text-4xl mb-3">&#x1F4ED;</div>
          <div>Плотностей пока нет</div>
          <div className="text-sm mt-1">Ожидание данных с бирж...</div>
        </div>
      </div>
    )
  }

  return (
    <div
      className="relative"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      {isPaused && (
        <div className="absolute top-2 right-4 z-10 px-2 py-0.5 bg-yellow-500/20 text-yellow-400 text-[10px] font-bold uppercase rounded border border-yellow-500/30">
          Paused
        </div>
      )}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-3 p-4">
        {groups.map(group => (
          <SymbolCard
            key={group.symbol}
            group={group}
            newBadgeMinutes={newBadgeMinutes}
          />
        ))}
      </div>
    </div>
  )
}
