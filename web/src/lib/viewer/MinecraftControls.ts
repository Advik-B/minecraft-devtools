import * as THREE from 'three/webgpu'

/** Pointer-lock fly camera (WASD, Space/Shift, Ctrl to sprint, wheel for speed) that can also mirror the player. */
export class MinecraftControls {
  camera: THREE.PerspectiveCamera
  domElement: HTMLElement
  isLocked = false
  followPlayer = false

  /** Blocks per second */
  speed = 12
  sprintMultiplier = 3

  private yaw = 0
  private pitch = 0
  private keys = new Set<string>()
  private forward = new THREE.Vector3()
  private right = new THREE.Vector3()
  private move = new THREE.Vector3()
  private euler = new THREE.Euler(0, 0, 0, 'YXZ')
  private lockCallbacks: Array<(locked: boolean) => void> = []

  constructor(camera: THREE.PerspectiveCamera, domElement: HTMLElement) {
    this.camera = camera
    this.domElement = domElement
    this.onClick = this.onClick.bind(this)
    this.onMouseMove = this.onMouseMove.bind(this)
    this.onKeyDown = this.onKeyDown.bind(this)
    this.onKeyUp = this.onKeyUp.bind(this)
    this.onBlur = this.onBlur.bind(this)
    this.onWheel = this.onWheel.bind(this)
    this.onPointerLockChange = this.onPointerLockChange.bind(this)

    domElement.addEventListener('click', this.onClick)
    domElement.addEventListener('wheel', this.onWheel, { passive: false })
    document.addEventListener('pointerlockchange', this.onPointerLockChange)
    window.addEventListener('keydown', this.onKeyDown)
    window.addEventListener('keyup', this.onKeyUp)
    window.addEventListener('blur', this.onBlur)
  }

  onLock(cb: (locked: boolean) => void): () => void {
    this.lockCallbacks.push(cb)
    return () => {
      this.lockCallbacks = this.lockCallbacks.filter(c => c !== cb)
    }
  }

  unlock() {
    if (document.pointerLockElement === this.domElement) document.exitPointerLock?.()
  }

  private onClick() {
    if (!this.isLocked && !this.followPlayer) this.domElement.requestPointerLock?.()
  }

  private onPointerLockChange() {
    this.isLocked = document.pointerLockElement === this.domElement
    if (this.isLocked) {
      window.addEventListener('mousemove', this.onMouseMove)
    } else {
      window.removeEventListener('mousemove', this.onMouseMove)
      this.keys.clear()
    }
    for (const cb of this.lockCallbacks) cb(this.isLocked)
  }

  private onMouseMove(e: MouseEvent) {
    if (!this.isLocked || this.followPlayer) return
    const sensitivity = 0.0022
    this.yaw -= e.movementX * sensitivity
    this.pitch -= e.movementY * sensitivity
    const max = Math.PI / 2 - 0.01
    this.pitch = Math.max(-max, Math.min(max, this.pitch))
    this.applyRotation()
  }

  private onWheel(e: WheelEvent) {
    if (!this.isLocked) return
    e.preventDefault()
    this.speed = Math.max(2, Math.min(200, this.speed * (e.deltaY < 0 ? 1.2 : 1 / 1.2)))
  }

  private onKeyDown(e: KeyboardEvent) {
    if (!this.isLocked) return
    this.keys.add(e.code)
    if (e.code === 'Space') e.preventDefault()
  }

  private onKeyUp(e: KeyboardEvent) {
    this.keys.delete(e.code)
  }

  private onBlur() {
    this.keys.clear()
  }

  private applyRotation() {
    this.euler.set(this.pitch, this.yaw, 0)
    this.camera.quaternion.setFromEuler(this.euler)
  }

  update(delta: number) {
    if (this.followPlayer || !this.isLocked) return
    const sprint = this.keys.has('ControlLeft') || this.keys.has('ControlRight')
    const dist = this.speed * (sprint ? this.sprintMultiplier : 1) * delta
    this.move.set(0, 0, 0)
    if (this.keys.has('KeyW')) this.move.z -= 1
    if (this.keys.has('KeyS')) this.move.z += 1
    if (this.keys.has('KeyA')) this.move.x -= 1
    if (this.keys.has('KeyD')) this.move.x += 1
    if (this.move.lengthSq() > 0) {
      this.move.normalize()
      this.camera.getWorldDirection(this.forward)
      this.forward.y = 0
      this.forward.normalize()
      this.right.crossVectors(this.forward, this.camera.up).normalize()
      this.camera.position.addScaledVector(this.forward, -this.move.z * dist)
      this.camera.position.addScaledVector(this.right, this.move.x * dist)
    }
    let vertical = 0
    if (this.keys.has('Space')) vertical += 1
    if (this.keys.has('ShiftLeft') || this.keys.has('ShiftRight')) vertical -= 1
    this.camera.position.y += vertical * dist
  }

  /** Moves the camera to a Minecraft pose (feet position, yaw/pitch in degrees). */
  syncTo(x: number, y: number, z: number, yawDeg: number, pitchDeg: number) {
    this.camera.position.set(x, y + 1.62, z)
    // Minecraft yaw 0 faces +Z; three's camera faces -Z at yaw 0.
    this.yaw = THREE.MathUtils.degToRad(180 - yawDeg)
    this.pitch = THREE.MathUtils.degToRad(-pitchDeg)
    this.applyRotation()
  }

  dispose() {
    this.unlock()
    this.domElement.removeEventListener('click', this.onClick)
    this.domElement.removeEventListener('wheel', this.onWheel)
    document.removeEventListener('pointerlockchange', this.onPointerLockChange)
    window.removeEventListener('mousemove', this.onMouseMove)
    window.removeEventListener('keydown', this.onKeyDown)
    window.removeEventListener('keyup', this.onKeyUp)
    window.removeEventListener('blur', this.onBlur)
  }
}
