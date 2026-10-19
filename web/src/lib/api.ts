import { appState } from './stores.svelte'
import type {
  HealthResponse,
  ClientResponse,
  PlayerResponse,
  WorldResponse,
  InventoryResponse,
  ScreenResponse,
  EntitiesResponse,
  EntityCategoriesResponse,
  RaycastResponse,
  BaritoneStatusResponse,
  BaritonePathResponse,
  BaritoneJobResponse,
  BaritoneSettingInfo,
  RootsResponse,
  ObjectInspectResponse,
  ClassInspectResponse,
  RegistryEntryResponse,
  PlayerSkinResponse,
  TextureAtlasesResponse,
  TextureAtlasResponse
} from './types'

export async function fetchApi<T>(path: string, options: RequestInit = {}): Promise<T | null> {
  const token = appState.token
  if (!token) {
    appState.tokenModalOpen = true
    return null
  }

  const headers = new Headers(options.headers || {})
  headers.set('Authorization', `Bearer ${token}`)
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }

  try {
    const res = await fetch(path, {
      ...options,
      headers
    })

    if (res.status === 401) {
      appState.tokenModalOpen = true
      return null
    }

    if (res.status === 409) {
      // Not in game or conflict
      appState.inGame = false
      return null
    }

    if (!res.ok) {
      const errorData = await res.json().catch(() => ({ detail: res.statusText }))
      throw new Error(errorData.detail || errorData.title || `Request failed with status ${res.status}`)
    }

    appState.serverOnline = true
    return (await res.json()) as T
  } catch (err: unknown) {
    if (err instanceof TypeError && err.message.includes('fetch')) {
      appState.serverOnline = false
    }
    throw err
  }
}

export async function testToken(tokenToTest: string): Promise<boolean> {
  try {
    const res = await fetch('/v1/health', {
      headers: {
        'Authorization': `Bearer ${tokenToTest.trim()}`
      }
    })
    return res.status === 200
  } catch {
    return false
  }
}

export async function getHealth(): Promise<HealthResponse | null> {
  const data = await fetchApi<HealthResponse>('/v1/health')
  if (data) {
    appState.serverOnline = true
    appState.mcdtVersion = data.mcdtVersion || ''
    appState.gameVersion = data.gameVersion || ''
  }
  return data
}

export async function getClient(): Promise<ClientResponse | null> {
  const data = await fetchApi<ClientResponse>('/v1/client')
  if (data) {
    if (data.fps !== undefined) appState.fps = data.fps
  }
  return data
}

export async function getPlayer(): Promise<PlayerResponse | null> {
  const data = await fetchApi<PlayerResponse>('/v1/player')
  if (data) {
    appState.inGame = true
  } else {
    appState.inGame = false
  }
  return data
}

export async function getWorld(): Promise<WorldResponse | null> {
  return await fetchApi<WorldResponse>('/v1/world')
}

export async function sendChat(message: string): Promise<boolean> {
  const res = await fetchApi<{ sent: boolean }>('/v1/chat', {
    method: 'POST',
    body: JSON.stringify({ message })
  })
  return !!res
}

export async function pressKey(key: string, action = 'press'): Promise<boolean> {
  const res = await fetchApi<{ success: boolean }>('/v1/input/keys', {
    method: 'POST',
    body: JSON.stringify({ key, action })
  })
  return !!res
}

/**
 * Fetches a binary endpoint with the bearer token and returns an object URL for it.
 * Plain <img src> and <a href> can't send the Authorization header, so images go through here.
 * The caller owns the URL and should revoke it with URL.revokeObjectURL when done.
 */
export async function fetchBlobUrl(path: string): Promise<string | null> {
  const token = appState.token
  if (!token) {
    appState.tokenModalOpen = true
    return null
  }

  try {
    const res = await fetch(path, {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    })
    if (!res.ok) return null
    const blob = await res.blob()
    return URL.createObjectURL(blob)
  } catch {
    return null
  }
}

export async function captureScreenshot(): Promise<string | null> {
  return fetchBlobUrl('/v1/screenshot')
}

