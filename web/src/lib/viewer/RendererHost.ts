import * as THREE from 'three/webgpu'
import { CSS2DRenderer } from 'three/addons/renderers/CSS2DRenderer.js'

export interface RenderStats {
  backend: 'WebGPU' | 'WebGL 2'
  adapterName: string
  adapterVendor: string
  fps: number
  frameTimeMs: number
  drawCalls: number
  triangles: number
  geometries: number
  textures: number
}

const DAY_SKY = new THREE.Color(0x78a7ff)
const NIGHT_SKY = new THREE.Color(0x0a0c14)

/** Owns the WebGPU renderer (with its automatic WebGL 2 fallback), scene, camera, sky and fog. */
export class RendererHost {
  readonly canvas: HTMLCanvasElement
  readonly scene = new THREE.Scene()
  readonly camera: THREE.PerspectiveCamera
  renderer!: THREE.WebGPURenderer
  labels: CSS2DRenderer | null = null
  backend: RenderStats['backend'] = 'WebGPU'
  adapterName = 'Detecting...'
  adapterVendor = 'Unknown'

  private fog = new THREE.Fog(DAY_SKY.clone(), 64, 128)
  private sky = DAY_SKY.clone()
  private lastTime = performance.now()
  private frames = 0
  private fpsWindowStart = performance.now()
  private fps = 0
  private frameTime = 0
  private callbacks: Array<(delta: number) => void> = []

  constructor(canvas: HTMLCanvasElement) {
    // Block colors are authored in Minecraft's gamma space; keep three from converting them.
    THREE.ColorManagement.enabled = false
    this.canvas = canvas
    this.scene.background = this.sky
    this.scene.fog = this.fog
    const width = canvas.clientWidth || 800
    const height = canvas.clientHeight || 600
    this.camera = new THREE.PerspectiveCamera(70, width / height, 0.05, 1000)
    this.camera.position.set(0, 80, 0)
  }

  async init(labelHost?: HTMLElement, cancelled: () => boolean = () => false): Promise<void> {
    this.renderer = new THREE.WebGPURenderer({ canvas: this.canvas, antialias: false, alpha: false, powerPreference: 'high-performance' })
    this.renderer.outputColorSpace = THREE.LinearSRGBColorSpace
    await this.renderer.init()
    if (cancelled()) return
    const backend = this.renderer.backend as unknown as { isWebGPUBackend?: boolean; gl?: WebGL2RenderingContext }
    this.backend = backend.isWebGPUBackend ? 'WebGPU' : 'WebGL 2'
    await this.detectAdapter(backend.gl)

    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.setSize(this.canvas.clientWidth || 800, this.canvas.clientHeight || 600, false)

    if (labelHost) {
      this.labels = new CSS2DRenderer()
      this.labels.domElement.style.position = 'absolute'
      this.labels.domElement.style.inset = '0'
      this.labels.domElement.style.pointerEvents = 'none'
      labelHost.appendChild(this.labels.domElement)
      this.labels.setSize(this.canvas.clientWidth || 800, this.canvas.clientHeight || 600)
    }

    if (cancelled()) return
    this.renderer.setAnimationLoop(time => this.frame(time))
  }

  private async detectAdapter(gl?: WebGL2RenderingContext) {
    if (this.backend === 'WebGPU') {
      try {
        const gpu = (navigator as Navigator & { gpu?: { requestAdapter(): Promise<{ info?: { vendor?: string; architecture?: string; device?: string; description?: string } } | null> } }).gpu
        const info = (await gpu?.requestAdapter())?.info
        this.adapterName = info?.description || info?.device || info?.architecture || 'WebGPU adapter'
        this.adapterVendor = info?.vendor || 'Unknown'
      } catch {
        this.adapterName = 'WebGPU adapter'
      }
      return
    }
    const ext = gl?.getExtension('WEBGL_debug_renderer_info')
    this.adapterName = ext && gl ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)) : 'WebGL 2 context'
    this.adapterVendor = ext && gl ? String(gl.getParameter(ext.UNMASKED_VENDOR_WEBGL)) : 'Unknown'
  }

  onFrame(cb: (delta: number) => void): () => void {
    this.callbacks.push(cb)
    return () => {
      this.callbacks = this.callbacks.filter(c => c !== cb)
    }
  }

  /** Fog starts at 60% of the streamed radius and hides the edge. */
  setViewDistance(blocks: number) {
    this.fog.near = blocks * 0.6
    this.fog.far = blocks
    this.camera.far = blocks + 64
    this.camera.updateProjectionMatrix()
  }

  /** Sky color follows the game's sky brightness (0.2 at night, 1.0 at noon). */
  setSkyBrightness(factor: number) {
    const k = Math.max(0, Math.min(1, (factor - 0.2) / 0.8))
    this.sky.copy(NIGHT_SKY).lerp(DAY_SKY, k)
    this.fog.color.copy(this.sky)
  }

  setPixelRatioCap(cap: number) {
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, cap))
    this.renderer.setSize(this.canvas.clientWidth, this.canvas.clientHeight, false)
  }

  resize(width: number, height: number) {
    if (width <= 0 || height <= 0) return
    this.camera.aspect = width / height
    this.camera.updateProjectionMatrix()
    this.renderer?.setSize(width, height, false)
    this.labels?.setSize(width, height)
  }

  private frame(time: number) {
    const delta = Math.min((time - this.lastTime) / 1000, 0.1)
    this.frameTime = time - this.lastTime
    this.lastTime = time
    this.frames++
    if (time - this.fpsWindowStart >= 500) {
      this.fps = Math.round((this.frames * 1000) / (time - this.fpsWindowStart))
      this.frames = 0
      this.fpsWindowStart = time
    }
    for (const cb of this.callbacks) cb(delta)
    this.renderer.render(this.scene, this.camera)
    this.labels?.render(this.scene, this.camera)
  }

  getStats(): RenderStats {
    const info = this.renderer?.info
    return {
      backend: this.backend,
      adapterName: this.adapterName,
      adapterVendor: this.adapterVendor,
      fps: this.fps,
      frameTimeMs: Math.round(this.frameTime * 10) / 10,
      drawCalls: info?.render.drawCalls ?? 0,
      triangles: info?.render.triangles ?? 0,
      geometries: info?.memory.geometries ?? 0,
      textures: info?.memory.textures ?? 0
    }
  }

  dispose() {
    this.renderer?.setAnimationLoop(null)
    this.callbacks = []
    this.labels?.domElement.remove()
    this.renderer?.dispose()
  }
}
