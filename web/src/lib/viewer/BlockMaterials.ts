import * as THREE from 'three/webgpu'
import { attribute, float, max, mix, pow, texture, uniform, uv, vec3, vec4 } from 'three/tsl'
import type { Node } from 'three/webgpu'

/**
 * Shared atlas materials for the three render layers. Colors stay in Minecraft's gamma space end to end (no sRGB
 * decode, linear output) so textures, tints and the lightmap curve match the game.
 */
export class BlockMaterials {
  readonly skyFactor = uniform(1.0)
  readonly fullbright = uniform(0.0)
  readonly solid: THREE.MeshBasicNodeMaterial
  readonly cutout: THREE.MeshBasicNodeMaterial
  readonly translucent: THREE.MeshBasicNodeMaterial
  readonly atlas: THREE.Texture

  constructor(atlas: THREE.Texture) {
    atlas.colorSpace = THREE.NoColorSpace
    atlas.flipY = false
    atlas.magFilter = THREE.NearestFilter
    atlas.minFilter = THREE.NearestFilter
    atlas.generateMipmaps = false
    atlas.needsUpdate = true
    this.atlas = atlas

    this.solid = this.make('solid')
    this.cutout = this.make('cutout')
    this.cutout.alphaTest = 0.5
    this.translucent = this.make('translucent')
  }

  get all(): THREE.MeshBasicNodeMaterial[] {
    return [this.solid, this.cutout, this.translucent]
  }

  setWireframe(on: boolean) {
    for (const m of this.all) m.wireframe = on
  }

  dispose() {
    for (const m of this.all) m.dispose()
    this.atlas.dispose()
  }

  private make(mode: 'solid' | 'cutout' | 'translucent'): THREE.MeshBasicNodeMaterial {
    const tex = texture(this.atlas, uv())
    const tint = attribute('aTint', 'vec4')
    const light = attribute('aLight', 'vec4')

    // Vanilla lightmap: brightness l / (4 - 3l), then the default 50% gamma boost.
    const curve = (l: Node<'float'>) => {
      const b = l.div(float(4).sub(l.mul(3)))
      return mix(b, float(1).sub(pow(float(1).sub(b), 4)), 0.5)
    }
    const sky = curve(light.x).mul(this.skyFactor)
    const blk = curve(light.y)
    let lit = max(vec3(sky, sky, sky), vec3(blk, blk.mul(0.88), blk.mul(0.72)))
    lit = mix(vec3(0.04, 0.04, 0.05), vec3(1, 1, 1), lit)
    lit = mix(lit, vec3(1, 1, 1), this.fullbright)

    const material = new THREE.MeshBasicNodeMaterial()
    material.colorNode = vec4(tex.rgb.mul(tint.rgb).mul(lit), mode === 'solid' ? float(1) : tex.a)
    if (mode === 'translucent') {
      material.transparent = true
      material.depthWrite = false
      material.side = THREE.DoubleSide
    }
    return material
  }
}
