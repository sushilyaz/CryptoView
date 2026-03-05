import { create } from 'zustand'
import type { DensityItem } from '../types/density'

interface DensityState {
  densities: DensityItem[]
  displayedDensities: DensityItem[]
  connected: boolean
  lastUpdate: string | null
  isPaused: boolean

  setDensities: (items: DensityItem[], timestamp: string) => void
  setConnected: (connected: boolean) => void
  setPaused: (paused: boolean) => void
  removeSymbols: (rawSymbols: string[]) => void
}

export const useDensityStore = create<DensityState>((set, get) => ({
  densities: [],
  displayedDensities: [],
  connected: false,
  lastUpdate: null,
  isPaused: false,

  setDensities: (items, timestamp) => {
    const { isPaused } = get()
    if (isPaused) {
      set({ densities: items, lastUpdate: timestamp })
    } else {
      set({ densities: items, displayedDensities: items, lastUpdate: timestamp })
    }
  },

  setConnected: (connected) => {
    set({ connected })
  },

  setPaused: (paused) => {
    if (!paused) {
      // Unpause: show latest data immediately
      const { densities } = get()
      set({ isPaused: false, displayedDensities: densities })
    } else {
      set({ isPaused: true })
    }
  },

  removeSymbols: (rawSymbols) => {
    const symbolSet = new Set(rawSymbols.map(s => s.toUpperCase()))
    const { densities, displayedDensities } = get()
    set({
      densities: densities.filter(d => !symbolSet.has(d.symbol.toUpperCase())),
      displayedDensities: displayedDensities.filter(d => !symbolSet.has(d.symbol.toUpperCase())),
    })
  },
}))
