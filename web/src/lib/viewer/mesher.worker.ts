/// <reference lib="webworker" />
// Owns every streamed section and turns dirty ones into indexed, typed-array geometry for three render layers.
import { parseChunk } from './protocol'
import type { BlockModel } from '../types'
import type { MeshLayerData, MesherRequest, MesherResponse } from './mesherTypes'

declare const self: DedicatedWorkerGlobalScope

const LAYER_SOLID = 0
const LAYER_CUTOUT = 1
const LAYER_TRANSLUCENT = 2

// Minecraft 3D data order: down, up, north, south, west, east
const DIRS = [
  [0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]
]
const OPPOSITE = [1, 0, 3, 2, 5, 4]
const FACE_SHADE = [0.5, 1.0, 0.8, 0.8, 0.6, 0.6]
const NORMAL_AXIS = [1, 1, 2, 2, 0, 0]
// Unit-cube corners per face, counter-clockwise seen from outside
const FACE_CORNERS: number[][] = [
  [0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1],
  [0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0],
  [1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 0],
  [0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1],
  [0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0],
  [1, 0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1]
]
const DEFAULT_GRASS = [0x91, 0xbd, 0x59]
const DEFAULT_FOLIAGE = [0x77, 0xab, 0x2f]
const DEFAULT_WATER = [0x3f, 0x76, 0xe4]
const AIR = 0
const P = 18 // padded edge length
const SLICE_MS = 12

interface Quad {
  pos: Float32Array
  uv: Float32Array
  face: number
  cull: number
  tint: number
  r: number
  g: number
  b: number
  shade: number
  boundary: boolean
}

interface Fluid {
  water: boolean
  height: number
  layer: number
  still: number[]
  flow: number[]
}

interface Model {
  air: boolean
  blockId: number
  layer: number
  occlusion: number
  selfCull: boolean
  aoOccluder: boolean
  ao: boolean
  emission: number
  quads: Quad[]
  fluid: Fluid | null
}

interface Section {
  palette: Int32Array
  indices: Uint8Array | Uint16Array | null
  sky: Uint8Array | null
  block: Uint8Array | null
}

interface ChunkTints {
  grass: Uint8Array | null
  foliage: Uint8Array | null
  water: Uint8Array | null
}

const models = new Map<number, Model>()
const requested = new Set<number>()
const waiting = new Map<number, Set<string>>()
const sections = new Map<string, Section>()
const chunkSections = new Map<string, Set<number>>()
const tints = new Map<string, ChunkTints>()
const dirty = new Map<string, [number, number, number]>()
let hasSkyLight = true
let camX = 0
let camY = 64
let camZ = 0
let scheduled = false

const sKey = (cx: number, sy: number, cz: number) => `${cx},${sy},${cz}`
const cKey = (cx: number, cz: number) => `${cx},${cz}`

function post(msg: MesherResponse, transfer: Transferable[] = []) {
  self.postMessage(msg, transfer)
}

function layerIndex(layer: string): number {
  if (layer === 'translucent') return LAYER_TRANSLUCENT
  if (layer === 'solid') return LAYER_SOLID
  return LAYER_CUTOUT
}

function addModel(m: BlockModel) {
  const quads: Quad[] = (m.quads || []).map(q => {
    const face = q.face ?? 0
    const axis = NORMAL_AXIS[face]
    const plane = DIRS[face][axis] > 0 ? 1 : 0
    let boundary = true
    for (let i = 0; i < 4; i++) {
      if (Math.abs(q.pos[i * 3 + axis] - plane) > 1e-4) boundary = false
    }
    const color = q.tint === 4 ? (q.color ?? 0xffffff) : 0xffffff
    return {
      pos: Float32Array.from(q.pos),
      uv: Float32Array.from(q.uv),
      face,
      cull: q.cull ?? 0,
      tint: q.tint ?? 0,
      r: (color >> 16) & 255,
      g: (color >> 8) & 255,
      b: color & 255,
      shade: q.shade ? FACE_SHADE[face] : 1,
      boundary
    }
  })
  models.set(m.id, {
    air: m.air,
    blockId: m.blockId,
    layer: layerIndex(m.layer),
    occlusion: m.occlusion ?? 0,
    selfCull: !!m.selfCull,
    aoOccluder: !!m.aoOccluder,
    ao: !!m.ao,
    emission: m.emission ?? 0,
    quads,
    fluid: m.fluid
      ? {
          water: m.fluid.type === 'water',
          height: m.fluid.falling ? 1 : m.fluid.height,
          layer: layerIndex(m.fluid.layer),
          still: m.fluid.still,
          flow: m.fluid.flow
        }
      : null
  })
}

