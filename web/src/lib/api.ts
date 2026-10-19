import { appState } from './stores.svelte'
import type { HealthResponse, ClientResponse, PlayerResponse, WorldResponse } from './types'

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
    if (data.session?.username) appState.playerName = data.session.username
  }
  return data
}

export async function getPlayer(): Promise<PlayerResponse | null> {
  const data = await fetchApi<PlayerResponse>('/v1/player')
  if (data) {
    appState.inGame = true
    if (data.name) appState.playerName = data.name
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

export async function captureScreenshot(): Promise<string | null> {
  const token = appState.token
  if (!token) {
    appState.tokenModalOpen = true
    return null
  }

  try {
    const res = await fetch('/v1/screenshot', {
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
