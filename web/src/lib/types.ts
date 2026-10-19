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

// --- Inventory & Screen Types ---
export interface SlotInfo {
  slot: number
  empty: boolean
  id?: string
  count?: number
  name?: string
  damage?: number
  maxDamage?: number
  rarity?: 'common' | 'uncommon' | 'rare' | 'epic' | string
  components?: Record<string, unknown>
}

export interface InventoryResponse {
  selectedSlot: number
  main: SlotInfo[]
  armor: SlotInfo[]
  offhand: SlotInfo[]
}

export interface ScreenResponse {
  open: boolean
  className?: string
  title?: string
  width?: number
  height?: number
  widgets?: Array<{
    index: number
    type: string
    message: string
    bounds: { x: number; y: number; width: number; height: number }
    active: boolean
    visible: boolean
    focused: boolean
  }>
  container?: {
    type: string
    slotCount: number
    slots: Array<{
      index: number
      x: number
      y: number
      item: SlotInfo
    }>
  }
}

// --- Entity & Radar Types ---
export interface EntityInfo {
  id: number
  uuid: string
  type: string
  pos: [number, number, number]
  velocity: [number, number, number]
  yaw: number
  pitch: number
  distance: number
  alive: boolean
  health?: number
  maxHealth?: number
  customName?: string
  boundingBox?: {
    min: [number, number, number]
    max: [number, number, number]
  }
  category?: 'player' | 'hostile' | 'passive' | 'item' | 'other' | string
}

export interface EntityCategoriesResponse {
  hostile: string[]
  passive: string[]
  categories: Record<string, string>
}

export interface EntitiesResponse {
  center: [number, number, number]
  radius: number
  count: number
  entities: EntityInfo[]
}

export interface RaycastResponse {
  hitType: 'MISS' | 'BLOCK' | 'ENTITY' | string
  pos: [number, number, number]
  distance: number
  block?: {
    pos: [number, number, number]
    face: string
    state: string
    inside: boolean
  }
  entity?: {
    id: number
    uuid: string
    type: string
    name?: string
  }
}

// --- Baritone Automation Types ---
export interface BaritoneStatusResponse {
  installed: boolean
  activeProcess?: string
  goal?: {
    type: string
    params: Record<string, unknown>
  }
  pathing?: {
    hasPath: boolean
    isPathing: boolean
    calculating: boolean
    ticksRemaining?: number
  }
}

export interface BaritoneBlockPos {
  x: number
  y: number
  z: number
}

export interface BaritonePathResponse {
  hasPath: boolean
  currentSegment: BaritoneBlockPos[]
  nextSegments: BaritoneBlockPos[][]
}

export interface BaritoneSettingInfo {
  name: string
  type: string
  defaultValue: unknown
  value: unknown
}

export interface BaritoneJobResponse {
  jobId: string
  type: string
  status: string
  message: string
  createdAt?: string
  updatedAt?: string
}

// --- Reflection Types ---
export interface RootDescriptor {
  $handle: string
  $class: string
}

export interface RootsResponse {
  roots: Record<string, RootDescriptor>
}

export interface ObjectInspectResponse {
  $handle: string
  $class: string
  fields?: Record<string, unknown>
  methods?: Array<{
    name: string
    returnType: string
    parameterTypes: string[]
    isStatic?: boolean
  }>
  path?: string
  size?: number
  items?: unknown[]
}

export interface ClassInspectResponse {
  $class: string
  superClass?: string
  interfaces?: string[]
  staticFields?: Record<string, unknown>
  staticMethods?: Array<{
    name: string
    returnType: string
    parameterTypes: string[]
    isStatic?: boolean
  }>
}

export interface RegistryEntryResponse {
  total: number
  offset: number
  limit: number
  entries: string[]
}

