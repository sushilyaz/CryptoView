import { useState, useEffect, useRef } from 'react'
import { Modal } from '../common/Modal'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useUiStore } from '../../stores/uiStore'
import { symbolApi, statusApi } from '../../api/httpClient'
import { EXCHANGE_LABELS } from '../../utils/constants'

export function SymbolSettingsPopup() {
  const symbol = useUiStore(s => s.symbolSettingsSymbol)
  const rawSymbols = useUiStore(s => s.symbolSettingsRawSymbols)
  const closeSymbolSettings = useUiStore(s => s.closeSymbolSettings)
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const update = useWorkspaceStore(s => s.update)
  const updateActiveLocal = useWorkspaceStore(s => s.updateActiveLocal)

  const [comment, setComment] = useState('')
  const [minDensity, setMinDensity] = useState('')
  const [marketOverrides, setMarketOverrides] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const [availableMarkets, setAvailableMarkets] = useState<string[]>([])
  const [loading, setLoading] = useState(false)

  // Снапшот workspace при открытии — чтобы обновления densities не сбрасывали инпуты
  const snapshotRef = useRef(activeWorkspace)

  // При открытии модалки: загрузить биржи из бэкенда + снапшотить workspace
  useEffect(() => {
    if (!symbol || !activeWorkspace) return

    // Снапшотим workspace один раз при открытии
    snapshotRef.current = activeWorkspace

    // Инициализируем инпуты из снапшота
    setComment(activeWorkspace.symbolComments[symbol] ?? '')
    const firstRaw = rawSymbols[0]?.toUpperCase() ?? symbol.toUpperCase()
    const override = activeWorkspace.symbolMinDensityOverrides[firstRaw]
    setMinDensity(override != null ? String(override) : '')

    // Загружаем доступные биржи из API
    setLoading(true)
    statusApi.getMarketsForSymbol(symbol)
      .then(markets => {
        setAvailableMarkets(markets.sort())
        // Инициализируем market overrides после получения бирж
        const overrides: Record<string, string> = {}
        for (const marketKey of markets) {
          const rawSym = findRawForMarket(marketKey, rawSymbols, symbol)
          const key = `${rawSym}_${marketKey}`
          const val = activeWorkspace.symbolMarketMinDensityOverrides[key]
          overrides[marketKey] = val != null ? String(val) : ''
        }
        setMarketOverrides(overrides)
      })
      .catch(() => setAvailableMarkets([]))
      .finally(() => setLoading(false))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [symbol]) // Только при смене символа (открытии модалки)

  if (!symbol) return null

  const handleSave = async () => {
    const ws = snapshotRef.current
    if (!ws) return
    setSaving(true)
    try {
      const firstRaw = rawSymbols[0]?.toUpperCase() ?? symbol.toUpperCase()

      // 1. Комментарий (по базовому тикеру)
      if (comment.trim()) {
        await symbolApi.setComment(ws.id, symbol, comment.trim())
        updateActiveLocal({
          symbolComments: { ...ws.symbolComments, [symbol]: comment.trim() },
        })
      } else {
        await symbolApi.deleteComment(ws.id, symbol)
        const comments = { ...ws.symbolComments }
        delete comments[symbol]
        updateActiveLocal({ symbolComments: comments })
      }

      // 2. Глобальный per-symbol min density (по raw symbol)
      const num = parseInt(minDensity)
      if (!isNaN(num) && minDensity.trim()) {
        await symbolApi.setMinDensity(ws.id, firstRaw, num)
        updateActiveLocal({
          symbolMinDensityOverrides: { ...ws.symbolMinDensityOverrides, [firstRaw]: num },
        })
      } else {
        await symbolApi.deleteMinDensity(ws.id, firstRaw)
        const overrides = { ...ws.symbolMinDensityOverrides }
        delete overrides[firstRaw]
        updateActiveLocal({ symbolMinDensityOverrides: overrides })
      }

      // 3. Per-market overrides — через PUT workspace
      const newSymbolMarketOverrides = { ...ws.symbolMarketMinDensityOverrides }
      for (const marketKey of availableMarkets) {
        const rawSym = findRawForMarket(marketKey, rawSymbols, symbol)
        const compositeKey = `${rawSym}_${marketKey}`
        const val = parseInt(marketOverrides[marketKey] ?? '')
        if (!isNaN(val) && (marketOverrides[marketKey] ?? '').trim()) {
          newSymbolMarketOverrides[compositeKey] = val
        } else {
          delete newSymbolMarketOverrides[compositeKey]
        }
      }
      updateActiveLocal({ symbolMarketMinDensityOverrides: newSymbolMarketOverrides })
      await update(ws.id, {
        ...ws,
        symbolMarketMinDensityOverrides: newSymbolMarketOverrides,
      })

      closeSymbolSettings()
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal
      open={!!symbol}
      onClose={closeSymbolSettings}
      title={`Настройки: ${symbol}`}
      width="max-w-md"
    >
      <div className="p-4 space-y-4">
        <div>
          <label className="block text-sm text-gray-400 mb-1">Комментарий</label>
          <input
            type="text"
            value={comment}
            onChange={e => setComment(e.target.value)}
            placeholder="Например: крупный бид"
            className="w-full px-3 py-2 bg-[#0f1117] border border-[#2d3748] rounded-lg text-white text-sm focus:outline-none focus:border-blue-500"
          />
        </div>

        <div>
          <label className="block text-sm text-gray-400 mb-1">Мин. размер плотности ($)</label>
          <input
            type="number"
            value={minDensity}
            onChange={e => setMinDensity(e.target.value)}
            placeholder="По умолчанию (из общих настроек)"
            className="w-full px-3 py-2 bg-[#0f1117] border border-[#2d3748] rounded-lg text-white text-sm focus:outline-none focus:border-blue-500"
          />
          <p className="text-xs text-gray-600 mt-1">Общий порог для всех бирж. Оставьте пустым для настроек workspace.</p>
        </div>

        {loading ? (
          <div className="text-xs text-gray-500">Загрузка бирж...</div>
        ) : availableMarkets.length > 0 && (
          <div>
            <label className="block text-sm text-gray-400 mb-2">Мин. размер по биржам ($)</label>
            <div className="space-y-2">
              {availableMarkets.map(marketKey => (
                <div key={marketKey} className="flex items-center gap-2">
                  <span className="text-xs text-gray-400 w-28 shrink-0">{formatMarketLabel(marketKey)}</span>
                  <input
                    type="number"
                    value={marketOverrides[marketKey] ?? ''}
                    onChange={e => setMarketOverrides(prev => ({ ...prev, [marketKey]: e.target.value }))}
                    placeholder="—"
                    className="flex-1 px-2 py-1.5 bg-[#0f1117] border border-[#2d3748] rounded text-white text-sm focus:outline-none focus:border-blue-500"
                  />
                </div>
              ))}
            </div>
            <p className="text-xs text-gray-600 mt-1">Перезаписывает общий порог для конкретной биржи.</p>
          </div>
        )}
      </div>

      <div className="flex justify-end gap-2 px-4 py-3 border-t border-[#2d3748] bg-[#141820]">
        <button
          onClick={closeSymbolSettings}
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
    </Modal>
  )
}

// Определяет raw symbol для данного market key (например BINANCE_FUTURES -> ETHUSDT)
function findRawForMarket(marketKey: string, rawSymbols: string[], baseTicker: string): string {
  const [, marketType] = marketKey.split('_')
  // Hyperliquid Futures использует базовый тикер без суффикса
  if (marketKey.startsWith('HYPERLIQUID')) return baseTicker.toUpperCase()
  // CEX: обычно USDT для futures/spot
  const suffix = marketType === 'FUTURES' ? 'USDT' : 'USDT'
  const candidate = baseTicker.toUpperCase() + suffix
  if (rawSymbols.map(s => s.toUpperCase()).includes(candidate)) return candidate
  return rawSymbols[0]?.toUpperCase() ?? baseTicker.toUpperCase() + 'USDT'
}

function formatMarketLabel(marketKey: string): string {
  const [exchange, marketType] = marketKey.split('_')
  const label = EXCHANGE_LABELS[exchange as keyof typeof EXCHANGE_LABELS] ?? exchange
  return `${label} ${marketType === 'FUTURES' ? 'Fut' : 'Spot'}`
}
