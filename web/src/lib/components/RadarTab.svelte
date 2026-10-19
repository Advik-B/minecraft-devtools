<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import { getEntities, getEntityCategories, getRaycast, playerLook, getPlayer } from '../api'
  import { toastStore } from '../toast.svelte'
  import type { EntitiesResponse, EntityInfo, RaycastResponse, PlayerResponse } from '../types'
  import {
    Radar,
    Target,
    Crosshair,
    Eye,
    Copy,
    RefreshCw,
    Search,
    ShieldAlert,
    Skull,
    Heart,
    Box,
    User,
    Compass,
    Key
  } from 'lucide-svelte'

  let entitiesData = $state<EntitiesResponse | null>(null)
  let raycastData = $state<RaycastResponse | null>(null)
  let playerData = $state<PlayerResponse | null>(null)
  let categoryMap = $state<Record<string, string>>({})
  let pollInterval: number | null = null
  let isRefreshing = $state(false)
  let canvasEl: HTMLCanvasElement | null = $state(null)

  let radarRadius = $state(32)
  let searchQuery = $state('')
  let selectedCategory = $state<'all' | 'hostile' | 'passive' | 'players' | 'items'>('all')
  let hoveredEntityId = $state<number | null>(null)

  function classifyEntity(ent: EntityInfo): 'player' | 'hostile' | 'passive' | 'item' | 'other' {
    if (ent.category) {
      if (ent.category === 'player' || ent.category === 'hostile' || ent.category === 'passive' || ent.category === 'item') {
        return ent.category
      }
    }
    const mapped = categoryMap[ent.type]
    if (mapped) {
      if (mapped === 'hostile' || mapped === 'passive' || mapped === 'player' || mapped === 'item') {
        return mapped
      }
    }
    return 'other'
  }

  function getEntityColor(category: string): string {
    switch (category) {
      case 'player': return '#38bdf8' // Sky blue
      case 'hostile': return '#f87171' // Red
      case 'passive': return '#4ade80' // Green
      case 'item': return '#facc15' // Yellow
      default: return '#c084fc' // Purple
    }
  }

  async function loadCategories() {
    if (!appState.token) return
    try {
      const res = await getEntityCategories().catch(() => null)
      if (res?.categories) {
        categoryMap = res.categories
      }
    } catch {
      // ignore
    }
  }

  async function loadData() {
    if (!appState.token) return
    try {
      const [entities, ray, pl] = await Promise.all([
        getEntities(radarRadius).catch(() => null),
        getRaycast().catch(() => null),
        getPlayer().catch(() => null)
      ])
      if (entities) entitiesData = entities
      if (ray) raycastData = ray
      if (pl) playerData = pl
      drawRadar()
    } catch {
      // ignore
    }
  }

  onMount(() => {
    loadCategories()
    loadData()
    pollInterval = window.setInterval(loadData, 1200)
  })

  onDestroy(() => {
    if (pollInterval !== null) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  })

  function drawRadar() {
    if (!canvasEl) return
    const ctx = canvasEl.getContext('2d')
    if (!ctx) return

    const width = canvasEl.width
    const height = canvasEl.height
    const cx = width / 2
    const cy = height / 2
    const radiusPx = width / 2 - 16

    ctx.clearRect(0, 0, width, height)

    // Background circle
    ctx.fillStyle = '#0f172a'
    ctx.beginPath()
    ctx.arc(cx, cy, radiusPx, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = '#334155'
    ctx.lineWidth = 1.5
    ctx.stroke()

    // Range rings
    const rings = [0.25, 0.5, 0.75, 1.0]
    ctx.lineWidth = 1
    rings.forEach((ratio) => {
      ctx.strokeStyle = '#1e293b'
      ctx.beginPath()
      ctx.arc(cx, cy, radiusPx * ratio, 0, Math.PI * 2)
      ctx.stroke()

      // Distance labels
      ctx.fillStyle = '#64748b'
      ctx.font = '10px monospace'
      ctx.fillText(`${Math.round(radarRadius * ratio)}m`, cx + 4, cy - radiusPx * ratio + 10)
    })

    // Crosshairs
    ctx.strokeStyle = '#1e293b'
    ctx.beginPath()
    ctx.moveTo(cx - radiusPx, cy)
    ctx.lineTo(cx + radiusPx, cy)
    ctx.moveTo(cx, cy - radiusPx)
    ctx.lineTo(cx, cy + radiusPx)
    ctx.stroke()

    // Player heading cone & pointer
    const playerYaw = playerData?.position?.yaw || 0
    // Minecraft yaw: 0 = South (+Z), 90 = West (-X), 180 = North (-Z), 270 = East (+X)
    // Canvas standard: angle = yaw * Math.PI / 180 + Math.PI / 2
    const rad = ((playerYaw + 90) * Math.PI) / 180

    // Field of view cone
    ctx.fillStyle = 'rgba(56, 189, 248, 0.08)'
    ctx.beginPath()
    ctx.moveTo(cx, cy)
    ctx.arc(cx, cy, radiusPx, rad - 0.45, rad + 0.45)
    ctx.closePath()
    ctx.fill()

    // Draw entities
    if (entitiesData && playerData?.position) {
      const px = playerData.position.x
      const pz = playerData.position.z

      entitiesData.entities.forEach((ent) => {
        const dx = ent.pos[0] - px
        const dz = ent.pos[2] - pz
        const dist = Math.sqrt(dx * dx + dz * dz)
        if (dist > radarRadius) return

        const scale = radiusPx / radarRadius
        const ex = cx + dx * scale
        const ey = cy + dz * scale

        const category = classifyEntity(ent)
        const color = getEntityColor(category)
        const isHovered = hoveredEntityId === ent.id

        ctx.fillStyle = color
        ctx.beginPath()
        ctx.arc(ex, ey, isHovered ? 6 : 4, 0, Math.PI * 2)
        ctx.fill()

        if (isHovered) {
          ctx.strokeStyle = '#ffffff'
          ctx.lineWidth = 2
          ctx.stroke()
        }
      })
    }

    // Center player blip
    ctx.fillStyle = '#38bdf8'
    ctx.beginPath()
    ctx.arc(cx, cy, 5, 0, Math.PI * 2)
    ctx.fill()
    ctx.strokeStyle = '#ffffff'
    ctx.lineWidth = 2
    ctx.stroke()

    // Heading arrow line
    ctx.strokeStyle = '#38bdf8'
    ctx.lineWidth = 2
    ctx.beginPath()
    ctx.moveTo(cx, cy)
    ctx.lineTo(cx + Math.cos(rad) * 16, cy + Math.sin(rad) * 16)
    ctx.stroke()
  }

  $effect(() => {
    if (entitiesData || playerData || radarRadius) {
      drawRadar()
    }
  })

  async function handleLookAtEntity(ent: EntityInfo) {
    try {
      await playerLook({ target: ent.pos })
      toastStore.show(`Aimed at ${ent.customName || ent.type}`, 'success')
      await loadData()
    } catch {
      toastStore.show('Failed to turn camera', 'error')
    }
  }

  function handleCopyTp(ent: EntityInfo) {
    const [x, y, z] = ent.pos
    const cmd = `/tp ${x.toFixed(2)} ${y.toFixed(2)} ${z.toFixed(2)}`
    try {
      navigator.clipboard?.writeText(cmd).catch(() => {})
    } catch {
      // ignore
    }
    toastStore.show('Copied: ' + cmd, 'success')
  }

  const filteredEntities = $derived.by(() => {
    if (!entitiesData) return []
    return entitiesData.entities.filter((ent) => {
      const cat = classifyEntity(ent)
      if (selectedCategory === 'hostile' && cat !== 'hostile') return false
      if (selectedCategory === 'passive' && cat !== 'passive') return false
      if (selectedCategory === 'players' && cat !== 'player') return false
      if (selectedCategory === 'items' && cat !== 'item') return false

      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase().trim()
        const matchType = ent.type.toLowerCase().includes(q)
        const matchName = ent.customName?.toLowerCase().includes(q)
        if (!matchType && !matchName) return false
      }

      return true
    })
  })
