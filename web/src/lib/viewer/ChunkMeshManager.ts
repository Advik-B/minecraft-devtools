import * as THREE from 'three/webgpu'
import type { BlockMaterials } from './BlockMaterials'
import type { MeshLayerData, MesherResponse } from './mesherTypes'

type MeshMessage = Extract<MesherResponse, { type: 'mesh' }>

const UPLOAD_BUDGET_MS = 4
// Section geometry is centered, so a 16³ cube plus model overhang fits in this sphere.
const BOUNDS = new THREE.Sphere(new THREE.Vector3(), 15)

/** Turns worker results into scene meshes under a per-frame upload budget. */
export class ChunkMeshManager {
  readonly group = new THREE.Group()
  private meshes = new Map<string, (THREE.Mesh | null)[]>()
  private pending = new Map<string, MeshMessage>()
  private materials: THREE.Material[]

  triangles = 0

  constructor(materials: BlockMaterials) {
    this.materials = materials.all
    this.group.matrixAutoUpdate = false
  }

  get sectionCount(): number {
    return this.meshes.size
  }

  get queued(): number {
    return this.pending.size
  }

  enqueue(msg: MeshMessage) {
    // A newer mesh for the same section supersedes one that has not been uploaded yet.
    this.pending.delete(msg.key)
    this.pending.set(msg.key, msg)
  }

  remove(keys: string[]) {
    for (const key of keys) {
      this.pending.delete(key)
      this.dispose(key)
    }
  }

  clear() {
    this.pending.clear()
    for (const key of [...this.meshes.keys()]) this.dispose(key)
  }

  /** Uploads queued meshes until the frame budget is spent. */
  update() {
    if (!this.pending.size) return
    const start = performance.now()
    for (const [key, msg] of this.pending) {
      this.pending.delete(key)
      this.apply(msg)
      if (performance.now() - start > UPLOAD_BUDGET_MS) break
    }
  }

  private apply(msg: MeshMessage) {
    this.dispose(msg.key)
    const slots: (THREE.Mesh | null)[] = []
    let any = false
    for (let layer = 0; layer < 3; layer++) {
      const data = msg.layers[layer]
      if (!data) {
        slots.push(null)
        continue
      }
      const mesh = new THREE.Mesh(this.geometry(data), this.materials[layer])
      mesh.position.set(msg.cx * 16 + 8, msg.sy * 16 + 8, msg.cz * 16 + 8)
      mesh.matrixAutoUpdate = false
      mesh.updateMatrix()
      mesh.renderOrder = layer
      this.group.add(mesh)
      this.triangles += data.index.length / 3
      slots.push(mesh)
      any = true
    }
    if (any) this.meshes.set(msg.key, slots)
  }

  private geometry(data: MeshLayerData): THREE.BufferGeometry {
    const geo = new THREE.BufferGeometry()
    geo.setAttribute('position', new THREE.BufferAttribute(data.pos, 3))
    geo.setAttribute('uv', new THREE.BufferAttribute(data.uv, 2))
    geo.setAttribute('aTint', new THREE.BufferAttribute(data.tint, 4, true))
    geo.setAttribute('aLight', new THREE.BufferAttribute(data.light, 4, true))
    geo.setIndex(new THREE.BufferAttribute(data.index, 1))
    geo.boundingSphere = BOUNDS.clone()
    geo.boundingBox = new THREE.Box3(new THREE.Vector3(-8, -8, -8), new THREE.Vector3(8, 8, 8))
    return geo
  }

  private dispose(key: string) {
    const slots = this.meshes.get(key)
    if (!slots) return
    for (const mesh of slots) {
      if (!mesh) continue
      this.group.remove(mesh)
      this.triangles -= (mesh.geometry.index?.count ?? 0) / 3
      mesh.geometry.dispose()
    }
    this.meshes.delete(key)
  }
}
