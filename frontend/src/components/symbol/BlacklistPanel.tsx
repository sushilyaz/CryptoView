import { Modal } from '../common/Modal'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useUiStore } from '../../stores/uiStore'
import { symbolApi } from '../../api/httpClient'

export function BlacklistPanel() {
  const blacklistOpen = useUiStore(s => s.blacklistOpen)
  const closeBlacklist = useUiStore(s => s.closeBlacklist)
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const updateActiveLocal = useWorkspaceStore(s => s.updateActiveLocal)

  const blacklist = activeWorkspace?.blacklistedSymbols ?? []

  const handleRemove = async (symbol: string) => {
    if (!activeWorkspace) return
    await symbolApi.removeBlacklist(activeWorkspace.id, symbol)
    updateActiveLocal({
      blacklistedSymbols: activeWorkspace.blacklistedSymbols.filter(s => s !== symbol),
    })
  }

  return (
    <Modal open={blacklistOpen} onClose={closeBlacklist} title="Чёрный список" width="max-w-md">
      <div className="p-4">
        {blacklist.length === 0 ? (
          <p className="text-gray-500 text-sm text-center py-8">Чёрный список пуст</p>
        ) : (
          <div className="space-y-1">
            {blacklist.map(symbol => (
              <div key={symbol} className="flex items-center justify-between px-3 py-2 bg-[#0f1117] rounded-lg">
                <span className="text-sm text-white font-medium">{symbol}</span>
                <button
                  onClick={() => handleRemove(symbol)}
                  className="text-xs text-gray-500 hover:text-green-400 transition-colors"
                  title="Убрать из ЧС"
                >
                  Убрать
                </button>
              </div>
            ))}
          </div>
        )}
      </div>
    </Modal>
  )
}
