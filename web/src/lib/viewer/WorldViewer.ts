import * as THREE from 'three/webgpu'
import { getBlockModels, loadAtlasImage } from '../api'
import { BlockMaterials } from './BlockMaterials'
import { ChunkMeshManager } from './ChunkMeshManager'
import { EntityLayer } from './EntityLayer'
import { MinecraftControls } from './MinecraftControls'
import { RendererHost, type RenderStats } from './RendererHost'
import { WorldStreamClient, type WsStatus } from './WorldStreamClient'
import type { MesherRequest, MesherResponse } from './mesherTypes'
import type { PosePacket } from './protocol'
import MesherWorker from './mesher.worker?worker'

export interface ViewerStats {
  render: RenderStats
  wsStatus: WsStatus
  dimension: string
  chunksReceived: number
  megabytesReceived: number
  sections: number
  meshQueue: number
  triangles: number
  entities: number
  modelsKnown: number
  camera: [number, number, number]
  player: [number, number, number] | null
}

export interface ViewerOptions {
  radius: number
  follow: boolean
  entities: boolean
}

const MODEL_BATCH = 2048

/** Composes renderer, mesher worker, stream and controls into the dashboard's 3D world view. */
export class WorldViewer {
  readonly host: RendererHost
  private materials!: BlockMaterials
  private meshes!: ChunkMeshManager
  private entities = new EntityLayer()
  private controls!: MinecraftControls
  private stream!: WorldStreamClient
  private worker: Worker | null = null
  private options: ViewerOptions
  private pose: PosePacket | null = null
  private dimension = ''
  private modelsKnown = 0
  private needIds = new Set<number>()
  private needTimer: number | null = null
  private cameraChunk = ''
  private lastCameraPost = 0
  private timeOverride: number | null = null
  private snappedOnce = false
  private disposed = false
  private started = false

  onLockChange: (locked: boolean) => void = () => {}

  constructor(canvas: HTMLCanvasElement, options: ViewerOptions) {
    this.host = new RendererHost(canvas)
    this.options = { ...options }
  }

  async start(labelHost: HTMLElement): Promise<void> {
    await this.host.init(labelHost, () => this.disposed)
    if (this.disposed) return
    const url = await loadAtlasImage('blocks')
    if (!url) throw new Error('Block atlas unavailable')
    const atlas = await new THREE.TextureLoader().loadAsync(url)
    URL.revokeObjectURL(url)
    if (this.disposed) return

    this.materials = new BlockMaterials(atlas)
    this.meshes = new ChunkMeshManager(this.materials)
    this.host.scene.add(this.meshes.group)
    this.host.scene.add(this.entities.group)
    this.host.setViewDistance(this.options.radius * 16)

    this.controls = new MinecraftControls(this.host.camera, this.host.canvas)
    this.controls.followPlayer = this.options.follow
    this.controls.onLock(locked => this.onLockChange(locked))

    this.worker = new MesherWorker()
    this.worker.onmessage = (e: MessageEvent<MesherResponse>) => this.onWorker(e.data)

    this.stream = new WorldStreamClient({
      onStatus: () => {},
      onHello: hello => {
        this.dimension = hello.hasWorld ? hello.dimension : ''
        this.meshes.clear()
        this.entities.clear()
        this.snappedOnce = false
        this.post({ type: 'hello', hasSkyLight: hello.hasSkyLight })
      },
      onChunk: buffer => this.post({ type: 'chunk', buffer }, [buffer]),
      onUnload: (cx, cz) => this.post({ type: 'unload', cx, cz }),
      onPose: pose => this.onPose(pose),
      onEntities: list => {
        if (this.options.entities) this.entities.update(list)
      }
    }, this.viewRequest())
    this.stream.connect()

    this.host.onFrame(delta => this.frame(delta))
    this.started = true
  }

  private post(msg: MesherRequest, transfer: Transferable[] = []) {
    this.worker?.postMessage(msg, transfer)
  }

  private onWorker(msg: MesherResponse) {
    switch (msg.type) {
      case 'mesh':
        this.meshes.enqueue(msg)
        break
      case 'remove':
        this.meshes.remove(msg.keys)
        break
      case 'need':
        for (const id of msg.ids) this.needIds.add(id)
        if (this.needTimer === null) this.needTimer = window.setTimeout(() => this.fetchModels(), 30)
        break
    }
  }

  private async fetchModels() {
    this.needTimer = null
    const ids = [...this.needIds]
    this.needIds.clear()
    for (let i = 0; i < ids.length; i += MODEL_BATCH) {
      const batch = ids.slice(i, i + MODEL_BATCH)
      try {
        const res = await getBlockModels(batch)
        if (!res) throw new Error('no response')
        this.modelsKnown += res.models.length
        this.post({ type: 'models', models: res.models })
      } catch (err) {
        console.warn('Block model fetch failed', err)
        this.post({ type: 'modelsFailed', ids: batch })
      }
    }
  }

