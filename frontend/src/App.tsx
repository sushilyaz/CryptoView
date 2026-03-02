import { useEffect } from 'react'
import { Header } from './components/layout/Header'
import { DensityGrid } from './components/density/DensityGrid'
import { SettingsModal } from './components/workspace/SettingsModal'
import { BlacklistPanel } from './components/symbol/BlacklistPanel'
import { SymbolSettingsPopup } from './components/symbol/SymbolSettingsPopup'
import { useWebSocket } from './hooks/useWebSocket'
import { useWorkspaceStore } from './stores/workspaceStore'

export default function App() {
  const fetchActive = useWorkspaceStore(s => s.fetchActive)

  // Загружаем активный workspace при старте
  useEffect(() => {
    fetchActive()
  }, [])

  // Подключаемся к WebSocket
  useWebSocket()

  return (
    <div className="min-h-screen bg-[#0f1117] flex flex-col">
      <Header />
      <main className="flex-1 overflow-auto">
        <DensityGrid />
      </main>

      {/* Модалки */}
      <SettingsModal />
      <BlacklistPanel />
      <SymbolSettingsPopup />
    </div>
  )
}
