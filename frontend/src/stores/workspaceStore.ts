import { create } from 'zustand'
import type { Workspace, WorkspaceRequest } from '../types/workspace'
import { workspaceApi } from '../api/httpClient'

interface WorkspaceState {
  workspaces: Workspace[]
  activeWorkspace: Workspace | null
  loading: boolean
  error: string | null

  fetchAll: () => Promise<void>
  fetchActive: () => Promise<void>
  create: (req: WorkspaceRequest) => Promise<void>
  update: (id: string, req: WorkspaceRequest) => Promise<void>
  activate: (id: string) => Promise<void>
  delete: (id: string) => Promise<void>
  exportWorkspace: (id: string) => Promise<void>
  importWorkspace: (file: File) => Promise<void>

  // Локальное обновление активного workspace без запроса на сервер
  updateActiveLocal: (patch: Partial<Workspace>) => void
}

export const useWorkspaceStore = create<WorkspaceState>((set) => ({
  workspaces: [],
  activeWorkspace: null,
  loading: false,
  error: null,

  fetchAll: async () => {
    try {
      set({ loading: true, error: null })
      const workspaces = await workspaceApi.list()
      set({ workspaces })
    } catch (e) {
      set({ error: String(e) })
    } finally {
      set({ loading: false })
    }
  },

  fetchActive: async () => {
    try {
      const activeWorkspace = await workspaceApi.getActive()
      set({ activeWorkspace })
    } catch (e) {
      set({ error: String(e) })
    }
  },

  create: async (req) => {
    const ws = await workspaceApi.create(req)
    set(s => ({ workspaces: [...s.workspaces, ws] }))
  },

  update: async (id, req) => {
    const updated = await workspaceApi.update(id, req)
    set(s => ({
      workspaces: s.workspaces.map(w => w.id === id ? updated : w),
      activeWorkspace: s.activeWorkspace?.id === id ? updated : s.activeWorkspace,
    }))
  },

  activate: async (id) => {
    const activated = await workspaceApi.activate(id)
    set({ activeWorkspace: activated })
  },

  delete: async (id) => {
    await workspaceApi.delete(id)
    set(s => ({ workspaces: s.workspaces.filter(w => w.id !== id) }))
  },

  exportWorkspace: async (id) => {
    const data = await workspaceApi.export(id)
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `workspace-${data.name}.json`
    a.click()
    URL.revokeObjectURL(url)
  },

  importWorkspace: async (file) => {
    const text = await file.text()
    const data = JSON.parse(text) as Workspace
    const imported = await workspaceApi.import(data)
    set(s => ({ workspaces: [...s.workspaces, imported] }))
  },

  updateActiveLocal: (patch) => {
    set(s => ({
      activeWorkspace: s.activeWorkspace ? { ...s.activeWorkspace, ...patch } : null,
    }))
  },
}))