function markDirty(cx: number, sy: number, cz: number) {
  const key = sKey(cx, sy, cz)
  if (sections.has(key)) dirty.set(key, [cx, sy, cz])
}

function markWithNeighbors(cx: number, sy: number, cz: number) {
  markDirty(cx, sy, cz)
  for (const d of DIRS) markDirty(cx + d[0], sy + d[1], cz + d[2])
}

function removeSection(cx: number, sy: number, cz: number, removed: string[]) {
  const key = sKey(cx, sy, cz)
  sections.delete(key)
  dirty.delete(key)
  removed.push(key)
}

function handleChunk(buffer: ArrayBuffer) {
  const chunk = parseChunk(buffer)
  const ck = cKey(chunk.cx, chunk.cz)
  let present = chunkSections.get(ck)
  if (!present) {
    present = new Set()
    chunkSections.set(ck, present)
  }
  const removed: string[] = []
  if (chunk.full) {
    const incoming = new Set(chunk.sections.map(s => s.sy))
    for (const sy of [...present]) {
      if (!incoming.has(sy)) {
        removeSection(chunk.cx, sy, chunk.cz, removed)
        present.delete(sy)
        markWithNeighbors(chunk.cx, sy, chunk.cz)
      }
    }
    tints.set(ck, { grass: chunk.grass, foliage: chunk.foliage, water: chunk.water })
    for (let dz = -1; dz <= 1; dz++)
      for (let dx = -1; dx <= 1; dx++)
        blended.delete(cKey(chunk.cx + dx, chunk.cz + dz))
  }
  for (const s of chunk.sections) {
    sections.set(sKey(chunk.cx, s.sy, chunk.cz), { palette: s.palette, indices: s.indices, sky: s.sky, block: s.block })
    present.add(s.sy)
    markWithNeighbors(chunk.cx, s.sy, chunk.cz)
  }
  if (removed.length) post({ type: 'remove', keys: removed })
}

function handleUnload(cx: number, cz: number) {
  const ck = cKey(cx, cz)
  const present = chunkSections.get(ck)
  if (!present) return
  const removed: string[] = []
  for (const sy of present) removeSection(cx, sy, cz, removed)
  chunkSections.delete(ck)
  tints.delete(ck)
  for (let dz = -1; dz <= 1; dz++)
    for (let dx = -1; dx <= 1; dx++)
      blended.delete(cKey(cx + dx, cz + dz))
  for (const sy of present) {
    markDirty(cx - 1, sy, cz)
    markDirty(cx + 1, sy, cz)
    markDirty(cx, sy, cz - 1)
    markDirty(cx, sy, cz + 1)
  }
  if (removed.length) post({ type: 'remove', keys: removed })
}

function reset() {
  const keys = [...sections.keys()]
  sections.clear()
  chunkSections.clear()
  tints.clear()
  blended.clear()
  dirty.clear()
  waiting.clear()
  if (keys.length) post({ type: 'remove', keys })
}

// --- Geometry building ---

class LayerBuilder {
  pos = new Float32Array(4096 * 3)
  uv = new Float32Array(4096 * 2)
  tint = new Uint8Array(4096 * 4)
  light = new Uint8Array(4096 * 4)
  index = new Uint32Array(4096 * 1.5)
  vertices = 0
  indices = 0

  reset() {
    this.vertices = 0
    this.indices = 0
  }

  private grow() {
    const grow = <T extends Float32Array | Uint8Array | Uint32Array>(a: T): T => {
      const next = new (a.constructor as new (n: number) => T)(a.length * 2)
      next.set(a)
      return next
    }
    this.pos = grow(this.pos)
    this.uv = grow(this.uv)
    this.tint = grow(this.tint)
    this.light = grow(this.light)
    this.index = grow(this.index)
  }

