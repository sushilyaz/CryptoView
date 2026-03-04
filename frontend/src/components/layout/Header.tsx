import { useState, useEffect } from 'react'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useDensityStore } from '../../stores/densityStore'
import { useUiStore } from '../../stores/uiStore'
import { statusApi } from '../../api/httpClient'
import type { DensitySortType } from '../../types/enums'

const INNER_SORT_OPTIONS: { value: DensitySortType; label: string }[] = [
  { value: 'DISTANCE_ASC', label: 'Расст.' },
  { value: 'SIZE_USD_DESC', label: 'Объём' },
  { value: 'DURATION_DESC', label: 'Время' },
]

export function Header() {
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const update = useWorkspaceStore(s => s.update)
  const updateActiveLocal = useWorkspaceStore(s => s.updateActiveLocal)
  const connected = useDensityStore(s => s.connected)
  const trackedCount = useDensityStore(s => s.displayedDensities.length)
  const openSettings = useUiStore(s => s.openSettings)
  const openBlacklist = useUiStore(s => s.openBlacklist)
  const innerSortType = useUiStore(s => s.innerSortType)
  const setInnerSortType = useUiStore(s => s.setInnerSortType)

  const [volumeReady, setVolumeReady] = useState(false)

  // Проверяем готовность volume данных каждые 30 сек
  useEffect(() => {
    let active = true
    const check = async () => {
      try {
        const status = await statusApi.get()
        if (active) setVolumeReady(status.volumeTrackingReady ?? false)
      } catch { /* ignore */ }
    }
    check()
    const interval = setInterval(check, 30_000)
    return () => { active = false; clearInterval(interval) }
  }, [])

  const tsMode = activeWorkspace?.tsMode ?? false

  const handleToggleTs = async () => {
    if (!activeWorkspace || !volumeReady) return
    const newTsMode = !tsMode
    updateActiveLocal({ tsMode: newTsMode })
    await update(activeWorkspace.id, { ...activeWorkspace, tsMode: newTsMode })
  }

  return (
    <header className="flex items-center justify-between px-6 py-3 bg-[#141820] border-b border-[#2d3748]">
      {/* Логотип */}
      <div className="flex items-center gap-3">
        <span className="text-xl font-bold text-white">CryptoView</span>
        <div className={`w-2 h-2 rounded-full ${connected ? 'bg-green-400' : 'bg-red-400'}`} title={connected ? 'Подключён' : 'Нет связи'} />
      </div>

      {/* Центр — workspace + контролы */}
      <div className="flex items-center gap-4">
        <div className="flex items-center gap-2">
          <span className="text-sm text-gray-400">Workspace:</span>
          <span className="text-sm font-medium text-white">
            {activeWorkspace?.name ?? '—'}
          </span>
          <span className="text-xs text-gray-500">{trackedCount} плотн.</span>
        </div>

        {/* Тумблер ТС */}
        <button
          onClick={handleToggleTs}
          disabled={!volumeReady}
          className={`px-3 py-1 text-xs font-semibold rounded-lg border transition-colors ${
            tsMode
              ? 'bg-green-600/20 text-green-400 border-green-500/40'
              : 'bg-[#1a1f2e] text-gray-400 border-[#2d3748] hover:text-white'
          } ${!volumeReady ? 'opacity-40 cursor-not-allowed' : ''}`}
          title={!volumeReady ? 'Ожидание данных (15 мин)' : tsMode ? 'Режим ТС: включён' : 'Режим ТС: выключен'}
        >
          {tsMode ? 'По ТС' : 'Выкл'}
        </button>

        {/* Сортировка внутри карточек */}
        <div className="flex items-center gap-1 bg-[#0f1117] rounded-lg border border-[#2d3748] p-0.5">
          {INNER_SORT_OPTIONS.map(opt => (
            <button
              key={opt.value}
              onClick={() => setInnerSortType(opt.value)}
              className={`px-2 py-0.5 text-[11px] rounded-md transition-colors ${
                innerSortType === opt.value
                  ? 'bg-blue-600 text-white'
                  : 'text-gray-400 hover:text-white'
              }`}
            >
              {opt.label}
            </button>
          ))}
        </div>
      </div>

      {/* Кнопки справа */}
      <div className="flex items-center gap-2">
        <button
          onClick={openBlacklist}
          className="px-3 py-1.5 text-sm text-gray-300 hover:text-white bg-[#1a1f2e] hover:bg-[#2d3748] rounded-lg border border-[#2d3748] transition-colors"
        >
          ЧС
        </button>
        <button
          onClick={openSettings}
          className="px-3 py-1.5 text-sm text-white bg-blue-600 hover:bg-blue-500 rounded-lg transition-colors flex items-center gap-1.5"
        >
          Настройки
        </button>
      </div>
    </header>
  )
}
