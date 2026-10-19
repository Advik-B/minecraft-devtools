function initToken(): string {
  if (typeof window === 'undefined') return ''
  
  // Check URL hash first (e.g. #token=abc or #/something?token=abc)
  const hash = window.location.hash
  if (hash) {
    const match = hash.match(/token=([a-zA-Z0-9_-]+)/)
    if (match && match[1]) {
      const extractedToken = match[1]
      try {
        localStorage.setItem('mcdt_token', extractedToken)
      } catch {
        // Ignore localStorage error in private mode
      }
      // Clean up hash from address bar for security
      const cleanUrl = window.location.pathname + window.location.search
      window.history.replaceState(null, '', cleanUrl)
      return extractedToken
    }
  }

  // Fallback to localStorage
  try {
    return localStorage.getItem('mcdt_token') || ''
  } catch {
    return ''
  }
}

class AppState {
  token = $state(initToken())
  tokenModalOpen = $state(false)
  serverOnline = $state(false)
  inGame = $state(false)
  mcdtVersion = $state('')
  gameVersion = $state('')
  playerName = $state('')
  fps = $state(0)
  tick = $state(0)
  activeTab = $state<'telemetry' | 'events' | 'actions' | 'docs'>('telemetry')
  wsStatus = $state<'connected' | 'connecting' | 'disconnected'>('disconnected')

  setToken(newToken: string) {
    this.token = newToken.trim()
    try {
      if (this.token) {
        localStorage.setItem('mcdt_token', this.token)
      } else {
        localStorage.removeItem('mcdt_token')
      }
    } catch {
      // Ignore
    }
  }

  clearToken() {
    this.setToken('')
    this.tokenModalOpen = true
  }
}

export const appState = new AppState()
