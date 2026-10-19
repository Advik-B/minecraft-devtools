<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import {
    getBaritoneStatus,
    getBaritonePath,
    startBaritoneGoto,
    startBaritoneMine,
    startBaritoneFollow,
    startBaritoneExplore,
    cancelBaritone,
    getBaritoneSettings,
    patchBaritoneSettings,
    executeBaritoneCommand,
    getPlayer,
    getRegistryEntries
  } from '../api'
  import { toastStore } from '../toast.svelte'
  import type { BaritoneStatusResponse, BaritonePathResponse, BaritoneSettingInfo } from '../types'
  import {
    Bot,
    Compass,
    Pickaxe,
    Users,
    Navigation,
    Square,
    Play,
    Terminal,
    Settings,
    Search,
    AlertCircle,
    CheckCircle2,
    RefreshCw,
    Key,
    SlidersHorizontal
  } from 'lucide-svelte'

  let status = $state<BaritoneStatusResponse | null>(null)
  let path = $state<BaritonePathResponse | null>(null)
  let settingsMap = $state<Record<string, BaritoneSettingInfo>>({})
  let isInstalled = $state<boolean | null>(null)
  let pollInterval: number | null = null

  // Form inputs
  let gotoX = $state<number>(0)
  let gotoY = $state<number>(64)
  let gotoZ = $state<number>(0)

  let mineBlock = $state('')
  let mineQuantity = $state(16)
  let blockSuggestions = $state<string[]>([])
  let entitySuggestions = $state<string[]>([])

  let followTarget = $state('')
  let exploreX = $state<number | null>(null)
  let exploreZ = $state<number | null>(null)

  let rawCommand = $state('')
  let isSendingCommand = $state(false)

  let settingsSearch = $state('')
  let isBusy = $state(false)

  async function loadBlockSuggestions(query = '') {
    if (!appState.token) return
    try {
      const res = await getRegistryEntries('minecraft:block', query, 50).catch(() => null)
      if (res?.entries) {
        blockSuggestions = res.entries
      }
    } catch {
      // ignore
    }
  }

  async function loadEntitySuggestions(query = '') {
    if (!appState.token) return
    try {
      const res = await getRegistryEntries('minecraft:entity_type', query, 50).catch(() => null)
      if (res?.entries) {
        entitySuggestions = res.entries
      }
    } catch {
      // ignore
    }
  }

  async function loadData() {
    if (!appState.token) return
    try {
      const st = await getBaritoneStatus().catch(() => null)
      if (st) {
        status = st
        isInstalled = st.installed !== false
      } else {
        isInstalled = false
      }

      if (isInstalled) {
        const pt = await getBaritonePath().catch(() => null)
        if (pt) path = pt
      }
    } catch {
      isInstalled = false
    }
  }

  async function loadSettings() {
    if (!appState.token || isInstalled === false) return
    try {
      const sets = await getBaritoneSettings().catch(() => null)
      if (sets) {
        settingsMap = sets as Record<string, BaritoneSettingInfo>
      }
    } catch {
      // ignore
    }
  }

  onMount(() => {
    loadData()
    loadSettings()
    loadBlockSuggestions()
    loadEntitySuggestions()
    pollInterval = window.setInterval(loadData, 1500)
  })

  onDestroy(() => {
    if (pollInterval !== null) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  })

  async function handleFillCurrentCoords() {
    const pl = await getPlayer().catch(() => null)
    if (pl) {
      gotoX = Math.round(pl.pos[0])
      gotoY = Math.round(pl.pos[1])
      gotoZ = Math.round(pl.pos[2])
      toastStore.show('Filled current coordinates', 'info', 1000)
    }
  }

  async function handleGoto() {
    isBusy = true
    try {
      const res = await startBaritoneGoto(gotoX, gotoY, gotoZ)
      if (res) {
        toastStore.show(`Navigating to [${gotoX}, ${gotoY}, ${gotoZ}]`, 'success')
        await loadData()
      } else {
        toastStore.show('Failed to start navigation', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Error starting Goto', 'error')
    } finally {
      isBusy = false
    }
  }

  async function handleMine() {
    isBusy = true
    try {
      const blockId = mineBlock.trim()
      const res = await startBaritoneMine([blockId], mineQuantity)
      if (res) {
        toastStore.show(`Mining ${mineQuantity}x ${blockId}`, 'success')
        await loadData()
      } else {
        toastStore.show('Failed to start mining task', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Error starting Mine', 'error')
    } finally {
      isBusy = false
    }
  }

  async function handleFollow() {
    if (!followTarget.trim()) return
    isBusy = true
    try {
      const res = await startBaritoneFollow(followTarget.trim())
      if (res) {
        toastStore.show(`Following ${followTarget}`, 'success')
        await loadData()
      } else {
        toastStore.show('Failed to follow target', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Error starting Follow', 'error')
    } finally {
      isBusy = false
    }
  }

  async function handleExplore() {
    isBusy = true
    try {
      const res = await startBaritoneExplore(exploreX ?? undefined, exploreZ ?? undefined)
      if (res) {
        toastStore.show('Explore mission launched', 'success')
        await loadData()
      } else {
        toastStore.show('Failed to start exploration', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Error starting Explore', 'error')
    } finally {
      isBusy = false
    }
  }

  async function handleCancel() {
    isBusy = true
    try {
      const ok = await cancelBaritone()
      if (ok) {
        toastStore.show('Baritone process stopped', 'info')
        await loadData()
      } else {
        toastStore.show('No active process or failed to stop', 'warning')
      }
    } catch {
      toastStore.show('Failed to cancel Baritone task', 'error')
    } finally {
      isBusy = false
    }
  }

  async function handleExecuteCommand() {
    const cmd = rawCommand.trim()
    if (!cmd) return
    isSendingCommand = true
    try {
      const ok = await executeBaritoneCommand(cmd)
      if (ok) {
        toastStore.show(`Executed: ${cmd}`, 'success')
        rawCommand = ''
        await loadData()
      } else {
        toastStore.show('Command rejected by Baritone', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Error executing command', 'error')
    } finally {
      isSendingCommand = false
    }
  }

  async function handleToggleSetting(name: string, currentValue: unknown) {
    if (typeof currentValue !== 'boolean') return
    const nextVal = !currentValue
    try {
      const patch = await patchBaritoneSettings({ [name]: nextVal })
      if (patch) {
        settingsMap[name] = { ...settingsMap[name], value: nextVal }
        toastStore.show(`Setting updated: ${name} = ${nextVal}`, 'success', 1200)
      }
    } catch {
      toastStore.show(`Failed to update ${name}`, 'error')
    }
  }

  const filteredSettings = $derived.by(() => {
    const entries = Object.entries(settingsMap)
    if (!settingsSearch.trim()) return entries.slice(0, 50)
    const q = settingsSearch.toLowerCase()
    return entries.filter(([name]) => name.toLowerCase().includes(q))
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
          <p class="text-sm text-base-content/70">Connect with your Bearer token to dispatch bot automation jobs.</p>
        </div>
      </div>
      <button class="btn btn-warning btn-sm shrink-0" onclick={() => appState.tokenModalOpen = true}>Set Token</button>
    </div>
  {:else if isInstalled === false}
    <div class="card bg-base-200 border border-warning/30 shadow-sm rounded-box p-8 text-center space-y-4">
      <div class="w-14 h-14 mx-auto rounded-full bg-warning/10 text-warning flex items-center justify-center">
        <Bot class="w-7 h-7" />
      </div>
      <h3 class="font-bold text-lg text-base-content">Baritone Mod Not Detected</h3>
      <p class="text-sm text-base-content/70 max-w-lg mx-auto">
        Baritone is not currently installed or active in this Minecraft Fabric client. To enable autonomous pathfinding, mining, building, and farming, add the Baritone Fabric mod to your client mods folder.
      </p>
      <div class="badge badge-outline badge-neutral font-mono text-xs">HTTP 501: dev.advik.mcdt.baritone.UnavailableBaritoneService</div>
    </div>
  {:else}
    <!-- Status & Emergency Stop Banner -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5">
      <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 rounded-xl bg-primary/10 text-primary flex items-center justify-center">
            <Bot class="w-6 h-6" />
          </div>
          <div>
            <div class="flex items-center gap-2">
              <h2 class="text-base font-bold text-base-content">Baritone Automation Agent</h2>
              {#if status?.pathing?.isPathing}
                <span class="badge badge-success badge-sm animate-pulse">Active Pathing</span>
              {:else if status?.pathing?.calculating}
                <span class="badge badge-warning badge-sm animate-pulse">Calculating Path</span>
              {:else}
                <span class="badge badge-ghost badge-sm">Idle</span>
              {/if}
            </div>
            <p class="text-xs text-base-content/60">
              Active Process: <span class="font-mono text-base-content font-bold">{status?.activeProcess || 'None'}</span>
              {#if status?.goal?.type}
                &bull; Goal: <span class="font-mono text-primary font-semibold">{status.goal.type}</span>
              {/if}
              {#if path?.currentSegment}
                &bull; Path Waypoints: <span class="font-mono">{path.currentSegment.length} blocks</span>
              {/if}
            </p>
          </div>
        </div>

        <button
          class="btn btn-error btn-sm gap-1.5 shadow-sm"
          onclick={handleCancel}
          disabled={isBusy}
        >
          <Square class="w-4 h-4 fill-current" />
          <span>Emergency Stop</span>
        </button>
      </div>
    </div>

    <!-- Mission Control Dispatch Grid -->
    <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
      <!-- 1. Goto Mission -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 space-y-3 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between pb-2 border-b border-base-300">
            <div class="flex items-center gap-2 text-sm font-bold text-base-content">
              <Compass class="w-4 h-4 text-primary" />
              <span>Goto Goal</span>
            </div>
            <button class="btn btn-ghost btn-xs text-primary" onclick={handleFillCurrentCoords}>My Coords</button>
          </div>
          <div class="grid grid-cols-3 gap-2 pt-2">
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">X</span>
              <input type="number" bind:value={gotoX} class="input input-xs input-bordered w-full font-mono bg-base-100" />
            </div>
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">Y</span>
              <input type="number" bind:value={gotoY} class="input input-xs input-bordered w-full font-mono bg-base-100" />
            </div>
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">Z</span>
              <input type="number" bind:value={gotoZ} class="input input-xs input-bordered w-full font-mono bg-base-100" />
            </div>
          </div>
        </div>
        <button class="btn btn-sm btn-primary w-full gap-1.5 mt-2" onclick={handleGoto} disabled={isBusy}>
          <Play class="w-3.5 h-3.5" />
          <span>Start Navigation</span>
        </button>
      </div>

      <!-- 2. Mine Mission -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 space-y-3 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between pb-2 border-b border-base-300">
            <div class="flex items-center gap-2 text-sm font-bold text-base-content">
              <Pickaxe class="w-4 h-4 text-warning" />
              <span>Mine Block</span>
            </div>
            <span class="badge badge-warning badge-xs font-mono">Ore Mining</span>
          </div>
          <div class="space-y-2 pt-2">
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">Target Block ID</span>
              <input
                type="text"
                bind:value={mineBlock}
                list="block-suggestions"
                oninput={() => loadBlockSuggestions(mineBlock)}
                class="input input-xs input-bordered w-full font-mono bg-base-100"
                placeholder="Search block in registry..."
              />
              <datalist id="block-suggestions">
                {#each blockSuggestions as blk}
                  <option value={blk}></option>
                {/each}
              </datalist>
            </div>
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">Quantity</span>
              <input type="number" bind:value={mineQuantity} min="1" max="640" class="input input-xs input-bordered w-full font-mono bg-base-100" />
            </div>
          </div>
        </div>
        <button class="btn btn-sm btn-warning w-full gap-1.5 mt-2" onclick={handleMine} disabled={isBusy || !mineBlock.trim()}>
          <Play class="w-3.5 h-3.5" />
          <span>Start Mining</span>
        </button>
      </div>

      <!-- 3. Follow Mission -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 space-y-3 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between pb-2 border-b border-base-300">
            <div class="flex items-center gap-2 text-sm font-bold text-base-content">
              <Users class="w-4 h-4 text-secondary" />
              <span>Follow Entity</span>
            </div>
            <span class="badge badge-secondary badge-xs font-mono">Tracking</span>
          </div>
          <div class="space-y-2 pt-2">
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">Player or Entity Type</span>
              <input
                type="text"
                bind:value={followTarget}
                list="entity-suggestions"
                oninput={() => loadEntitySuggestions(followTarget)}
                class="input input-xs input-bordered w-full font-mono bg-base-100"
                placeholder="Search entity in registry or player name..."
              />
              <datalist id="entity-suggestions">
                {#each entitySuggestions as ent}
                  <option value={ent}></option>
                {/each}
              </datalist>
            </div>
            <p class="text-[10px] text-base-content/50">Follows and matches target movement continuously.</p>
          </div>
        </div>
        <button class="btn btn-sm btn-secondary w-full gap-1.5 mt-2" onclick={handleFollow} disabled={isBusy || !followTarget.trim()}>
          <Play class="w-3.5 h-3.5" />
          <span>Start Follow</span>
        </button>
      </div>

      <!-- 4. Explore Mission -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 space-y-3 flex flex-col justify-between">
        <div>
          <div class="flex items-center justify-between pb-2 border-b border-base-300">
            <div class="flex items-center gap-2 text-sm font-bold text-base-content">
              <Navigation class="w-4 h-4 text-accent" />
              <span>World Explore</span>
            </div>
            <span class="badge badge-accent badge-xs font-mono">Radial</span>
          </div>
          <div class="grid grid-cols-2 gap-2 pt-2">
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">X (optional)</span>
              <input type="number" bind:value={exploreX} placeholder="Auto" class="input input-xs input-bordered w-full font-mono bg-base-100" />
            </div>
            <div>
              <span class="text-[10px] text-base-content/50 uppercase font-semibold">Z (optional)</span>
              <input type="number" bind:value={exploreZ} placeholder="Auto" class="input input-xs input-bordered w-full font-mono bg-base-100" />
            </div>
          </div>
        </div>
        <button class="btn btn-sm btn-accent w-full gap-1.5 mt-2" onclick={handleExplore} disabled={isBusy}>
          <Play class="w-3.5 h-3.5" />
          <span>Launch Exploration</span>
        </button>
      </div>
    </div>

    <!-- Direct Baritone Command Console -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-3">
      <div class="flex items-center gap-2 text-sm font-bold text-base-content">
        <Terminal class="w-4 h-4 text-primary" />
        <span>Direct Baritone Command Console</span>
      </div>
      <form onsubmit={(e) => { e.preventDefault(); handleExecuteCommand(); }} class="flex items-center gap-2">
        <input
          type="text"
          bind:value={rawCommand}
          placeholder="Enter Baritone command without '#' (e.g. mine ancient_debris or goto 200 70 300)..."
          class="input input-sm input-bordered flex-1 bg-base-100 font-mono text-xs"
        />
        <button type="submit" class="btn btn-sm btn-primary gap-1" disabled={isSendingCommand || !rawCommand.trim()}>
          <span>Execute</span>
        </button>
      </form>
    </div>

    <!-- Baritone Settings Inspector -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4">
      <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pb-3 border-b border-base-300">
        <div class="flex items-center gap-2 text-sm font-bold text-base-content">
          <SlidersHorizontal class="w-4 h-4 text-secondary" />
          <span>Baritone Settings Inspector</span>
        </div>
        <div class="relative w-full sm:w-64">
          <Search class="w-3.5 h-3.5 absolute left-3 top-1/2 -translate-y-1/2 text-base-content/50" />
          <input
            type="text"
            bind:value={settingsSearch}
            placeholder="Search settings..."
            class="input input-xs input-bordered w-full pl-8 bg-base-100"
          />
        </div>
      </div>

      <div class="overflow-x-auto max-h-72">
        <table class="table table-xs table-zebra w-full font-mono">
          <thead>
            <tr class="text-base-content/60">
              <th>Setting Name</th>
              <th>Type</th>
              <th>Current Value</th>
              <th class="text-right">Toggle / Action</th>
            </tr>
          </thead>
          <tbody>
            {#if filteredSettings.length === 0}
              <tr>
                <td colspan="4" class="text-center py-6 text-base-content/40 italic">
                  No settings loaded or matched query.
                </td>
              </tr>
            {:else}
              {#each filteredSettings as [name, info]}
                <tr>
                  <td class="font-bold text-base-content">{name}</td>
                  <td class="text-base-content/50">{info.type}</td>
                  <td>
                    {#if typeof info.value === 'boolean'}
                      <span class="badge badge-xs {info.value ? 'badge-success' : 'badge-ghost'}">
                        {info.value ? 'true' : 'false'}
                      </span>
                    {:else}
                      <span class="text-base-content/80">{String(info.value)}</span>
                    {/if}
                  </td>
                  <td class="text-right">
                    {#if typeof info.value === 'boolean'}
                      <input
                        type="checkbox"
                        checked={info.value}
                        class="toggle toggle-xs toggle-primary"
                        onchange={() => handleToggleSetting(name, info.value)}
                      />
                    {:else}
                      <span class="text-base-content/40 italic text-[10px]">Read-only</span>
                    {/if}
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
