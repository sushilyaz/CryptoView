import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useDensityStore } from '../../stores/densityStore'
import { useUiStore } from '../../stores/uiStore'

export function Header() {
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const connected = useDensityStore(s => s.connected)
  const trackedCount = useDensityStore(s => s.densities.length)
  const openSettings = useUiStore(s => s.openSettings)
  const openBlacklist = useUiStore(s => s.openBlacklist)

  return (
    <header className="flex items-center justify-between px-6 py-3 bg-[#141820] border-b border-[#2d3748]">
      {/* Логотип */}
      <div className="flex items-center gap-3">
        <span className="text-xl font-bold text-white">CryptoView</span>
        <div className={`w-2 h-2 rounded-full ${connected ? 'bg-green-400' : 'bg-red-400'}`} title={connected ? 'Подключён' : 'Нет связи'} />
      </div>

      {/* Центр — имя workspace */}
      <div className="flex items-center gap-2">
        <span className="text-sm text-gray-400">Workspace:</span>
        <span className="text-sm font-medium text-white">
          {activeWorkspace?.name ?? '—'}
        </span>
        <span className="text-xs text-gray-500 ml-2">{trackedCount} плотностей</span>
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
          ⚙ Настройки
        </button>
      </div>
    </header>
  )
}
