import * as THREE from 'three/webgpu'
import { CSS2DObject } from 'three/addons/renderers/CSS2DRenderer.js'
import type { EntityState } from './protocol'

const MAX_ENTITIES = 512
const INTERP_MS = 200
// misc, player, hostile, passive, item, projectile/vehicle
const CATEGORY_COLORS = [0xb0b0b0, 0x3fd0ff, 0xff4d4d, 0x7ddc5a, 0xffd23f, 0xc792ea]

interface Tracked {
  state: EntityState
  fromX: number
  fromY: number
  fromZ: number
  fromYaw: number
  at: number
  label: CSS2DObject | null
}

/** Draws streamed entities as instanced boxes (sized from their bounding box) with name tags for players. */
export class EntityLayer {
  readonly group = new THREE.Group()
  private boxes: THREE.InstancedMesh
  private tracked = new Map<number, Tracked>()
  private matrix = new THREE.Matrix4()
  private quat = new THREE.Quaternion()
  private euler = new THREE.Euler()
  private scale = new THREE.Vector3()
  private pos = new THREE.Vector3()
  private color = new THREE.Color()
  hideSelf = false

  constructor() {
    const geo = new THREE.BoxGeometry(1, 1, 1)
    geo.translate(0, 0.5, 0)
    const mat = new THREE.MeshBasicNodeMaterial({ transparent: true, opacity: 0.75, depthWrite: false })
    this.boxes = new THREE.InstancedMesh(geo, mat, MAX_ENTITIES)
    // Allocate colors up front: a node material compiled without instanceColor never picks it up later.
    this.boxes.instanceColor = new THREE.InstancedBufferAttribute(new Float32Array(MAX_ENTITIES * 3), 3)
    this.boxes.count = 0
    this.boxes.frustumCulled = false
    this.group.add(this.boxes)
  }

  get count(): number {
    return this.tracked.size
  }

  update(list: EntityState[]) {
    const now = performance.now()
    const seen = new Set<number>()
    for (const e of list) {
      seen.add(e.id)
      const prev = this.tracked.get(e.id)
      if (prev) {
        const [x, y, z, yaw] = this.interpolated(prev, now)
        prev.fromX = x
        prev.fromY = y
        prev.fromZ = z
        prev.fromYaw = yaw
        prev.state = e
        prev.at = now
      } else {
        this.tracked.set(e.id, {
          state: e, fromX: e.x, fromY: e.y, fromZ: e.z, fromYaw: e.yaw, at: now, label: this.makeLabel(e)
        })
      }
    }
    for (const [id, t] of this.tracked) {
      if (!seen.has(id)) {
        if (t.label) this.group.remove(t.label)
        t.label?.element.remove()
        this.tracked.delete(id)
      }
    }
  }

  clear() {
    this.update([])
  }

  /** Per frame: interpolate toward the latest snapshot and write instance matrices. */
  tick() {
    const now = performance.now()
    let i = 0
    for (const t of this.tracked.values()) {
      if (i >= MAX_ENTITIES) break
      const visible = !(this.hideSelf && t.state.self)
      if (t.label) t.label.visible = visible
      if (!visible) continue
      const [x, y, z, yaw] = this.interpolated(t, now)
      const e = t.state
      this.pos.set(x, y, z)
      this.euler.set(0, THREE.MathUtils.degToRad(-yaw), 0)
      this.quat.setFromEuler(this.euler)
      this.scale.set(Math.max(e.width, 0.1), Math.max(e.height, 0.1), Math.max(e.width, 0.1))
      this.matrix.compose(this.pos, this.quat, this.scale)
      this.boxes.setMatrixAt(i, this.matrix)
      this.color.setHex(e.self ? 0xffffff : CATEGORY_COLORS[e.category] ?? CATEGORY_COLORS[0])
      this.boxes.setColorAt(i, this.color)
      if (t.label) t.label.position.set(x, y + e.height + 0.35, z)
      i++
    }
    this.boxes.count = i
    this.boxes.instanceMatrix.needsUpdate = true
    if (this.boxes.instanceColor) this.boxes.instanceColor.needsUpdate = true
  }

  dispose() {
    this.clear()
    this.boxes.geometry.dispose()
    ;(this.boxes.material as THREE.Material).dispose()
  }

  private interpolated(t: Tracked, now: number): [number, number, number, number] {
    const k = Math.min(1, (now - t.at) / INTERP_MS)
    const e = t.state
    let dyaw = e.yaw - t.fromYaw
    dyaw = ((dyaw + 540) % 360) - 180
    return [
      t.fromX + (e.x - t.fromX) * k,
      t.fromY + (e.y - t.fromY) * k,
      t.fromZ + (e.z - t.fromZ) * k,
      t.fromYaw + dyaw * k
    ]
  }

  private makeLabel(e: EntityState): CSS2DObject | null {
    if (e.category !== 1 || !e.name) return null
    const el = document.createElement('div')
    el.textContent = e.name
    el.className = 'px-1.5 py-0.5 rounded bg-black/60 text-white text-[11px] font-mono whitespace-nowrap pointer-events-none'
    const label = new CSS2DObject(el)
    this.group.add(label)
    return label
  }
}
