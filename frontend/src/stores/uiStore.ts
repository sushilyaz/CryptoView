import { create } from 'zustand'
import type { DensitySortType } from '../types/enums'

interface UiState {
  settingsOpen: boolean
  blacklistOpen: boolean
  symbolSettingsSymbol: string | null  // null = закрыто, базовый тикер (ETH)
  symbolSettingsRawSymbols: string[]   // оригинальные символы (ETHUSDT, ETH)
  innerSortType: DensitySortType       // сортировка внутри карточки

  openSettings: () => void
  closeSettings: () => void
  openBlacklist: () => void
  closeBlacklist: () => void
  openSymbolSettings: (symbol: string, rawSymbols: string[]) => void
  closeSymbolSettings: () => void
  setInnerSortType: (sort: DensitySortType) => void
}

export const useUiStore = create<UiState>((set) => ({
  settingsOpen: false,
  blacklistOpen: false,
  symbolSettingsSymbol: null,
  symbolSettingsRawSymbols: [],
  innerSortType: 'DISTANCE_ASC',

  openSettings: () => set({ settingsOpen: true }),
  closeSettings: () => set({ settingsOpen: false }),
  openBlacklist: () => set({ blacklistOpen: true }),
  closeBlacklist: () => set({ blacklistOpen: false }),
  openSymbolSettings: (symbol, rawSymbols) => set({ symbolSettingsSymbol: symbol, symbolSettingsRawSymbols: rawSymbols }),
  closeSymbolSettings: () => set({ symbolSettingsSymbol: null, symbolSettingsRawSymbols: [] }),
  setInnerSortType: (sort) => set({ innerSortType: sort }),
}))