  vertex(x: number, y: number, z: number, u: number, v: number, r: number, g: number, b: number, sky: number, blk: number) {
    if ((this.vertices + 1) * 3 > this.pos.length) this.grow()
    const i = this.vertices
    this.pos[i * 3] = x
    this.pos[i * 3 + 1] = y
    this.pos[i * 3 + 2] = z
    this.uv[i * 2] = u
    this.uv[i * 2 + 1] = v
    this.tint[i * 4] = r
    this.tint[i * 4 + 1] = g
    this.tint[i * 4 + 2] = b
    this.tint[i * 4 + 3] = 255
    this.light[i * 4] = sky * 17
    this.light[i * 4 + 1] = blk * 17
    this.vertices++
  }

  /** Two triangles over the last four vertices; flips the diagonal so AO interpolates evenly. */
  quad(flip: boolean) {
    if (this.indices + 6 > this.index.length) this.grow()
    const b = this.vertices - 4
    const idx = this.index
    let n = this.indices
    if (flip) {
      idx[n++] = b + 1; idx[n++] = b + 2; idx[n++] = b + 3
      idx[n++] = b + 1; idx[n++] = b + 3; idx[n++] = b
    } else {
      idx[n++] = b; idx[n++] = b + 1; idx[n++] = b + 2
      idx[n++] = b; idx[n++] = b + 2; idx[n++] = b + 3
    }
    this.indices = n
  }

  finish(transfer: Transferable[]): MeshLayerData | null {
    if (this.indices === 0) return null
    const v = this.vertices
    const data: MeshLayerData = {
      pos: this.pos.slice(0, v * 3),
      uv: this.uv.slice(0, v * 2),
      tint: this.tint.slice(0, v * 4),
      light: this.light.slice(0, v * 4),
      index: v < 65536 ? Uint16Array.from(this.index.subarray(0, this.indices)) : this.index.slice(0, this.indices)
    }
    transfer.push(data.pos.buffer, data.uv.buffer, data.tint.buffer, data.light.buffer, data.index.buffer)
    return data
  }
}

const builders = [new LayerBuilder(), new LayerBuilder(), new LayerBuilder()]
const padState = new Int32Array(P * P * P)
const padSky = new Uint8Array(P * P * P)
const padBlock = new Uint8Array(P * P * P)
const pidx = (x: number, y: number, z: number) => ((y + 1) * P + (z + 1)) * P + (x + 1)

function nibble(arr: Uint8Array, i: number): number {
  const b = arr[i >> 1]
  return i & 1 ? b >> 4 : b & 15
}

/** Fills the 18³ padded arrays from the section and its 26 neighbors. Returns ids with no model yet. */
function gather(cx: number, sy: number, cz: number): Set<number> {
  const unknown = new Set<number>()
  const near: (Section | undefined)[] = new Array(27)
  for (let dy = -1; dy <= 1; dy++)
    for (let dz = -1; dz <= 1; dz++)
      for (let dx = -1; dx <= 1; dx++)
        near[(dy + 1) * 9 + (dz + 1) * 3 + (dx + 1)] = sections.get(sKey(cx + dx, sy + dy, cz + dz))
  const defaultSky = hasSkyLight ? 15 : 0
  for (let y = -1; y <= 16; y++) {
    const dy = y < 0 ? -1 : y > 15 ? 1 : 0
    const ly = y & 15
    for (let z = -1; z <= 16; z++) {
      const dz = z < 0 ? -1 : z > 15 ? 1 : 0
      const lz = z & 15
      for (let x = -1; x <= 16; x++) {
        const dx = x < 0 ? -1 : x > 15 ? 1 : 0
        const lx = x & 15
        const p = pidx(x, y, z)
        const sec = near[(dy + 1) * 9 + (dz + 1) * 3 + (dx + 1)]
        if (!sec) {
          padState[p] = AIR
          padSky[p] = defaultSky
          padBlock[p] = 0
          continue
        }
        const i = (ly << 8) | (lz << 4) | lx
        const id = sec.indices ? sec.palette[sec.indices[i]] : sec.palette[0]
        padState[p] = id
        if (!models.has(id)) unknown.add(id)
        padSky[p] = sec.sky ? nibble(sec.sky, i) : defaultSky
        padBlock[p] = sec.block ? nibble(sec.block, i) : 0
      }
    }
  }
  return unknown
}

function modelAt(p: number): Model | undefined {
  return models.get(padState[p])
}

function isOccluder(p: number): boolean {
  const m = modelAt(p)
  return !!m && m.aoOccluder
}

