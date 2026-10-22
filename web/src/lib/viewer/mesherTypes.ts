import type { BlockModel } from '../types'

/** One render layer of a section. Positions are relative to the section center. */
export interface MeshLayerData {
  pos: Float32Array
  uv: Float32Array
  /** RGBA bytes: biome tint × face shade × ambient occlusion */
  tint: Uint8Array
  /** Bytes per vertex: sky light × 17, block light × 17, unused, unused */
  light: Uint8Array
  index: Uint16Array | Uint32Array
}

export type MesherRequest =
  | { type: 'hello'; hasSkyLight: boolean }
  | { type: 'chunk'; buffer: ArrayBuffer }
  | { type: 'unload'; cx: number; cz: number }
  | { type: 'models'; models: BlockModel[] }
  | { type: 'modelsFailed'; ids: number[] }
  | { type: 'camera'; x: number; y: number; z: number }
  | { type: 'remeshAll' }

export type MesherResponse =
  | { type: 'mesh'; key: string; cx: number; sy: number; cz: number; layers: (MeshLayerData | null)[] }
  | { type: 'remove'; keys: string[] }
  | { type: 'need'; ids: number[] }
