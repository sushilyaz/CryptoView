import type { Workspace, WorkspaceRequest } from '../types/workspace'
import type { DensityItem } from '../types/density'
import type { DensitySortType } from '../types/enums'

const BASE = '/api/v1'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status}`)
  return res.json()
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status}`)
  return res.json()
}

async function put<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`PUT ${path} failed: ${res.status}`)
  return res.json()
}

async function del<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`DELETE ${path} failed: ${res.status}`)
  return res.json()
}

// --- Workspaces ---

export const workspaceApi = {
  list: () => get<Workspace[]>('/workspaces'),
  getActive: () => get<Workspace>('/workspaces/active'),
  create: (req: WorkspaceRequest) => post<Workspace>('/workspaces', req),
  update: (id: string, req: WorkspaceRequest) => put<Workspace>(`/workspaces/${id}`, req),
  delete: (id: string) => del<void>(`/workspaces/${id}`),
  activate: (id: string) => post<Workspace>(`/workspaces/${id}/activate`),
  export: (id: string) => get<Workspace>(`/workspaces/${id}/export`),
  import: (data: Workspace) => post<Workspace>('/workspaces/import', data),
}

// --- Symbol settings ---

export const symbolApi = {
  addBlacklist: (wsId: string, symbol: string) =>
    post<void>(`/workspaces/${wsId}/blacklist/${symbol}`),
  removeBlacklist: (wsId: string, symbol: string) =>
    del<void>(`/workspaces/${wsId}/blacklist/${symbol}`),
  setComment: (wsId: string, symbol: string, comment: string) =>
    put<void>(`/workspaces/${wsId}/symbols/${symbol}/comment`, { comment }),
  deleteComment: (wsId: string, symbol: string) =>
    del<void>(`/workspaces/${wsId}/symbols/${symbol}/comment`),
  setMinDensity: (wsId: string, symbol: string, minDensity: number) =>
    put<void>(`/workspaces/${wsId}/symbols/${symbol}/min-density`, { minDensity }),
  deleteMinDensity: (wsId: string, symbol: string) =>
    del<void>(`/workspaces/${wsId}/symbols/${symbol}/min-density`),
}

// --- Densities ---

export const densityApi = {
  list: (opts?: { sort?: DensitySortType; limit?: number; workspaceId?: string }) => {
    const params = new URLSearchParams()
    if (opts?.sort) params.set('sort', opts.sort)
    if (opts?.limit) params.set('limit', String(opts.limit))
    if (opts?.workspaceId) params.set('workspaceId', opts.workspaceId)
    return get<DensityItem[]>(`/densities${params.size ? '?' + params : ''}`)
  },
}

// --- Status ---

export interface SystemStatus {
  connectedExchanges: string[]
  totalSymbols: number
  trackedDensities: number
  activeWorkspace: string
}

export const statusApi = {
  get: () => get<SystemStatus>('/status'),
}
