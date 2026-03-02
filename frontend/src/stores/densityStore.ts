import { create } from 'zustand'
import type { DensityItem, SymbolGroup } from '../types/density'

interface DensityState {
  densities: DensityItem[]
  connected: boolean
  lastUpdate: string | null

  setDensities: (items: DensityItem[], timestamp: string) => void
  setConnected: (connected: boolean) => void

  // Группировка плотностей по символу
  getSymbolGroups: (favoritedSymbols: string[], symbolComments: Record<string, string>) => SymbolGroup[]
}

export const useDensityStore = create<DensityState>((set, get) => ({
  densities: [],
  connected: false,
  lastUpdate: null,

  setDensities: (items, timestamp) => {
    set({ densities: items, lastUpdate: timestamp })
  },

  setConnected: (connected) => {
    set({ connected })
  },

  getSymbolGroups: (favoritedSymbols, symbolComments) => {
    const { densities } = get()

    // Группируем по символу
    const map = new Map<string, DensityItem[]>()
    for (const d of densities) {
      const existing = map.get(d.symbol) ?? []
      existing.push(d)
      map.set(d.symbol, existing)
    }

    const groups: SymbolGroup[] = []
    for (const [symbol, items] of map) {
      groups.push({
        symbol,
        densities: items,
        isFavorite: favoritedSymbols.includes(symbol),
        comment: symbolComments[symbol],
      })
    }

    // Избранные — вперёд
    groups.sort((a, b) => {
      if (a.isFavorite && !b.isFavorite) return -1
      if (!a.isFavorite && b.isFavorite) return 1
      return 0
    })

    return groups
  },
}))
