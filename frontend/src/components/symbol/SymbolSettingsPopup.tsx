import { useState, useEffect } from 'react'
import { Modal } from '../common/Modal'
import { useWorkspaceStore } from '../../stores/workspaceStore'
import { useUiStore } from '../../stores/uiStore'
import { symbolApi } from '../../api/httpClient'

export function SymbolSettingsPopup() {
  const symbol = useUiStore(s => s.symbolSettingsSymbol)
  const closeSymbolSettings = useUiStore(s => s.closeSymbolSettings)
  const activeWorkspace = useWorkspaceStore(s => s.activeWorkspace)
  const updateActiveLocal = useWorkspaceStore(s => s.updateActiveLocal)

  const [comment, setComment] = useState('')
  const [minDensity, setMinDensity] = useState('')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    if (symbol && activeWorkspace) {
      setComment(activeWorkspace.symbolComments[symbol] ?? '')
      const override = activeWorkspace.symbolMinDensityOverrides[symbol]
      setMinDensity(override != null ? String(override) : '')
    }
  }, [symbol, activeWorkspace])

  if (!symbol) return null

  const handleSave = async () => {
    if (!activeWorkspace) return
    setSaving(true)
    try {
      // Сохраняем комментарий
      if (comment.trim()) {
        await symbolApi.setComment(activeWorkspace.id, symbol, comment.trim())
        updateActiveLocal({
          symbolComments: { ...activeWorkspace.symbolComments, [symbol]: comment.trim() },
        })
      } else {
        await symbolApi.deleteComment(activeWorkspace.id, symbol)
        const comments = { ...activeWorkspace.symbolComments }
        delete comments[symbol]
        updateActiveLocal({ symbolComments: comments })
      }

      // Сохраняем мин. плотность
      const num = parseInt(minDensity)
      if (!isNaN(num) && minDensity.trim()) {
        await symbolApi.setMinDensity(activeWorkspace.id, symbol, num)
        updateActiveLocal({
          symbolMinDensityOverrides: { ...activeWorkspace.symbolMinDensityOverrides, [symbol]: num },
        })
      } else {
        await symbolApi.deleteMinDensity(activeWorkspace.id, symbol)
        const overrides = { ...activeWorkspace.symbolMinDensityOverrides }
        delete overrides[symbol]
        updateActiveLocal({ symbolMinDensityOverrides: overrides })
      }

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
          <p className="text-xs text-gray-600 mt-1">Оставьте пустым, чтобы использовать общие настройки</p>
        </div>
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
