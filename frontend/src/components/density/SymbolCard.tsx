import { useState, useMemo } from 'react'
import type { SymbolGroup } from '../../types/density'
import { DensityRow } from './DensityRow'
import { formatDuration } from '../../utils/formatters'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useUiStore } from '../../stores/uiStore'
import { symbolApi } from '../../api/httpClient'

interface SymbolCardProps {
  group: SymbolGroup
  newBadgeMinutes: number
}

export function SymbolCard({ group, newBadgeMinutes }: SymbolCardProps) {
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const update = useWorkspaceStore(s => s.update)
  const updateActiveLocal = useWorkspaceStore(s => s.updateActiveLocal)
  const openSymbolSettings = useUiStore(s => s.openSymbolSettings)
  const innerSortType = useUiStore(s => s.innerSortType)

  const [addingToBlacklist, setAddingToBlacklist] = useState(false)

  const maxDuration = Math.max(...group.densities.map(d => d.durationSeconds))
  const isFavorite = group.isFavorite

  // Сортировка плотностей внутри карточки
  const sortedDensities = useMemo(() => {
    const copy = [...group.densities]
    switch (innerSortType) {
      case 'DISTANCE_ASC':
        return copy.sort((a, b) => a.distancePercent - b.distancePercent)
      case 'SIZE_USD_DESC':
        return copy.sort((a, b) => b.volumeUsd - a.volumeUsd)
      case 'DURATION_DESC':
        return copy.sort((a, b) => b.durationSeconds - a.durationSeconds)
      default:
        return copy
    }
  }, [group.densities, innerSortType])

  const handleToggleFavorite = async () => {
    if (!activeWorkspace) return
    const ticker = group.symbol // базовый тикер (ETH, BTC)
    const favoritedSymbols = isFavorite
      ? activeWorkspace.favoritedSymbols.filter(s => s !== ticker)
      : [...activeWorkspace.favoritedSymbols, ticker]

    updateActiveLocal({ favoritedSymbols })
    await update(activeWorkspace.id, { ...activeWorkspace, favoritedSymbols })
  }

  const handleAddToBlacklist = async () => {
    if (!activeWorkspace) return
    setAddingToBlacklist(true)
    try {
      // Бэкенд фильтрует по raw symbol — добавляем все варианты
      const rawSymbols = group.rawSymbols
      for (const sym of rawSymbols) {
        await symbolApi.addBlacklist(activeWorkspace.id, sym)
      }
      const blacklistedSymbols = [...activeWorkspace.blacklistedSymbols, ...rawSymbols]
      updateActiveLocal({ blacklistedSymbols })
    } finally {
      setAddingToBlacklist(false)
    }
  }

  return (
    <div className={`bg-[#1a1f2e] rounded-xl border ${isFavorite ? 'border-yellow-500/40' : 'border-[#2d3748]'} overflow-hidden`}>
      {/* Заголовок карточки */}
      <div className="flex items-center justify-between px-3 py-2 bg-[#141820]">
        <div className="flex items-center gap-2">
          {/* Звёздочка — избранное */}
          <button
            onClick={handleToggleFavorite}
            className={`text-base transition-colors ${isFavorite ? 'text-yellow-400' : 'text-gray-600 hover:text-yellow-400'}`}
            title={isFavorite ? 'Убрать из избранного' : 'В избранное'}
          >
            &#x2605;
          </button>
          <span className="font-semibold text-white text-sm">{group.symbol}</span>
          {group.comment && (
            <span className="text-xs text-gray-500 italic truncate max-w-[120px]">
              {group.comment}
            </span>
          )}
        </div>

        <div className="flex items-center gap-1.5">
          {/* Время жизни (макс.) */}
          <span className="text-xs text-gray-500">{formatDuration(maxDuration)}</span>

          {/* Шестерёнка — настройки символа */}
          <button
            onClick={() => openSymbolSettings(group.symbol, group.rawSymbols)}
            className="text-gray-500 hover:text-gray-300 transition-colors text-sm px-1"
            title="Настройки монеты"
          >
            &#x2699;
          </button>

          {/* Кнопка ЧС */}
          <button
            onClick={handleAddToBlacklist}
            disabled={addingToBlacklist}
            className="text-xs text-gray-500 hover:text-red-400 transition-colors px-1"
            title="Добавить в чёрный список"
          >
            &#x1F6AB;
          </button>
        </div>
      </div>

      {/* Плотности */}
      <div className="py-1">
        {sortedDensities.map((d) => (
          <DensityRow
            key={`${d.exchange}_${d.marketType}_${d.side}_${d.price}`}
            density={d}
            newBadgeMinutes={newBadgeMinutes}
          />
        ))}
      </div>
    </div>
  )
}