// --- Inventory & Screen APIs ---
export async function getInventory(): Promise<InventoryResponse | null> {
  return await fetchApi<InventoryResponse>('/v1/player/inventory')
}

export async function getScreen(): Promise<ScreenResponse | null> {
  return await fetchApi<ScreenResponse>('/v1/screen')
}

export async function clickScreenSlot(slot: number, button = 0, mode = 'pickup'): Promise<boolean> {
  const res = await fetchApi<{ success: boolean }>('/v1/screen/slots', {
    method: 'POST',
    body: JSON.stringify({ slot, button, mode })
  })
  return !!res
}

export async function closeScreen(): Promise<boolean> {
  const res = await fetchApi<{ success: boolean }>('/v1/screen/actions', {
    method: 'POST',
    body: JSON.stringify({ action: 'close' })
  })
  return !!res
}

// --- World Radar & Entities APIs ---
export async function getEntities(radius = 32, type?: string, pos?: string, limit = 100): Promise<EntitiesResponse | null> {
  const params = new URLSearchParams()
  params.set('radius', radius.toString())
  params.set('limit', limit.toString())
  if (type) params.set('type', type)
  if (pos) params.set('pos', pos)
  return await fetchApi<EntitiesResponse>(`/v1/world/entities?${params.toString()}`)
}

export async function getEntityCategories(): Promise<EntityCategoriesResponse | null> {
  return await fetchApi<EntityCategoriesResponse>('/v1/world/entities/categories')
}

export async function getRaycast(): Promise<RaycastResponse | null> {
  return await fetchApi<RaycastResponse>('/v1/world/raycast')
}

// --- Baritone Automation APIs ---
export async function getBaritoneStatus(): Promise<BaritoneStatusResponse | null> {
  return await fetchApi<BaritoneStatusResponse>('/v1/baritone/status')
}

export async function getBaritonePath(): Promise<BaritonePathResponse | null> {
  return await fetchApi<BaritonePathResponse>('/v1/baritone/path')
}

export async function startBaritoneGoto(x: number, y: number, z: number): Promise<BaritoneJobResponse | null> {
  return await fetchApi<BaritoneJobResponse>('/v1/baritone/goto', {
    method: 'POST',
    body: JSON.stringify({ x, y, z })
  })
}

export async function startBaritoneMine(blocks: string[], quantity = 1): Promise<BaritoneJobResponse | null> {
  return await fetchApi<BaritoneJobResponse>('/v1/baritone/mine', {
    method: 'POST',
    body: JSON.stringify({ blocks, quantity })
  })
}

export async function startBaritoneFollow(nameOrUuid: string): Promise<BaritoneJobResponse | null> {
  return await fetchApi<BaritoneJobResponse>('/v1/baritone/follow', {
    method: 'POST',
    body: JSON.stringify({ name: nameOrUuid })
  })
}

export async function startBaritoneExplore(x?: number, z?: number): Promise<BaritoneJobResponse | null> {
  return await fetchApi<BaritoneJobResponse>('/v1/baritone/explore', {
    method: 'POST',
    body: JSON.stringify(x !== undefined && z !== undefined ? { x, z } : {})
  })
}

export async function cancelBaritone(): Promise<boolean> {
  const res = await fetchApi<{ cancelled: boolean }>('/v1/baritone/cancel', {
    method: 'POST',
    body: JSON.stringify({})
  })
  return !!res?.cancelled
}

export async function getBaritoneSettings(): Promise<Map<string, BaritoneSettingInfo> | Record<string, BaritoneSettingInfo> | null> {
  return await fetchApi<Record<string, BaritoneSettingInfo>>('/v1/baritone/settings')
}

export async function patchBaritoneSettings(patch: Record<string, unknown>): Promise<Record<string, unknown> | null> {
  return await fetchApi<Record<string, unknown>>('/v1/baritone/settings', {
    method: 'PATCH',
    body: JSON.stringify(patch)
  })
}

export async function executeBaritoneCommand(command: string): Promise<boolean> {
  const res = await fetchApi<{ success: boolean; command: string }>('/v1/baritone/command', {
    method: 'POST',
    body: JSON.stringify({ command })
  })
  return !!res?.success
}

