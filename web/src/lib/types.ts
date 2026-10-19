// Mirrors api/v1/HealthResponse.java
export interface HealthResponse {
  mcdtVersion: string
  gameVersion: string
  uptimeMs: number
  tick: number
  queue: {
    depth: number
    capacity: number
    avgTaskMicros: number
    p99TaskMicros: number
  }
  baritone: string
  permission: string
  droppedEvents: number
}

// Mirrors api/v1/dto/ClientDto.java
export interface ClientResponse {
  fps: number
  window: {
    width: number
    height: number
    guiScaledWidth: number
    guiScaledHeight: number
    guiScale: number
    fullscreen: boolean
    focused: boolean
  }
  screen: {
    open: boolean
    className?: string
    title?: string
  }
  connectionState: 'menu' | 'singleplayer' | 'multiplayer' | string
  server?: {
    address: string
    ping: number
    version: string
    motd: string
    lan: boolean
  }
}

// Mirrors api/v1/dto/PlayerDto.java
export interface PlayerResponse {
  pos: [number, number, number]
  rotation: { yaw: number; pitch: number }
  velocity: [number, number, number]
  health: number
  maxHealth: number
  absorption: number
  food: number
  saturation: number
  experience: { level: number; progress: number; total: number }
  gameMode: string
  pose: string
  onGround: boolean
  sneaking: boolean
  sprinting: boolean
  swimming: boolean
  sleeping: boolean
  air: { current: number; max: number }
  effects: Array<{
    id: string
    amplifier: number
    durationTicks: number
    ambient: boolean
    visible: boolean
  }>
  abilities: {
    flying: boolean
    mayfly: boolean
    instabuild: boolean
    invulnerable: boolean
  }
}

// Mirrors api/v1/dto/WorldDto.java
export interface WorldResponse {
  dimension: string
  time: { dayTime: number; gameTime: number; day: number; timeOfDay: number }
  weather: { raining: boolean; thundering: boolean; rainLevel: number; thunderLevel: number }
  difficulty: string
  hardcore: boolean
  spawn: { x: number; y: number; z: number }
  loadedChunks: number
  entityCount: number
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
  hitType: 'miss' | 'block' | 'entity'
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
  // a root is null while it doesn't exist, e.g. screen when no GUI is open
  roots: Record<string, RootDescriptor | null>
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

export interface PlayerSkinResponse {
  playerName?: string
  playerUuid?: string
  model: string
  textureUrl?: string
  capeUrl?: string
  elytraUrl?: string
  secure?: boolean
  imageBase64?: string
}

export interface TextureAtlasSummary {
  id: string
  width: number
  height: number
  spriteCount: number
}

export interface TextureAtlasesResponse {
  total: number
  atlases: TextureAtlasSummary[]
}

export interface TextureAtlasSprite {
  x: number
  y: number
  width: number
  height: number
  u0: number
  u1: number
  v0: number
  v1: number
  animated: boolean
  frameCount: number
}

export interface TextureAtlasResponse {
  atlasId: string
  width: number
  height: number
  spriteCount: number
  sprites: Record<string, TextureAtlasSprite>
  imageBase64?: string
}

