import { useEffect } from 'react'
import { wsClient } from '../api/wsClient'
import { useDensityStore } from '../stores/densityStore'
import { useWorkspaceStore } from '../stores/workspaceStore'

export function useWebSocket() {
  const setDensities = useDensityStore(s => s.setDensities)
  const setConnected = useDensityStore(s => s.setConnected)
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)

  useEffect(() => {
    wsClient.connect(activeWorkspace?.id)

    const unsubMsg = wsClient.onMessage((msg) => {
      setDensities(msg.data, msg.timestamp)
    })

    const unsubStatus = wsClient.onStatus((connected) => {
      setConnected(connected)
    })

    return () => {
      unsubMsg()
      unsubStatus()
      wsClient.disconnect()
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])  // подключаемся один раз при монтировании, setDensities/setConnected стабильны (zustand)

  // При смене активного workspace — переключаем WS без переподключения
  useEffect(() => {
    if (activeWorkspace?.id) {
      wsClient.setWorkspace(activeWorkspace.id)
    }
  }, [activeWorkspace?.id])
}
