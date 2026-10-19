export interface HealthResponse {
  status: string
  mcdtVersion: string
  gameVersion: string
  baritone?: string
  clientUptimeMs?: number
  eventRingBufferSize?: number
  eventRingBufferHead?: number
}

export interface ClientResponse {
  version?: string
  fps?: number
  session?: {
    username?: string
    uuid?: string
  }
}

export interface PlayerResponse {
  name: string
  uuid?: string
  health?: number
  maxHealth?: number
  foodLevel?: number
  saturation?: number
  level?: number
  experienceProgress?: number
  position?: {
    x: number
    y: number
    z: number
    yaw: number
    pitch: number
  }
  movement?: {
    isSneaking: boolean
    isSprinting: boolean
    isOnGround: boolean
    isSwimming: boolean
    isFallFlying: boolean
  }
}

export interface WorldResponse {
  dimension?: string
  time?: number
  dayTime?: number
  gameTime?: number
  isRaining?: boolean
  isThundering?: boolean
  difficulty?: string
  loadedEntitiesCount?: number
  loadedChunksCount?: number
}

export interface GameEvent {
  id: string
  timestamp: number
  topic: string
  data: Record<string, unknown>
}

export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
}
