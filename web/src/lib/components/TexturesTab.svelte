<script lang="ts">
  import { onMount } from 'svelte'
  import { appState } from '../stores.svelte'
  import { getLoadedAtlases, getAtlasDetails, getAtlasImageUrl } from '../api'
  import { toastStore } from '../toast.svelte'
  import type { TextureAtlasesResponse, TextureAtlasResponse, TextureAtlasSprite } from '../types'
  import {
    Image,
    Layers,
    Search,
    Download,
    RefreshCw,
    ZoomIn,
    ZoomOut,
    Maximize2,
    Copy,
    Check,
    Film,
    Grid
  } from 'lucide-svelte'

  let atlases = $state<TextureAtlasesResponse | null>(null)
  let selectedAtlasId = $state<string>('minecraft:textures/atlas/blocks.png')
  let atlasData = $state<TextureAtlasResponse | null>(null)
  let loading = $state(false)
  let searchQuery = $state('')
  let selectedSpriteName = $state<string | null>(null)
  let zoom = $state(1)
  let copiedUv = $state(false)

  async function loadAtlases() {
    if (!appState.token) return
    try {
      atlases = await getLoadedAtlases()
      if (atlases && atlases.atlases.length > 0 && !atlases.atlases.some(a => a.id === selectedAtlasId)) {
        selectedAtlasId = atlases.atlases[0].id
      }
      await loadAtlasDetails()
    } catch {
      // ignore
    }
  }

  async function loadAtlasDetails() {
    if (!appState.token) return
    loading = true
    try {
      atlasData = await getAtlasDetails(selectedAtlasId)
      if (atlasData && Object.keys(atlasData.sprites).length > 0) {
        if (!selectedSpriteName || !atlasData.sprites[selectedSpriteName]) {
          selectedSpriteName = Object.keys(atlasData.sprites)[0]
        }
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Failed to load atlas', 'error')
    } finally {
      loading = false
    }
  }

  onMount(() => {
    loadAtlases()
  })

  function selectAtlas(id: string) {
    selectedAtlasId = id
    selectedSpriteName = null
    loadAtlasDetails()
  }

  const filteredSprites = $derived(() => {
    if (!atlasData?.sprites) return []
    const q = searchQuery.toLowerCase().trim()
    const entries = Object.entries(atlasData.sprites)
    if (!q) return entries.slice(0, 300)
    return entries.filter(([name]) => name.toLowerCase().includes(q)).slice(0, 300)
  })

  const selectedSprite = $derived<TextureAtlasSprite | null>(() => {
    if (!atlasData?.sprites || !selectedSpriteName) return null
    return atlasData.sprites[selectedSpriteName] || null
  })

  function copySpriteUv() {
    if (!selectedSprite || !selectedSpriteName) return
    const text = JSON.stringify({
      sprite: selectedSpriteName,
      ...selectedSprite
    }, null, 2)
    try {
      navigator.clipboard?.writeText(text).catch(() => {})
    } catch {
      // ignore
    }
    copiedUv = true
    toastStore.show('Sprite UV data copied to clipboard!', 'success', 3000)
    setTimeout(() => copiedUv = false, 3000)
  }
</script>

<div class="space-y-6">
  <!-- Top Control Bar -->
  <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4">
    <div class="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-4">
      <div class="flex flex-wrap items-center gap-3">
        <div class="flex items-center gap-2">
          <div class="w-8 h-8 rounded-lg bg-primary/10 text-primary flex items-center justify-center shrink-0">
            <Layers class="w-4 h-4" />
          </div>
          <span class="font-bold text-sm text-base-content whitespace-nowrap">Texture Atlas:</span>
        </div>

        <select
          class="select select-sm select-bordered font-mono text-xs max-w-xs"
          value={selectedAtlasId}
          onchange={(e) => selectAtlas((e.target as HTMLSelectElement).value)}
        >
          {#if atlases?.atlases}
            {#each atlases.atlases as a}
              <option value={a.id}>{a.id} ({a.width}x{a.height})</option>
            {/each}
          {:else}
            <option value="minecraft:textures/atlas/blocks.png">minecraft:textures/atlas/blocks.png</option>
          {/if}
        </select>

        {#if atlasData}
          <div class="flex items-center gap-2">
            <span class="badge badge-neutral badge-sm font-mono">{atlasData.width} &times; {atlasData.height}</span>
            <span class="badge badge-outline badge-sm font-mono">{atlasData.spriteCount} sprites</span>
          </div>
        {/if}
      </div>

      <div class="flex items-center gap-2">
        <button
          class="btn btn-sm btn-ghost gap-1.5"
          onclick={loadAtlasDetails}
          disabled={loading}
          title="Reload atlas"
        >
          <RefreshCw class="w-3.5 h-3.5 {loading ? 'animate-spin' : ''}" />
          <span>Refresh</span>
        </button>

        <a
          href={getAtlasImageUrl(selectedAtlasId)}
          download="{selectedAtlasId.split('/').pop() || 'atlas'}"
          class="btn btn-sm btn-outline btn-primary gap-1.5"
        >
          <Download class="w-3.5 h-3.5" />
          <span>Download PNG</span>
        </a>
      </div>
    </div>
  </div>

  <!-- Main View: Canvas/Viewer + Sprite List -->
  <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
    <!-- Left Column: Interactive Atlas Viewport -->
    <div class="lg:col-span-7 card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
      <div class="p-3 bg-base-300/40 border-b border-base-300 flex items-center justify-between">
        <div class="flex items-center gap-2">
          <Image class="w-4 h-4 text-primary" />
          <span class="text-xs font-semibold text-base-content">Stitched Texture Viewport</span>
        </div>
        <div class="flex items-center gap-1">
          <button
            class="btn btn-xs btn-ghost btn-square"
            onclick={() => zoom = Math.max(0.25, zoom - 0.25)}
            title="Zoom out"
          >
            <ZoomOut class="w-3.5 h-3.5" />
          </button>
          <span class="text-[11px] font-mono w-12 text-center text-base-content/70">{Math.round(zoom * 100)}%</span>
          <button
            class="btn btn-xs btn-ghost btn-square"
            onclick={() => zoom = Math.min(4, zoom + 0.25)}
            title="Zoom in"
          >
            <ZoomIn class="w-3.5 h-3.5" />
          </button>
          <button
            class="btn btn-xs btn-ghost btn-square"
            onclick={() => zoom = 1}
            title="Reset zoom"
          >
            <Maximize2 class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <div class="relative w-full h-[540px] bg-base-300/20 overflow-auto p-4 flex items-center justify-center">
        {#if atlasData}
          <div
            class="relative inline-block transition-transform duration-75 origin-center shadow-lg border border-base-300 rounded bg-base-100"
            style="width: {atlasData.width * zoom}px; height: {atlasData.height * zoom}px; min-width: {atlasData.width * zoom}px; min-height: {atlasData.height * zoom}px;"
          >
            <!-- Stitched Texture Image -->
            <img
              src={getAtlasImageUrl(selectedAtlasId)}
              alt="Texture Atlas"
              class="w-full h-full object-fill [image-rendering:pixelated] select-none pointer-events-none"
            />

            <!-- Highlight Overlay for Selected Sprite -->
            {#if selectedSprite}
              <div
                class="absolute border-2 border-primary bg-primary/20 pointer-events-none transition-all duration-150 z-10"
                style="
                  left: {selectedSprite.x * zoom}px;
                  top: {selectedSprite.y * zoom}px;
                  width: {Math.max(2, selectedSprite.width * zoom)}px;
                  height: {Math.max(2, selectedSprite.height * zoom)}px;
                "
              ></div>
            {/if}
          </div>
        {:else if loading}
          <div class="flex flex-col items-center gap-2 text-base-content/60">
            <span class="loading loading-spinner loading-md text-primary"></span>
            <span class="text-xs">Loading texture atlas...</span>
          </div>
        {:else}
          <span class="text-xs text-base-content/50">No atlas loaded</span>
        {/if}
      </div>
    </div>

    <!-- Right Column: Sprites Explorer & Inspector -->
    <div class="lg:col-span-5 space-y-4">
      <!-- Sprite Details Card -->
      {#if selectedSprite && selectedSpriteName}
        <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 space-y-3">
          <div class="flex items-center justify-between pb-2 border-b border-base-300">
            <div class="flex items-center gap-2 min-w-0">
              <div class="w-7 h-7 rounded bg-primary/10 text-primary flex items-center justify-center shrink-0">
                <Grid class="w-3.5 h-3.5" />
              </div>
              <div class="min-w-0">
                <h3 class="text-xs font-bold font-mono truncate text-base-content" title={selectedSpriteName}>
                  {selectedSpriteName}
                </h3>
              </div>
            </div>
            <button
              class="btn btn-xs {copiedUv ? 'btn-success' : 'btn-ghost'} gap-1 shrink-0"
              onclick={copySpriteUv}
              title="Copy sprite UV JSON"
            >
              {#if copiedUv}
                <Check class="w-3 h-3" />
                <span>Copied</span>
              {:else}
                <Copy class="w-3 h-3" />
                <span>Copy UV</span>
              {/if}
            </button>
          </div>

          <!-- Pixel Coordinates & Normalized UVs -->
          <div class="grid grid-cols-2 gap-2 text-xs">
            <div class="p-2 bg-base-300/50 rounded space-y-1">
              <span class="text-[11px] text-base-content/60 font-medium block">Pixel Bounds</span>
              <div class="font-mono text-[11px]">
                X: {selectedSprite.x}, Y: {selectedSprite.y}<br />
                Size: {selectedSprite.width} &times; {selectedSprite.height}px
              </div>
            </div>

            <div class="p-2 bg-base-300/50 rounded space-y-1">
              <span class="text-[11px] text-base-content/60 font-medium block">Normalized UV (0..1)</span>
              <div class="font-mono text-[11px]">
                U: [{selectedSprite.u0.toFixed(4)}, {selectedSprite.u1.toFixed(4)}]<br />
                V: [{selectedSprite.v0.toFixed(4)}, {selectedSprite.v1.toFixed(4)}]
              </div>
            </div>
          </div>

          <div class="flex items-center gap-2 text-xs">
            {#if selectedSprite.animated}
              <span class="badge badge-warning badge-sm gap-1">
                <Film class="w-3 h-3" />
                <span>Animated ({selectedSprite.frameCount} frames)</span>
              </span>
            {:else}
              <span class="badge badge-ghost badge-sm">Static Sprite</span>
            {/if}
          </div>
        </div>
      {/if}

      <!-- Sprite Search & List Card -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 space-y-3">
        <div class="flex items-center gap-2">
          <div class="relative flex-1">
            <Search class="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-base-content/40" />
            <input
              type="text"
              class="input input-sm input-bordered w-full pl-9 text-xs"
              placeholder="Search sprite (e.g. diamond, stone, dirt)..."
              bind:value={searchQuery}
            />
          </div>
          {#if searchQuery}
            <button class="btn btn-xs btn-ghost" onclick={() => searchQuery = ''}>Clear</button>
          {/if}
        </div>

        <div class="text-[11px] text-base-content/60 flex items-center justify-between px-1">
          <span>Showing {filteredSprites().length} matching sprites</span>
          <span>Max 300 shown</span>
        </div>

        <div class="overflow-y-auto max-h-[340px] space-y-1 pr-1">
          {#each filteredSprites() as [name, sprite]}
            <button
              class="w-full text-left px-2.5 py-1.5 rounded text-xs font-mono transition-colors flex items-center justify-between gap-2 {selectedSpriteName === name ? 'bg-primary text-primary-content font-bold' : 'hover:bg-base-300/60 text-base-content/80'}"
              onclick={() => selectedSpriteName = name}
            >
              <span class="truncate">{name.replace('minecraft:', '')}</span>
              <span class="text-[10px] opacity-75 shrink-0">{sprite.width}&times;{sprite.height}</span>
            </button>
          {/each}
          {#if filteredSprites().length === 0}
            <div class="p-4 text-center text-xs text-base-content/50">
              No sprites match "{searchQuery}"
            </div>
          {/if}
        </div>
      </div>
    </div>
  </div>
</div>