// Scratch for per-vertex smooth lighting
const vAo = new Float32Array(4)
const vSky = new Float32Array(4)
const vBlk = new Float32Array(4)

function smoothVertex(i: number, plane: [number, number, number], axisA: number, sA: number, axisB: number, sB: number) {
  const c0 = pidx(plane[0], plane[1], plane[2])
  const a = [plane[0], plane[1], plane[2]]
  a[axisA] += sA
  const c1 = pidx(a[0], a[1], a[2])
  const b = [plane[0], plane[1], plane[2]]
  b[axisB] += sB
  const c2 = pidx(b[0], b[1], b[2])
  b[axisA] += sA
  const c3 = pidx(b[0], b[1], b[2])
  const o1 = isOccluder(c1)
  const o2 = isOccluder(c2)
  const o3 = (o1 && o2) || isOccluder(c3)
  const o0 = isOccluder(c0)
  vAo[i] = ((o0 ? 0.2 : 1) + (o1 ? 0.2 : 1) + (o2 ? 0.2 : 1) + (o3 ? 0.2 : 1)) / 4
  const s0 = padSky[c0]
  const b0 = padBlock[c0]
  const pick = (occ: boolean, v: number, center: number) => (occ || v === 0 ? center : v)
  vSky[i] = (s0 + pick(o1, padSky[c1], s0) + pick(o2, padSky[c2], s0) + pick(o3, padSky[c3], s0)) / 4
  vBlk[i] = (b0 + pick(o1, padBlock[c1], b0) + pick(o2, padBlock[c2], b0) + pick(o3, padBlock[c3], b0)) / 4
}

function tintColor(tint: number, t: ChunkTints | undefined, col: number): [number, number, number] {
  let arr: Uint8Array | null | undefined
  let def: number[]
  if (tint === 1) { arr = t?.grass; def = DEFAULT_GRASS }
  else if (tint === 2) { arr = t?.foliage; def = DEFAULT_FOLIAGE }
  else { arr = t?.water; def = DEFAULT_WATER }
  if (!arr) return [def[0], def[1], def[2]]
  return [arr[col * 3], arr[col * 3 + 1], arr[col * 3 + 2]]
}

const BLEND_RADIUS = 2
const blended = new Map<string, ChunkTints>()

/** Box-blurs the raw 4×4 biome colors across chunk borders, like the game's default biome blend (5×5). */
function blendedTints(cx: number, cz: number): ChunkTints | undefined {
  const key = cKey(cx, cz)
  const cached = blended.get(key)
  if (cached) return cached
  const own = tints.get(key)
  if (!own?.grass) return own
  const near: (ChunkTints | undefined)[] = []
  for (let dz = -1; dz <= 1; dz++)
    for (let dx = -1; dx <= 1; dx++)
      near.push(tints.get(cKey(cx + dx, cz + dz)))
  const out: ChunkTints = { grass: new Uint8Array(768), foliage: new Uint8Array(768), water: new Uint8Array(768) }
  const channels: ('grass' | 'foliage' | 'water')[] = ['grass', 'foliage', 'water']
  for (let z = 0; z < 16; z++) {
    for (let x = 0; x < 16; x++) {
      for (const ch of channels) {
        let r = 0
        let g = 0
        let b = 0
        let n = 0
        for (let oz = -BLEND_RADIUS; oz <= BLEND_RADIUS; oz++) {
          for (let ox = -BLEND_RADIUS; ox <= BLEND_RADIUS; ox++) {
            let sx = x + ox
            let sz = z + oz
            const nx = sx < 0 ? 0 : sx > 15 ? 2 : 1
            const nz = sz < 0 ? 0 : sz > 15 ? 2 : 1
            const src = near[nz * 3 + nx]?.[ch]
            if (!src) continue
            sx &= 15
            sz &= 15
            const i = ((sz << 4) | sx) * 3
            r += src[i]
            g += src[i + 1]
            b += src[i + 2]
            n++
          }
        }
        const o = ((z << 4) | x) * 3
        out[ch]![o] = r / n
        out[ch]![o + 1] = g / n
        out[ch]![o + 2] = b / n
      }
    }
  }
  blended.set(key, out)
  return out
}

