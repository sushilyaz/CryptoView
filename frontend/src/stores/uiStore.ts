import { create } from 'zustand'

interface UiState {
  settingsOpen: boolean
  blacklistOpen: boolean
  symbolSettingsSymbol: string | null  // null = закрыто

  openSettings: () => void
  closeSettings: () => void
  openBlacklist: () => void
  closeBlacklist: () => void
  openSymbolSettings: (symbol: string) => void
  closeSymbolSettings: () => void
}

export const useUiStore = create<UiState>((set) => ({
  settingsOpen: false,
  blacklistOpen: false,
  symbolSettingsSymbol: null,

  openSettings: () => set({ settingsOpen: true }),
  closeSettings: () => set({ settingsOpen: false }),
  openBlacklist: () => set({ blacklistOpen: true }),
  closeBlacklist: () => set({ blacklistOpen: false }),
  openSymbolSettings: (symbol) => set({ symbolSettingsSymbol: symbol }),
  closeSymbolSettings: () => set({ symbolSettingsSymbol: null }),
}))