export async function getRegistryEntries(registry: string, query = '', limit = 100): Promise<RegistryEntryResponse | null> {
  const q = query ? `&query=${encodeURIComponent(query)}` : ''
  return await fetchApi<RegistryEntryResponse>(`/v1/registries/${encodeURIComponent(registry)}?limit=${limit}${q}`)
}

// --- Interactive Player Controls APIs ---
export async function playerMove(options: {
  forward?: boolean
  backward?: boolean
  left?: boolean
  right?: boolean
  jump?: boolean
  sneak?: boolean
  sprint?: boolean
  stop?: boolean
  ticks?: number
}): Promise<boolean> {
  const res = await fetchApi<{ success: boolean }>('/v1/player/move', {
    method: 'POST',
    body: JSON.stringify(options)
  })
  return !!res
}

export async function playerLook(options: {
  yaw?: number
  pitch?: number
  deltaYaw?: number
  deltaPitch?: number
  target?: [number, number, number]
}): Promise<boolean> {
  const res = await fetchApi<{ yaw: number; pitch: number }>('/v1/player/look', {
    method: 'POST',
    body: JSON.stringify(options)
  })
  return !!res
}

export async function playerAction(action: 'attack' | 'use' | 'drop' | 'swap_hands' | 'respawn'): Promise<boolean> {
  const res = await fetchApi<{ success: boolean }>('/v1/player/actions', {
    method: 'POST',
    body: JSON.stringify({ action })
  })
  return !!res
}

// --- Reflection & Packet APIs ---
export async function getObjectRoots(): Promise<RootsResponse | null> {
  return await fetchApi<RootsResponse>('/v1/objects/roots')
}

export async function inspectObject(handle: string, path?: string): Promise<ObjectInspectResponse | null> {
  const url = path ? `/v1/objects/${encodeURIComponent(handle)}?path=${encodeURIComponent(path)}` : `/v1/objects/${encodeURIComponent(handle)}`
  return await fetchApi<ObjectInspectResponse>(url)
}

export async function inspectClass(className: string): Promise<ClassInspectResponse | null> {
  return await fetchApi<ClassInspectResponse>(`/v1/classes/${encodeURIComponent(className)}`)
}

export async function sendPacket(payload: Record<string, unknown>): Promise<boolean> {
  const res = await fetchApi<{ success: boolean }>('/v1/packets', {
    method: 'POST',
    body: JSON.stringify(payload)
  })
  return !!res
}

// --- Player Skin & Texture APIs ---
export async function getPlayerSkinInfo(player?: string, embedImage?: boolean): Promise<PlayerSkinResponse | null> {
  const params = new URLSearchParams()
  if (player) params.set('player', player)
  if (embedImage) params.set('embed_image', 'true')
  const query = params.toString() ? `?${params.toString()}` : ''
  const data = await fetchApi<PlayerSkinResponse>(`/v1/player/skin/info${query}`)
  if (data && !player && data.playerName) appState.playerName = data.playerName
  return data
}

export async function loadPlayerSkinImage(player?: string): Promise<string | null> {
  const params = new URLSearchParams()
  if (player) params.set('player', player)
  const query = params.toString() ? `?${params.toString()}` : ''
  return fetchBlobUrl(`/v1/player/skin${query}`)
}

export async function getLoadedAtlases(): Promise<TextureAtlasesResponse | null> {
  return await fetchApi<TextureAtlasesResponse>('/v1/textures/atlases')
}

export async function getAtlasDetails(atlasId?: string, embedImage?: boolean): Promise<TextureAtlasResponse | null> {
  const params = new URLSearchParams()
  if (atlasId) params.set('id', atlasId)
  if (embedImage) params.set('embed_image', 'true')
  const query = params.toString() ? `?${params.toString()}` : ''
  return await fetchApi<TextureAtlasResponse>(`/v1/textures/atlas${query}`)
}

export async function loadAtlasImage(atlasId?: string): Promise<string | null> {
  const params = new URLSearchParams()
  if (atlasId) params.set('id', atlasId)
  const query = params.toString() ? `?${params.toString()}` : ''
  return fetchBlobUrl(`/v1/textures/atlas/image${query}`)
}