function emitModel(m: Model, x: number, y: number, z: number, t: ChunkTints | undefined) {
  const here = pidx(x, y, z)
  const col = (z << 4) | x
  for (const q of m.quads) {
    if (q.cull >= 0) {
      const d = DIRS[q.cull]
      const n = modelAt(pidx(x + d[0], y + d[1], z + d[2]))
      if (n && !n.air) {
        if ((n.occlusion >> OPPOSITE[q.cull]) & 1) continue
        if (m.selfCull && n.blockId === m.blockId) continue
      }
    }
    let r = q.r
    let g = q.g
    let b = q.b
    if (q.tint >= 1 && q.tint <= 3) {
      ;[r, g, b] = tintColor(q.tint, t, col)
    }
    const d = DIRS[q.face]
    const plane: [number, number, number] = q.boundary ? [x + d[0], y + d[1], z + d[2]] : [x, y, z]
    const axis = NORMAL_AXIS[q.face]
    const axisA = axis === 0 ? 1 : 0
    const axisB = axis === 2 ? 1 : 2
    if (m.ao) {
      for (let i = 0; i < 4; i++) {
        const sA = q.pos[i * 3 + axisA] < 0.5 ? -1 : 1
        const sB = q.pos[i * 3 + axisB] < 0.5 ? -1 : 1
        smoothVertex(i, plane, axisA, sA, axisB, sB)
      }
    } else {
      const p = pidx(plane[0], plane[1], plane[2])
      const src = isOccluder(p) ? here : p
      for (let i = 0; i < 4; i++) {
        vAo[i] = 1
        vSky[i] = padSky[src]
        vBlk[i] = padBlock[src]
      }
    }
    const builder = builders[m.layer]
    for (let i = 0; i < 4; i++) {
      const k = q.shade * vAo[i]
      builder.vertex(
        x + q.pos[i * 3] - 8, y + q.pos[i * 3 + 1] - 8, z + q.pos[i * 3 + 2] - 8,
        q.uv[i * 2], q.uv[i * 2 + 1],
        r * k, g * k, b * k,
        vSky[i], Math.max(vBlk[i], m.emission)
      )
    }
    builder.quad(vAo[0] + vAo[2] < vAo[1] + vAo[3])
  }
}

function fluidAt(p: number, water: boolean): Fluid | null {
  const f = modelAt(p)?.fluid
  return f && f.water === water ? f : null
}

function cornerHeight(x: number, y: number, z: number, cxs: number[], czs: number[], water: boolean): number {
  let total = 0
  let weight = 0
  for (const dx of cxs) {
    for (const dz of czs) {
      const p = pidx(x + dx, y, z + dz)
      if (fluidAt(pidx(x + dx, y + 1, z + dz), water)) return 1
      const f = fluidAt(p, water)
      if (f) {
        if (f.height >= 0.8) {
          total += f.height * 10
          weight += 10
        } else {
          total += f.height
          weight += 1
        }
      } else if ((modelAt(p)?.occlusion ?? 0) === 0) {
        weight += 1
      }
    }
  }
  return weight ? total / weight : 0
}

function emitFluid(m: Model, x: number, y: number, z: number, t: ChunkTints | undefined) {
  const f = m.fluid!
  const water = f.water
  const [r, g, b] = water ? tintColor(3, t, (z << 4) | x) : [255, 255, 255]
  const emission = water ? 0 : 15
  // Corner heights indexed by corner x/z (0 or 1)
  const h = [
    [cornerHeight(x, y, z, [-1, 0], [-1, 0], water), cornerHeight(x, y, z, [-1, 0], [0, 1], water)],
    [cornerHeight(x, y, z, [0, 1], [-1, 0], water), cornerHeight(x, y, z, [0, 1], [0, 1], water)]
  ]
  const builder = builders[f.layer]
  for (let face = 0; face < 6; face++) {
    const d = DIRS[face]
    const np = pidx(x + d[0], y + d[1], z + d[2])
    if (fluidAt(np, water)) continue
    const n = modelAt(np)
    if (n && ((n.occlusion >> OPPOSITE[face]) & 1)) {
      if (face !== 1 || h[0][0] + h[0][1] + h[1][0] + h[1][1] >= 4) continue
    }
    const corners = FACE_CORNERS[face]
    const sprite = face <= 1 ? f.still : f.flow
    const du = sprite[2] - sprite[0]
    const dv = sprite[3] - sprite[1]
    const src = face === 1 || !n || n.occlusion === 0 ? np : pidx(x, y, z)
    const sky = Math.max(padSky[src], padSky[pidx(x, y, z)])
    const blk = Math.max(padBlock[src], padBlock[pidx(x, y, z)], emission)
    const shade = FACE_SHADE[face]
    for (let i = 0; i < 4; i++) {
      const cx = corners[i * 3]
      const cy = corners[i * 3 + 1]
      const cz = corners[i * 3 + 2]
      const vy = cy === 1 ? h[cx][cz] - (face === 1 ? 0.001 : 0) : 0
      let u: number
      let v: number
      if (face <= 1) {
        u = sprite[0] + cx * du
        v = sprite[1] + cz * dv
      } else {
        const along = face === 2 || face === 3 ? cx : cz
        u = sprite[0] + along * du * 0.5
        v = sprite[1] + (1 - vy) * dv * 0.5
      }
      builder.vertex(x + cx - 8, y + vy - 8, z + cz - 8, u, v, r * shade, g * shade, b * shade, sky, blk)
    }
    builder.quad(false)
  }
}

