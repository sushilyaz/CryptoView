import type { Workspace, WorkspaceRequest, ExchangeMarketKey } from '../types/workspace'
import type { DensityItem } from '../types/density'
import type { DensitySortType } from '../types/enums'

const BASE = '/api/v1'

function parseBody<T>(res: Response): Promise<T> {
  if (res.status === 204 || res.headers.get('content-length') === '0') {
    return Promise.resolve(undefined as T)
  }
  return res.json()
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`)
  if (!res.ok) throw new Error(`GET ${path} failed: ${res.status}`)
  return parseBody<T>(res)
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status}`)
  return parseBody<T>(res)
}

async function put<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  })
  if (!res.ok) throw new Error(`PUT ${path} failed: ${res.status}`)
  return parseBody<T>(res)
}

async function del<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`DELETE ${path} failed: ${res.status}`)
  return parseBody<T>(res)
}

// --- enabledMarkets conversion (frontend ExchangeMarketKey[] <-> backend string[]) ---

function marketsToBackend(markets: ExchangeMarketKey[]): string[] {
  return markets.map(m => `${m.exchange}_${m.marketType}`)
}

function marketsFromBackend(keys: unknown[]): ExchangeMarketKey[] {
  if (!keys || keys.length === 0) return []
  return keys.map((key) => {
    if (typeof key === 'string') {
      const [exchange, marketType] = key.split('_')
      return { exchange, marketType } as ExchangeMarketKey
    }
    return key as ExchangeMarketKey
  })
}

function workspaceFromBackend(raw: Workspace): Workspace {
  return { ...raw, enabledMarkets: marketsFromBackend(raw.enabledMarkets as unknown as unknown[]) }
}

function requestToBackend(req: WorkspaceRequest): Record<string, unknown> {
  return { ...req, enabledMarkets: marketsToBackend(req.enabledMarkets) }
}

// --- Workspaces ---

export const workspaceApi = {
  list: async () => {
    const list = await get<Workspace[]>('/workspaces')
    return list.map(workspaceFromBackend)
  },
  getActive: async () => workspaceFromBackend(await get<Workspace>('/workspaces/active')),
  create: async (req: WorkspaceRequest) => workspaceFromBackend(await post<Workspace>('/workspaces', requestToBackend(req))),
  update: async (id: string, req: WorkspaceRequest) => workspaceFromBackend(await put<Workspace>(`/workspaces/${id}`, requestToBackend(req))),
  delete: (id: string) => del<void>(`/workspaces/${id}`),
  activate: async (id: string) => workspaceFromBackend(await post<Workspace>(`/workspaces/${id}/activate`)),
  export: async (id: string) => workspaceFromBackend(await get<Workspace>(`/workspaces/${id}/export`)),
  import: async (data: Workspace) => workspaceFromBackend(await post<Workspace>('/workspaces/import', data)),
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
  volumeTrackingReady: boolean
  appUptimeSeconds: number
}

export const statusApi = {
  get: () => get<SystemStatus>('/status'),
}
