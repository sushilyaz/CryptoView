import { useDensityStore } from '../../stores/densityStore'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { SymbolCard } from './SymbolCard'

export function DensityGrid() {
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const connected = useDensityStore(s => s.connected)
  const getSymbolGroups = useDensityStore(s => s.getSymbolGroups)

  const groups = getSymbolGroups(
    activeWorkspace?.favoritedSymbols ?? [],
    activeWorkspace?.symbolComments ?? {},
  )

  const newBadgeMinutes = activeWorkspace?.newBadgeDurationMinutes ?? 5

  if (!connected) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        <div className="text-center">
          <div className="text-4xl mb-3">⏳</div>
          <div>Подключение к серверу...</div>
        </div>
      </div>
    )
  }

  if (groups.length === 0) {
    return (
      <div className="flex items-center justify-center h-64 text-gray-500">
        <div className="text-center">
          <div className="text-4xl mb-3">📭</div>
          <div>Плотностей пока нет</div>
          <div className="text-sm mt-1">Ожидание данных с бирж...</div>
        </div>
      </div>
    )
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 2xl:grid-cols-5 gap-3 p-4">
      {groups.map(group => (
        <SymbolCard
          key={group.symbol}
          group={group}
          newBadgeMinutes={newBadgeMinutes}
        />
      ))}
    </div>
  )
}