  private onPose(pose: PosePacket) {
    this.pose = pose
    if (this.options.follow || !this.snappedOnce) {
      this.controls.syncTo(pose.x, pose.y, pose.z, pose.yaw, pose.pitch)
      this.snappedOnce = true
    }
    const sky = this.timeOverride ?? pose.skyBrightness
    this.materials.skyFactor.value = sky
    this.host.setSkyBrightness(sky)
  }

  private frame(delta: number) {
    this.controls.update(delta)
    this.meshes.update()
    this.entities.hideSelf = this.options.follow
    this.entities.tick()

    const p = this.host.camera.position
    const now = performance.now()
    if (now - this.lastCameraPost > 250) {
      this.lastCameraPost = now
      this.post({ type: 'camera', x: p.x, y: p.y, z: p.z })
    }
    if (!this.options.follow) {
      const chunk = `${Math.floor(p.x) >> 4},${Math.floor(p.z) >> 4}`
      if (chunk !== this.cameraChunk) {
        this.cameraChunk = chunk
        this.stream.sendView(this.viewRequest())
      }
    }
  }

  private viewRequest() {
    const p = this.host.camera.position
    // Until the first pose places the camera, center on the player rather than wherever the camera starts.
    const follow = this.options.follow || !this.snappedOnce
    return {
      radius: this.options.radius,
      follow,
      entities: this.options.entities,
      ...(follow ? {} : { cx: Math.floor(p.x) >> 4, cz: Math.floor(p.z) >> 4 })
    }
  }

  setRadius(radius: number) {
    if (!this.started) return
    this.options.radius = radius
    this.host.setViewDistance(radius * 16)
    this.stream?.sendView(this.viewRequest())
  }

  setFollow(follow: boolean) {
    if (!this.started) return
    this.options.follow = follow
    this.controls.followPlayer = follow
    if (follow) {
      this.controls.unlock()
      if (this.pose) this.controls.syncTo(this.pose.x, this.pose.y, this.pose.z, this.pose.yaw, this.pose.pitch)
    }
    this.cameraChunk = ''
    this.stream.sendView(this.viewRequest())
  }

  setEntities(on: boolean) {
    if (!this.started) return
    this.options.entities = on
    if (!on) this.entities.clear()
    this.stream.sendView(this.viewRequest())
  }

  /** Overrides the sky brightness (0.2 night .. 1 noon); null follows the game. */
  setTimeOverride(value: number | null) {
    if (!this.started) return
    this.timeOverride = value
    const sky = value ?? this.pose?.skyBrightness ?? 1
    this.materials.skyFactor.value = sky
    this.host.setSkyBrightness(sky)
  }

  setFullbright(on: boolean) {
    if (!this.started) return
    this.materials.fullbright.value = on ? 1 : 0
  }

  setWireframe(on: boolean) {
    if (!this.started) return
    this.materials.setWireframe(on)
  }

  snapToPlayer() {
    if (this.started && this.pose) this.controls.syncTo(this.pose.x, this.pose.y, this.pose.z, this.pose.yaw, this.pose.pitch)
  }

  remesh() {
    this.post({ type: 'remeshAll' })
  }

  resize(width: number, height: number) {
    this.host.resize(width, height)
  }

  getStats(): ViewerStats {
    const p = this.host.camera.position
    return {
      render: this.host.getStats(),
      wsStatus: this.stream?.status ?? 'disconnected',
      dimension: this.dimension,
      chunksReceived: this.stream?.chunksReceived ?? 0,
      megabytesReceived: Math.round((this.stream?.bytesReceived ?? 0) / 104857.6) / 10,
      sections: this.meshes?.sectionCount ?? 0,
      meshQueue: this.meshes?.queued ?? 0,
      triangles: Math.round(this.meshes?.triangles ?? 0),
      entities: this.entities.count,
      modelsKnown: this.modelsKnown,
      camera: [Math.round(p.x * 10) / 10, Math.round(p.y * 10) / 10, Math.round(p.z * 10) / 10],
      player: this.pose ? [Math.round(this.pose.x * 10) / 10, Math.round(this.pose.y * 10) / 10, Math.round(this.pose.z * 10) / 10] : null
    }
  }

  dispose() {
    this.disposed = true
    if (this.needTimer !== null) clearTimeout(this.needTimer)
    this.stream?.disconnect()
    this.worker?.terminate()
    this.controls?.dispose()
    this.meshes?.clear()
    this.entities.dispose()
    this.materials?.dispose()
    this.host.dispose()
  }
}
