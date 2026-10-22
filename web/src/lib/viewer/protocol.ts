// Decoder for /v1/world/stream protocol v2. Must match BinaryChunkSerializer.java.

export const PROTOCOL_VERSION = 2

export const PacketType = {
  Hello: 0x00,
  Chunk: 0x01,
  Unload: 0x03,
  Pose: 0x04,
  Entities: 0x05
} as const

export interface HelloPacket {
  version: number
  hasWorld: boolean
  levelId: number
  minY: number
  height: number
  hasSkyLight: boolean
  dimension: string
}

export interface SectionPayload {
  sy: number
  /** Global block state ids */
  palette: Int32Array
  /** 4096 palette indices in y<<8 | z<<4 | x order, null when the palette has one entry */
  indices: Uint8Array | Uint16Array | null
  /** Vanilla nibble arrays (2048 bytes), null for the default value */
  sky: Uint8Array | null
  block: Uint8Array | null
}

export interface ChunkPayload {
  cx: number
  cz: number
  full: boolean
  sections: SectionPayload[]
  /** 256 RGB triplets in z<<4 | x order */
  grass: Uint8Array | null
  foliage: Uint8Array | null
  water: Uint8Array | null
}

export interface PosePacket {
  x: number
  y: number
  z: number
  yaw: number
  pitch: number
  dayTime: number
  skyBrightness: number
}

/** 0 misc, 1 player, 2 hostile, 3 passive, 4 item, 5 projectile/vehicle */
export interface EntityState {
  id: number
  category: number
  self: boolean
  x: number
  y: number
  z: number
  yaw: number
  width: number
  height: number
  type: string
  name: string
}

const decoder = new TextDecoder()

function readString(view: DataView, offset: number): [string, number] {
  const len = view.getUint16(offset)
  const bytes = new Uint8Array(view.buffer, view.byteOffset + offset + 2, len)
  return [decoder.decode(bytes), offset + 2 + len]
}

export function parseHello(view: DataView): HelloPacket {
  const version = view.getUint16(1)
  const hasWorld = view.getUint8(3) === 1
  if (!hasWorld) {
    return { version, hasWorld, levelId: 0, minY: 0, height: 0, hasSkyLight: true, dimension: '' }
  }
  const levelId = view.getInt32(4)
  const minY = view.getInt32(8)
  const height = view.getInt32(12)
  const hasSkyLight = view.getUint8(16) === 1
  const [dimension] = readString(view, 17)
  return { version, hasWorld, levelId, minY, height, hasSkyLight, dimension }
}

export function parseChunk(buffer: ArrayBuffer): ChunkPayload {
  const view = new DataView(buffer)
  let o = 1
  const cx = view.getInt32(o); o += 4
  const cz = view.getInt32(o); o += 4
  const flags = view.getUint8(o); o += 1
  const count = view.getUint16(o); o += 2
  const sections: SectionPayload[] = []
  for (let s = 0; s < count; s++) {
    const sy = view.getInt16(o); o += 2
    const sflags = view.getUint8(o); o += 1
    const paletteLen = view.getUint16(o); o += 2
    const palette = new Int32Array(paletteLen)
    for (let i = 0; i < paletteLen; i++) {
      palette[i] = view.getInt32(o); o += 4
    }
    let indices: Uint8Array | Uint16Array | null = null
    if (sflags & 1) {
      if (sflags & 8) {
        const wide = new Uint16Array(4096)
        for (let i = 0; i < 4096; i++) {
          wide[i] = view.getUint16(o); o += 2
        }
        indices = wide
      } else {
        indices = new Uint8Array(buffer, o, 4096); o += 4096
      }
    }
    let sky: Uint8Array | null = null
    if (sflags & 2) {
      sky = new Uint8Array(buffer, o, 2048); o += 2048
    }
    let block: Uint8Array | null = null
    if (sflags & 4) {
      block = new Uint8Array(buffer, o, 2048); o += 2048
    }
    sections.push({ sy, palette, indices, sky, block })
  }
  let grass: Uint8Array | null = null
  let foliage: Uint8Array | null = null
  let water: Uint8Array | null = null
  if (flags & 2) {
    grass = new Uint8Array(buffer, o, 768); o += 768
    foliage = new Uint8Array(buffer, o, 768); o += 768
    water = new Uint8Array(buffer, o, 768); o += 768
  }
  return { cx, cz, full: (flags & 1) !== 0, sections, grass, foliage, water }
}

export function parseUnload(view: DataView): { cx: number; cz: number } {
  return { cx: view.getInt32(1), cz: view.getInt32(5) }
}

export function parsePose(view: DataView): PosePacket {
  return {
    x: view.getFloat64(1),
    y: view.getFloat64(9),
    z: view.getFloat64(17),
    yaw: view.getFloat32(25),
    pitch: view.getFloat32(29),
    dayTime: Number(view.getBigInt64(33)),
    skyBrightness: view.getFloat32(41)
  }
}

export function parseEntities(view: DataView): EntityState[] {
  const count = view.getUint16(1)
  const out: EntityState[] = []
  let o = 3
  for (let i = 0; i < count; i++) {
    const id = view.getInt32(o); o += 4
    const category = view.getUint8(o); o += 1
    const self = view.getUint8(o) === 1; o += 1
    const x = view.getFloat64(o); o += 8
    const y = view.getFloat64(o); o += 8
    const z = view.getFloat64(o); o += 8
    const yaw = view.getFloat32(o); o += 4
    const width = view.getFloat32(o); o += 4
    const height = view.getFloat32(o); o += 4
    let type: string
    let name: string
    ;[type, o] = readString(view, o)
    ;[name, o] = readString(view, o)
    out.push({ id, category, self, x, y, z, yaw, width, height, type, name })
  }
  return out
}