function meshSection(cx: number, sy: number, cz: number) {
  const key = sKey(cx, sy, cz)
  const unknown = gather(cx, sy, cz)
  if (unknown.size) {
    for (const id of unknown) {
      let set = waiting.get(id)
      if (!set) {
        set = new Set()
        waiting.set(id, set)
      }
      set.add(key)
    }
    const own = sections.get(key)!
    for (const id of own.palette) {
      if (!models.has(id)) return
    }
  }

  for (const builder of builders) builder.reset()
  const t = blendedTints(cx, cz)
  for (let y = 0; y < 16; y++) {
    for (let z = 0; z < 16; z++) {
      for (let x = 0; x < 16; x++) {
        const m = modelAt(pidx(x, y, z))
        if (!m || m.air) continue
        if (m.quads.length) emitModel(m, x, y, z, t)
        if (m.fluid) emitFluid(m, x, y, z, t)
      }
    }
  }
  const transfer: Transferable[] = []
  const layers = builders.map(b => b.finish(transfer))
  post({ type: 'mesh', key, cx, sy, cz, layers }, transfer)
}

function requestUnknown() {
  const need: number[] = []
  for (const id of waiting.keys()) {
    if (!requested.has(id)) {
      requested.add(id)
      need.push(id)
    }
  }
  if (need.length) post({ type: 'need', ids: need })
}

function process() {
  scheduled = false
  if (!dirty.size) return
  const start = performance.now()
  const order = [...dirty.values()].sort((a, b) => dist(a) - dist(b))
  for (const [cx, sy, cz] of order) {
    const key = sKey(cx, sy, cz)
    dirty.delete(key)
    if (sections.has(key)) meshSection(cx, sy, cz)
    if (performance.now() - start > SLICE_MS) break
  }
  requestUnknown()
  schedule()
}

function dist([cx, sy, cz]: [number, number, number]): number {
  const dx = cx * 16 + 8 - camX
  const dy = sy * 16 + 8 - camY
  const dz = cz * 16 + 8 - camZ
  return dx * dx + dy * dy * 4 + dz * dz
}

function schedule() {
  if (!scheduled && dirty.size) {
    scheduled = true
    setTimeout(process, 0)
  }
}

self.onmessage = (e: MessageEvent<MesherRequest>) => {
  const msg = e.data
  switch (msg.type) {
    case 'hello':
      hasSkyLight = msg.hasSkyLight
      reset()
      break
    case 'chunk':
      handleChunk(msg.buffer)
      break
    case 'unload':
      handleUnload(msg.cx, msg.cz)
      break
    case 'models':
      for (const m of msg.models) {
        addModel(m)
        const keys = waiting.get(m.id)
        if (keys) {
          for (const key of keys) {
            const [cx, sy, cz] = key.split(',').map(Number)
            markDirty(cx, sy, cz)
          }
          waiting.delete(m.id)
        }
      }
      break
    case 'modelsFailed':
      for (const id of msg.ids) requested.delete(id)
      break
    case 'camera':
      camX = msg.x
      camY = msg.y
      camZ = msg.z
      break
    case 'remeshAll':
      for (const key of sections.keys()) {
        const [cx, sy, cz] = key.split(',').map(Number)
        dirty.set(key, [cx, sy, cz])
      }
      break
  }
  schedule()
}
