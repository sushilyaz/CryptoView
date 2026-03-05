import { useState, useEffect, useRef } from 'react'
import { Modal } from '../common/Modal'
import { Toggle } from '../common/Toggle'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useUiStore } from '../../stores/uiStore'
import type { Workspace } from '../../types/workspace'
import type { Exchange, MarketType, DensitySortType } from '../../types/enums'
import {
  EXCHANGE_LABELS,
  EXCHANGE_COLORS,
  EXCHANGES_WITH_SPOT,
  EXCHANGES_WITH_FUTURES,
  DISABLED_MARKETS,
} from '../../utils/constants'

const SORT_OPTIONS: { value: DensitySortType; label: string }[] = [
  { value: 'SIZE_USD_DESC', label: 'По объёму ↓' },
  { value: 'DURATION_DESC', label: 'По времени жизни ↓' },
  { value: 'DISTANCE_ASC', label: 'По расстоянию ↑' },
]

const ALL_EXCHANGES: Exchange[] = [...new Set([...EXCHANGES_WITH_SPOT, ...EXCHANGES_WITH_FUTURES])]

export function SettingsModal() {
  const settingsOpen = useUiStore(s => s.settingsOpen)
  const closeSettings = useUiStore(s => s.closeSettings)
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const update = useWorkspaceStore(s => s.update)
  const exportWorkspace = useWorkspaceStore(s => s.exportWorkspace)
  const importWorkspace = useWorkspaceStore(s => s.importWorkspace)

  const [draft, setDraft] = useState<Workspace | null>(null)
  const [saving, setSaving] = useState(false)
  const importRef = useRef<HTMLInputElement>(null)

  // При открытии — заполняем draft из activeWorkspace
  useEffect(() => {
    if (settingsOpen && activeWorkspace) {
      setDraft({ ...activeWorkspace })
    }
  }, [settingsOpen, activeWorkspace])

  if (!draft) return null

  const isMarketEnabled = (exchange: Exchange, marketType: MarketType): boolean => {
    if (draft.enabledMarkets.length === 0) return true
    return draft.enabledMarkets.some(m => m.exchange === exchange && m.marketType === marketType)
  }

  const toggleMarket = (exchange: Exchange, marketType: MarketType) => {
    const key = `${exchange}_${marketType}`
    if (DISABLED_MARKETS.has(key)) return

    const currentEnabled = draft.enabledMarkets.length === 0
      // Если список пустой — это "все", нужно явно заполнить все включённые, кроме этого
      ? getAllAvailableMarkets().filter(m => !(m.exchange === exchange && m.marketType === marketType))
      : isMarketEnabled(exchange, marketType)
        ? draft.enabledMarkets.filter(m => !(m.exchange === exchange && m.marketType === marketType))
        : [...draft.enabledMarkets, { exchange, marketType }]

    setDraft({ ...draft, enabledMarkets: currentEnabled })
  }

  const getAllAvailableMarkets = () => {
    const markets: { exchange: Exchange; marketType: MarketType }[] = []
    for (const ex of ALL_EXCHANGES) {
      if (EXCHANGES_WITH_SPOT.includes(ex)) markets.push({ exchange: ex, marketType: 'SPOT' })
      if (EXCHANGES_WITH_FUTURES.includes(ex)) markets.push({ exchange: ex, marketType: 'FUTURES' })
    }
    return markets.filter(m => !DISABLED_MARKETS.has(`${m.exchange}_${m.marketType}`))
  }

  const getMinDensity = (exchange: Exchange, marketType: MarketType): string => {
    const key = `${exchange}_${marketType}`
    return String(draft.minDensityOverrides[key] ?? '')
  }

  const setMinDensity = (exchange: Exchange, marketType: MarketType, value: string) => {
    const key = `${exchange}_${marketType}`
    const num = parseInt(value)
    const overrides = { ...draft.minDensityOverrides }
    if (value === '' || isNaN(num)) {
      delete overrides[key]
    } else {
      overrides[key] = num
    }
    setDraft({ ...draft, minDensityOverrides: overrides })
  }

  const handleSave = async () => {
    if (!activeWorkspace || !draft) return
    setSaving(true)
    try {
      await update(activeWorkspace.id, draft)
      closeSettings()
    } finally {
      setSaving(false)
    }
  }

  const handleExport = () => {
    if (activeWorkspace) exportWorkspace(activeWorkspace.id)
  }

  const handleImport = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (file) importWorkspace(file)
  }

  const removeSymbolOverride = (symbol: string) => {
    const overrides = { ...draft.symbolMinDensityOverrides }
    delete overrides[symbol]
    setDraft({ ...draft, symbolMinDensityOverrides: overrides })
  }

  return (
    <Modal open={settingsOpen} onClose={closeSettings} title="Настройки пространства" width="max-w-3xl">
      <div className="p-6 space-y-6">

        {/* Название */}
        <div>
          <label className="block text-sm text-gray-400 mb-1">Название</label>
          <input
            type="text"
            value={draft.name}
            onChange={e => setDraft({ ...draft, name: e.target.value })}
            className="w-full px-3 py-2 bg-[#0f1117] border border-[#2d3748] rounded-lg text-white text-sm focus:outline-none focus:border-blue-500"
          />
        </div>

        {/* Сортировка */}
        <div>
          <label className="block text-sm text-gray-400 mb-1">Сортировка</label>
          <select
            value={draft.sortType}
            onChange={e => setDraft({ ...draft, sortType: e.target.value as DensitySortType })}
            className="w-full px-3 py-2 bg-[#0f1117] border border-[#2d3748] rounded-lg text-white text-sm focus:outline-none focus:border-blue-500"
          >
            {SORT_OPTIONS.map(o => (
              <option key={o.value} value={o.value}>{o.label}</option>
            ))}
          </select>
        </div>

        {/* NEW бейдж таймер */}
        <div>
          <label className="block text-sm text-gray-400 mb-1">Время показа бейджа NEW, мин</label>
          <input
            type="number"
            value={draft.newBadgeDurationMinutes}
            onChange={e => setDraft({ ...draft, newBadgeDurationMinutes: parseInt(e.target.value) || 5 })}
            min={1}
            max={1440}
            className="w-48 px-3 py-2 bg-[#0f1117] border border-[#2d3748] rounded-lg text-white text-sm focus:outline-none focus:border-blue-500"
          />
        </div>

        {/* Биржи */}
        <div>
          <label className="block text-sm text-gray-400 mb-3">Биржи</label>
          <div className="space-y-2">
            {ALL_EXCHANGES.map(exchange => {
              const hasSpot = EXCHANGES_WITH_SPOT.includes(exchange)
              const hasFutures = EXCHANGES_WITH_FUTURES.includes(exchange)
              const color = EXCHANGE_COLORS[exchange]

              return (
                <div key={exchange} className="flex items-center gap-4">
                  {/* Иконка + название */}
                  <div className="flex items-center gap-2 w-36">
                    <div className="w-3 h-3 rounded-full" style={{ backgroundColor: color }} />
                    <span className="text-sm text-gray-300">{EXCHANGE_LABELS[exchange]}</span>
                  </div>

                  {/* Spot */}
                  {hasSpot ? (
                    <Toggle
                      checked={isMarketEnabled(exchange, 'SPOT')}
                      onChange={() => toggleMarket(exchange, 'SPOT')}
                      disabled={DISABLED_MARKETS.has(`${exchange}_SPOT`)}
                      label="Spot"
                    />
                  ) : <div className="w-20" />}

                  {/* Futures */}
                  {hasFutures ? (
                    <Toggle
                      checked={isMarketEnabled(exchange, 'FUTURES')}
                      onChange={() => toggleMarket(exchange, 'FUTURES')}
                      disabled={DISABLED_MARKETS.has(`${exchange}_FUTURES`)}
                      label="Futures"
                    />
                  ) : <div className="w-20" />}
                </div>
              )
            })}
          </div>
        </div>

        {/* Мин. размер по биржам */}
        <div>
          <label className="block text-sm text-gray-400 mb-3">Мин. размер ($) по биржам</label>
          <div className="space-y-2">
            {ALL_EXCHANGES.map(exchange => {
              const hasSpot = EXCHANGES_WITH_SPOT.includes(exchange)
              const hasFutures = EXCHANGES_WITH_FUTURES.includes(exchange)

              return (
                <div key={exchange} className="flex items-center gap-3">
                  <div className="flex items-center gap-2 w-36">
                    <div className="w-3 h-3 rounded-full" style={{ backgroundColor: EXCHANGE_COLORS[exchange] }} />
                    <span className="text-sm text-gray-300">{EXCHANGE_LABELS[exchange]}</span>
                  </div>

                  {hasSpot && (
                    <div className="flex items-center gap-1">
                      <span className="text-xs text-gray-500 w-8">Spot</span>
                      <input
                        type="number"
                        placeholder="По умолч."
                        value={getMinDensity(exchange, 'SPOT')}
                        onChange={e => setMinDensity(exchange, 'SPOT', e.target.value)}
                        disabled={DISABLED_MARKETS.has(`${exchange}_SPOT`)}
                        className="w-28 px-2 py-1 bg-[#0f1117] border border-[#2d3748] rounded text-white text-xs focus:outline-none focus:border-blue-500 disabled:opacity-40"
                      />
                    </div>
                  )}

                  {hasFutures && (
                    <div className="flex items-center gap-1">
                      <span className="text-xs text-gray-500 w-12">Futures</span>
                      <input
                        type="number"
                        placeholder="По умолч."
                        value={getMinDensity(exchange, 'FUTURES')}
                        onChange={e => setMinDensity(exchange, 'FUTURES', e.target.value)}
                        disabled={DISABLED_MARKETS.has(`${exchange}_FUTURES`)}
                        className="w-28 px-2 py-1 bg-[#0f1117] border border-[#2d3748] rounded text-white text-xs focus:outline-none focus:border-blue-500 disabled:opacity-40"
                      />
                    </div>
                  )}
                </div>
              )
            })}
          </div>
        </div>

        {/* Настройки отдельных монет */}
        {Object.keys(draft.symbolMinDensityOverrides).length > 0 && (
          <div>
            <label className="block text-sm text-gray-400 mb-3">Настройки монет</label>
            <div className="space-y-1">
              {Object.entries(draft.symbolMinDensityOverrides).map(([symbol, minDensity]) => (
                <div key={symbol} className="flex items-center justify-between px-3 py-2 bg-[#0f1117] rounded-lg">
                  <span className="text-sm font-medium text-white">{symbol}</span>
                  <div className="flex items-center gap-3">
                    <span className="text-sm text-gray-400">мин. ${minDensity.toLocaleString()}</span>
                    <button
                      onClick={() => removeSymbolOverride(symbol)}
                      className="text-gray-500 hover:text-red-400 text-sm transition-colors"
                      title="Удалить override (вернуть общие настройки)"
                    >
                      ✕
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

      </div>

      {/* Футер — кнопки */}
      <div className="flex items-center justify-between px-6 py-4 border-t border-[#2d3748] bg-[#141820]">
        <div className="flex items-center gap-2">
          {/* Импорт */}
          <input
            type="file"
            accept=".json"
            ref={importRef}
            className="hidden"
            onChange={handleImport}
          />
          <button
            onClick={() => importRef.current?.click()}
            className="px-3 py-1.5 text-sm text-gray-400 hover:text-white bg-[#1a1f2e] hover:bg-[#2d3748] rounded-lg border border-[#2d3748] transition-colors"
          >
            Импорт
          </button>
          {/* Экспорт */}
          <button
            onClick={handleExport}
            className="px-3 py-1.5 text-sm text-gray-400 hover:text-white bg-[#1a1f2e] hover:bg-[#2d3748] rounded-lg border border-[#2d3748] transition-colors"
          >
            Экспорт
          </button>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={closeSettings}
            className="px-4 py-1.5 text-sm text-gray-400 hover:text-white transition-colors"
          >
            Отмена
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="px-4 py-1.5 text-sm text-white bg-blue-600 hover:bg-blue-500 rounded-lg transition-colors disabled:opacity-50"
          >
            {saving ? 'Сохранение...' : 'Сохранить'}
          </button>
        </div>
      </div>
    </Modal>
  )
}