</script>

<div class="space-y-6">
  {#if !appState.token}
    <div role="alert" class="alert alert-soft alert-warning border border-warning/30 shadow-md p-6 rounded-box flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <div class="p-3 bg-warning/20 rounded-xl text-warning">
          <Key class="w-6 h-6" />
        </div>
        <div>
          <h3 class="font-bold text-base text-base-content">Authentication Required</h3>
          <p class="text-sm text-base-content/70">Configure your token to activate live world radar and entity scanning.</p>
        </div>
      </div>
      <button class="btn btn-warning btn-sm shrink-0" onclick={() => appState.tokenModalOpen = true}>Set Token</button>
    </div>
  {:else if !appState.inGame}
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-8 text-center space-y-3">
      <div class="w-12 h-12 mx-auto rounded-full bg-base-300 flex items-center justify-center text-base-content/50">
        <Radar class="w-6 h-6" />
      </div>
      <h3 class="font-bold text-lg text-base-content">No Active World Loaded</h3>
      <p class="text-sm text-base-content/60 max-w-md mx-auto">
        Load into a Minecraft world to scan nearby entities, view the 2D radar, and inspect crosshair targets.
      </p>
    </div>
  {:else}
    <!-- Top Row: 2D Radar Canvas + Crosshair Raycast Inspector -->
    <div class="grid grid-cols-1 lg:grid-cols-12 gap-6 items-stretch">
      <!-- 2D Canvas Radar -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 lg:col-span-5 flex flex-col items-center justify-center relative">
        <div class="w-full flex items-center justify-between pb-3 mb-2 border-b border-base-300">
          <div class="flex items-center gap-2 text-sm font-semibold text-base-content">
            <Radar class="w-4 h-4 text-primary animate-pulse" />
            <span>2D Entity Scanner</span>
          </div>
          <!-- Radius selector -->
          <div class="join join-horizontal">
            {#each [16, 32, 64] as r}
              <button
                class="join-item btn btn-xs {radarRadius === r ? 'btn-primary' : 'btn-ghost'}"
                onclick={() => { radarRadius = r; loadData() }}
              >
                {r}m
              </button>
            {/each}
          </div>
        </div>

        <div class="relative w-[280px] h-[280px] sm:w-[320px] sm:h-[320px] my-auto flex items-center justify-center">
          <canvas
            bind:this={canvasEl}
            width={320}
            height={320}
            class="rounded-full shadow-lg border border-base-300 max-w-full h-auto"
          ></canvas>
        </div>

        <!-- Legend Pills -->
        <div class="flex flex-wrap items-center justify-center gap-3 pt-3 mt-2 border-t border-base-300 text-[11px] text-base-content/70">
          <span class="flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full bg-[#f87171]"></span> Hostile</span>
          <span class="flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full bg-[#4ade80]"></span> Passive</span>
          <span class="flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full bg-[#38bdf8]"></span> Players</span>
          <span class="flex items-center gap-1.5"><span class="w-2.5 h-2.5 rounded-full bg-[#facc15]"></span> Items</span>
        </div>
      </div>

      <!-- Raycast & Crosshair Target Inspector -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 lg:col-span-7 flex flex-col justify-between space-y-4">
        <div>
          <div class="flex items-center justify-between pb-3 border-b border-base-300">
            <div class="flex items-center gap-2 text-sm font-semibold text-base-content">
              <Crosshair class="w-4 h-4 text-secondary" />
              <span>Crosshair Raycast Target</span>
            </div>
            <span class="badge {raycastData?.hitType === 'BLOCK' ? 'badge-primary' : raycastData?.hitType === 'ENTITY' ? 'badge-secondary' : 'badge-ghost'} badge-sm font-mono font-bold">
              {raycastData?.hitType || 'AIR / MISS'}
            </span>
          </div>

          <div class="py-4 space-y-3">
            {#if raycastData?.hitType === 'BLOCK' && raycastData.block}
              <div class="p-4 rounded-xl bg-base-100 border border-base-300 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="font-bold text-sm text-base-content">{raycastData.block.state.split('[')[0]}</span>
                  <span class="badge badge-outline badge-xs font-mono">{raycastData.distance.toFixed(2)}m away</span>
                </div>
                <div class="grid grid-cols-2 gap-2 text-xs font-mono text-base-content/70">
                  <div>Face: <span class="text-base-content font-bold">{raycastData.block.face}</span></div>
                  <div>Coords: <span class="text-base-content font-bold">[{raycastData.block.pos.join(', ')}]</span></div>
                </div>
                {#if raycastData.block.state.includes('[')}
                  <div class="text-[11px] font-mono text-base-content/50 truncate bg-base-200/80 p-1.5 rounded">
                    {raycastData.block.state}
                  </div>
                {/if}
              </div>
            {:else if raycastData?.hitType === 'ENTITY' && raycastData.entity}
              <div class="p-4 rounded-xl bg-base-100 border border-secondary/40 space-y-2">
                <div class="flex items-center justify-between">
                  <span class="font-bold text-sm text-secondary">{raycastData.entity.name || raycastData.entity.type}</span>
                  <span class="badge badge-secondary badge-xs font-mono">{raycastData.distance.toFixed(2)}m away</span>
                </div>
                <div class="text-xs font-mono text-base-content/70">
                  <div>ID: #{raycastData.entity.id}</div>
                  <div class="truncate">UUID: {raycastData.entity.uuid}</div>
                </div>
              </div>
            {:else}
              <div class="p-6 rounded-xl bg-base-100 border border-base-300 text-center text-xs text-base-content/50 italic">
                Looking into open air or distant horizon (no block within reach)
              </div>
            {/if}

            <!-- Player Position & Orientation Quick Summary -->
            {#if playerData?.position}
              <div class="grid grid-cols-2 sm:grid-cols-4 gap-2 pt-2">
                <div class="p-2.5 rounded-lg bg-base-100 border border-base-300 text-center">
                  <span class="text-[10px] text-base-content/50 uppercase block font-semibold">X</span>
                  <span class="font-mono font-bold text-xs">{playerData.position.x.toFixed(2)}</span>
                </div>
                <div class="p-2.5 rounded-lg bg-base-100 border border-base-300 text-center">
                  <span class="text-[10px] text-base-content/50 uppercase block font-semibold">Y</span>
                  <span class="font-mono font-bold text-xs">{playerData.position.y.toFixed(2)}</span>
                </div>
                <div class="p-2.5 rounded-lg bg-base-100 border border-base-300 text-center">
                  <span class="text-[10px] text-base-content/50 uppercase block font-semibold">Z</span>
                  <span class="font-mono font-bold text-xs">{playerData.position.z.toFixed(2)}</span>
                </div>
                <div class="p-2.5 rounded-lg bg-base-100 border border-base-300 text-center">
                  <span class="text-[10px] text-base-content/50 uppercase block font-semibold">Facing</span>
                  <span class="font-mono font-bold text-xs">{playerData.position.yaw.toFixed(1)}°</span>
                </div>
              </div>
            {/if}
          </div>
        </div>

        <div class="flex items-center justify-between text-xs text-base-content/60 pt-2 border-t border-base-300">
          <span>{entitiesData?.count || 0} entities found in {radarRadius}m radius</span>
          <button class="btn btn-xs btn-ghost gap-1" onclick={loadData}>
            <RefreshCw class="w-3 h-3" />
            <span>Rescan</span>
          </button>
        </div>
      </div>
    </div>

    <!-- Live Entities Table & Search -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4">
      <div class="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-3 pb-3 border-b border-base-300">
        <!-- Search filter -->
        <div class="relative flex-1 max-w-md">
          <Search class="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-base-content/50" />
          <input
            type="text"
            bind:value={searchQuery}
            placeholder="Filter entities (e.g. creeper, zombie, cow)..."
            class="input input-sm input-bordered w-full pl-9 bg-base-100"
          />
        </div>

        <!-- Category Filter Tabs -->
        <div role="tablist" class="tabs tabs-box tabs-sm bg-base-300/60 p-1">
          <button
            role="tab"
            class="tab {selectedCategory === 'all' ? 'tab-active font-semibold' : ''}"
            onclick={() => selectedCategory = 'all'}
          >
            All ({entitiesData?.count || 0})
          </button>
          <button
            role="tab"
            class="tab {selectedCategory === 'hostile' ? 'tab-active font-semibold text-error' : ''}"
            onclick={() => selectedCategory = 'hostile'}
          >
            Hostile
          </button>
          <button
            role="tab"
            class="tab {selectedCategory === 'passive' ? 'tab-active font-semibold text-success' : ''}"
            onclick={() => selectedCategory = 'passive'}
          >
            Passive
          </button>
          <button
            role="tab"
            class="tab {selectedCategory === 'players' ? 'tab-active font-semibold text-info' : ''}"
            onclick={() => selectedCategory = 'players'}
          >
            Players
          </button>
          <button
            role="tab"
            class="tab {selectedCategory === 'items' ? 'tab-active font-semibold text-warning' : ''}"
            onclick={() => selectedCategory = 'items'}
          >
            Items
          </button>
        </div>
      </div>

      <!-- Entities Table -->
      <div class="overflow-x-auto">
        <table class="table table-sm table-zebra w-full text-xs">
          <thead>
            <tr class="text-base-content/60 border-b border-base-300">
              <th>Type / Name</th>
              <th>Category</th>
              <th>Health</th>
              <th>Distance</th>
              <th>Position [X, Y, Z]</th>
              <th class="text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {#if filteredEntities.length === 0}
              <tr>
                <td colspan="6" class="text-center py-8 text-base-content/40 italic">
                  No entities found matching your criteria.
                </td>
              </tr>
            {:else}
              {#each filteredEntities as ent}
                {@const cat = classifyEntity(ent)}
                <tr
                  class="hover:bg-base-300/60 transition-colors {hoveredEntityId === ent.id ? 'bg-primary/10' : ''}"
                  onmouseenter={() => hoveredEntityId = ent.id}
                  onmouseleave={() => hoveredEntityId = null}
                >
                  <td class="font-bold text-base-content">
                    <div class="flex items-center gap-2">
                      <span class="w-2 h-2 rounded-full" style="background-color: {getEntityColor(cat)}"></span>
                      <span>{ent.customName || ent.type.replace('minecraft:', '')}</span>
                    </div>
                  </td>
                  <td>
                    <span class="badge badge-soft badge-xs capitalize {cat === 'hostile' ? 'badge-error' : cat === 'passive' ? 'badge-success' : cat === 'player' ? 'badge-info' : 'badge-warning'}">
                      {cat}
                    </span>
                  </td>
                  <td>
                    {#if ent.health !== undefined && ent.maxHealth !== undefined}
                      <div class="flex items-center gap-1.5 font-mono">
                        <Heart class="w-3 h-3 text-error fill-error" />
                        <span>{ent.health.toFixed(1)}/{ent.maxHealth.toFixed(1)}</span>
                      </div>
                    {:else}
                      <span class="text-base-content/40 italic">-</span>
                    {/if}
                  </td>
                  <td class="font-mono font-semibold">{ent.distance.toFixed(1)}m</td>
                  <td class="font-mono text-base-content/70">
                    [{ent.pos[0].toFixed(1)}, {ent.pos[1].toFixed(1)}, {ent.pos[2].toFixed(1)}]
                  </td>
                  <td class="text-right">
                    <div class="inline-flex items-center gap-1">
                      <button
                        class="btn btn-xs btn-ghost btn-square"
                        title="Aim Crosshair at Entity"
                        onclick={() => handleLookAtEntity(ent)}
                      >
                        <Crosshair class="w-3.5 h-3.5 text-primary" />
                      </button>
                      <button
                        class="btn btn-xs btn-ghost btn-square"
                        title="Copy Teleport Command"
                        onclick={() => handleCopyTp(ent)}
                      >
                        <Copy class="w-3.5 h-3.5" />
                      </button>
                    </div>
                  </td>
                </tr>
              {/each}
            {/if}
          </tbody>
        </table>
      </div>
    </div>
  {/if}
</div>
