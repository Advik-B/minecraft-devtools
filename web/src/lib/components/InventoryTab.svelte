<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import { getInventory, getScreen, clickScreenSlot, closeScreen } from '../api'
  import { toastStore } from '../toast.svelte'
  import type { InventoryResponse, ScreenResponse, SlotInfo } from '../types'
  import {
    Package,
    Shield,
    Sparkles,
    Search,
    RefreshCw,
    XCircle,
    Eye,
    Key,
    Hand,
    Layers,
    Box
  } from 'lucide-svelte'

  let inventory = $state<InventoryResponse | null>(null)
  let screen = $state<ScreenResponse | null>(null)
  let isLoading = $state(false)
  let pollInterval: number | null = null
  let searchQuery = $state('')
  let selectedCategory = $state<'all' | 'hotbar' | 'main' | 'equipment' | 'container'>('all')
  let showEmptySlots = $state(false)
  let selectedSlotItem = $state<SlotInfo | null>(null)

  async function loadData() {
    if (!appState.token) return
    try {
      const [inv, scr] = await Promise.all([
        getInventory().catch(() => null),
        getScreen().catch(() => null)
      ])
      if (inv) inventory = inv
      screen = scr
    } catch {
      // ignore
    }
  }

  onMount(() => {
    loadData()
    pollInterval = window.setInterval(loadData, 1500)
  })

  onDestroy(() => {
    if (pollInterval !== null) {
      clearInterval(pollInterval)
      pollInterval = null
    }
  })

  async function handleRefresh() {
    isLoading = true
    await loadData()
    isLoading = false
    toastStore.show('Inventory refreshed', 'info', 1000)
  }

  async function handleCloseScreen() {
    try {
      const ok = await closeScreen()
      if (ok) {
        toastStore.show('Container closed', 'success')
        await loadData()
      }
    } catch {
      toastStore.show('Failed to close container', 'error')
    }
  }

  async function handleSlotClick(slotIndex: number) {
    try {
      const ok = await clickScreenSlot(slotIndex)
      if (ok) {
        toastStore.show(`Clicked slot #${slotIndex}`, 'info', 1000)
        await loadData()
      }
    } catch {
      toastStore.show(`Failed slot action #${slotIndex}`, 'error')
    }
  }

  function getRarityClass(rarity?: string): { border: string; text: string; badge: string } {
    switch (rarity?.toLowerCase()) {
      case 'epic':
        return { border: 'border-secondary/60 hover:border-secondary', text: 'text-secondary font-bold', badge: 'badge-secondary' }
      case 'rare':
        return { border: 'border-primary/60 hover:border-primary', text: 'text-primary font-bold', badge: 'badge-primary' }
      case 'uncommon':
        return { border: 'border-warning/50 hover:border-warning', text: 'text-warning font-semibold', badge: 'badge-warning' }
      default:
        return { border: 'border-base-300 hover:border-base-content/40', text: 'text-base-content font-medium', badge: 'badge-ghost' }
    }
  }

  const allItems = $derived.by(() => {
    if (!inventory) return []
    const items: Array<{ category: 'hotbar' | 'main' | 'armor' | 'offhand'; item: SlotInfo }> = []

    inventory.main?.forEach((slot, idx) => {
      const cat = idx < 9 ? 'hotbar' : 'main'
      items.push({ category: cat, item: slot })
    })

    inventory.armor?.forEach((slot) => {
      items.push({ category: 'armor', item: slot })
    })

    inventory.offhand?.forEach((slot) => {
      items.push({ category: 'offhand', item: slot })
    })

    return items
  })

  const filteredItems = $derived.by(() => {
    const list = allItems
    return list.filter(({ category, item }) => {
      if (!showEmptySlots && item.empty) return false

      if (selectedCategory === 'hotbar' && category !== 'hotbar') return false
      if (selectedCategory === 'main' && category !== 'main') return false
      if (selectedCategory === 'equipment' && category !== 'armor' && category !== 'offhand') return false

      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase().trim()
        const matchName = item.name?.toLowerCase().includes(q)
        const matchId = item.id?.toLowerCase().includes(q)
        if (!matchName && !matchId) return false
      }

      return true
    })
  })

  const activeHotbarItem = $derived.by(() => {
    if (!inventory?.main || inventory.selectedSlot === undefined) return null
    return inventory.main[inventory.selectedSlot]
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
          <p class="text-sm text-base-content/70">Set your Bearer token to view live player inventory and containers.</p>
        </div>
      </div>
      <button class="btn btn-warning btn-sm shrink-0" onclick={() => appState.tokenModalOpen = true}>Set Token</button>
    </div>
  {:else if !appState.inGame}
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-8 text-center space-y-3">
      <div class="w-12 h-12 mx-auto rounded-full bg-base-300 flex items-center justify-center text-base-content/50">
        <Package class="w-6 h-6" />
      </div>
      <h3 class="font-bold text-lg text-base-content">No Active World Loaded</h3>
      <p class="text-sm text-base-content/60 max-w-md mx-auto">
        Join a singleplayer world or multiplayer server in Minecraft to inspect your live inventory and container storage.
      </p>
    </div>
  {:else}
    <!-- Equipment & Active Held Item Top Ribbon -->
    <div class="grid grid-cols-1 lg:grid-cols-3 gap-4">
      <!-- Active Hand & Hotbar Highlight -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4">
        <div class="flex items-center justify-between pb-2 mb-2 border-b border-base-300">
          <div class="flex items-center gap-2 text-sm font-semibold text-base-content">
            <Hand class="w-4 h-4 text-primary" />
            <span>Held in Main Hand</span>
          </div>
          <span class="badge badge-primary badge-sm font-mono">Slot {inventory ? (inventory.selectedSlot + 1) : 1}/9</span>
        </div>
        {#if activeHotbarItem && !activeHotbarItem.empty}
          {@const rarity = getRarityClass(activeHotbarItem.rarity)}
          <div class="flex items-center gap-3">
            <div class="w-12 h-12 rounded-xl bg-base-300/80 border {rarity.border} flex items-center justify-center font-bold text-lg shadow-inner">
              <Box class="w-6 h-6 text-primary" />
            </div>
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2">
                <span class="font-bold text-sm truncate {rarity.text}">{activeHotbarItem.name || activeHotbarItem.id}</span>
                {#if activeHotbarItem.count && activeHotbarItem.count > 1}
                  <span class="badge {rarity.badge} badge-xs font-mono">x{activeHotbarItem.count}</span>
                {/if}
              </div>
              <p class="text-xs text-base-content/60 font-mono truncate">{activeHotbarItem.id}</p>
              {#if activeHotbarItem.maxDamage}
                <div class="mt-1 w-full bg-base-300 rounded-full h-1.5 overflow-hidden">
                  <div
                    class="bg-success h-1.5 rounded-full"
                    style="width: {Math.max(0, 100 - (((activeHotbarItem.damage || 0) / activeHotbarItem.maxDamage) * 100))}%"
                  ></div>
                </div>
              {/if}
            </div>
          </div>
        {:else}
          <div class="text-xs text-base-content/50 italic py-2">Empty hand</div>
        {/if}
      </div>

      <!-- Armor & Offhand Summary -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4 lg:col-span-2">
        <div class="flex items-center justify-between pb-2 mb-2 border-b border-base-300">
          <div class="flex items-center gap-2 text-sm font-semibold text-base-content">
            <Shield class="w-4 h-4 text-secondary" />
            <span>Equipped Gear</span>
          </div>
          <span class="text-xs text-base-content/60">Armor & Offhand</span>
        </div>

        <div class="grid grid-cols-2 sm:grid-cols-5 gap-2">
          {#each ['Boots', 'Leggings', 'Chestplate', 'Helmet'] as piece, idx}
            {@const armorSlot = inventory?.armor?.[idx]}
            {@const rarity = getRarityClass(armorSlot?.rarity)}
            <div class="p-2 rounded-lg bg-base-300/50 border {rarity.border} flex flex-col justify-between min-h-16">
              <div class="flex items-center justify-between text-[11px] text-base-content/60">
                <span class="uppercase tracking-wider font-semibold">{piece}</span>
              </div>
              {#if armorSlot && !armorSlot.empty}
                <span class="font-bold text-xs truncate {rarity.text}">{armorSlot.name || armorSlot.id}</span>
                {#if armorSlot.maxDamage}
                  <span class="text-[10px] text-base-content/60 font-mono">
                    {armorSlot.maxDamage - (armorSlot.damage || 0)}/{armorSlot.maxDamage}
                  </span>
                {/if}
              {:else}
                <span class="text-[11px] text-base-content/40 italic">None</span>
              {/if}
            </div>
          {/each}

          <!-- Offhand -->
          {#each [inventory?.offhand?.[0]] as offSlot}
            {@const offRarity = getRarityClass(offSlot?.rarity)}
            <div class="p-2 rounded-lg bg-base-300/50 border {offRarity.border} flex flex-col justify-between min-h-16">
              <div class="flex items-center justify-between text-[11px] text-base-content/60">
                <span class="uppercase tracking-wider font-semibold">Offhand</span>
              </div>
              {#if offSlot && !offSlot.empty}
                <span class="font-bold text-xs truncate {offRarity.text}">{offSlot.name || offSlot.id}</span>
                {#if offSlot.count && offSlot.count > 1}
                  <span class="text-[10px] text-base-content/70 font-mono">x{offSlot.count}</span>
                {/if}
              {:else}
                <span class="text-[11px] text-base-content/40 italic">Empty</span>
              {/if}
            </div>
          {/each}
        </div>
      </div>
    </div>

    <!-- Active Open Container / Screen Section -->
    {#if screen && screen.open}
      <div class="card bg-base-200 border-2 border-primary/40 shadow-md rounded-box p-5 space-y-4">
        <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pb-3 border-b border-base-300">
          <div class="flex items-center gap-2.5">
            <div class="p-2 bg-primary/20 text-primary rounded-lg">
              <Layers class="w-5 h-5" />
            </div>
            <div>
              <h3 class="font-bold text-base text-base-content flex items-center gap-2">
                <span>{screen.title || 'Open Container Screen'}</span>
                <span class="badge badge-primary badge-sm font-mono">{screen.container?.type || 'Container'}</span>
              </h3>
              <p class="text-xs text-base-content/60">
                {screen.container?.slotCount || 0} slots active in this window
              </p>
            </div>
          </div>
          <button class="btn btn-sm btn-error btn-outline gap-1.5" onclick={handleCloseScreen}>
            <XCircle class="w-4 h-4" />
            <span>Close Screen</span>
          </button>
        </div>

        <!-- Container Slots Grid -->
        {#if screen.container?.slots && screen.container.slots.length > 0}
          <div class="grid grid-cols-2 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-9 gap-2">
            {#each screen.container.slots as slotDetail}
              {@const itm = slotDetail.item}
              {@const rarity = getRarityClass(itm?.rarity)}
              <button
                class="p-2.5 rounded-xl bg-base-100 border {rarity.border} hover:bg-base-300 transition-all text-left flex flex-col justify-between h-20 relative group cursor-pointer shadow-xs"
                onclick={() => handleSlotClick(slotDetail.index)}
                title="Click slot #{slotDetail.index} ({itm?.name || 'Empty'})"
              >
                <div class="flex items-center justify-between w-full text-[10px] text-base-content/50">
                  <span class="font-mono">#{slotDetail.index}</span>
                  {#if itm && !itm.empty && itm.count && itm.count > 1}
                    <span class="badge {rarity.badge} badge-xs font-mono font-bold">x{itm.count}</span>
                  {/if}
                </div>
                {#if itm && !itm.empty}
                  <div class="truncate font-semibold text-xs {rarity.text}">
                    {itm.name || itm.id}
                  </div>
                {:else}
                  <div class="text-[11px] text-base-content/30 italic">Empty</div>
                {/if}
              </button>
            {/each}
          </div>
        {/if}
      </div>
    {/if}

    <!-- Main Inventory Cards & Controls -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4">
      <!-- Toolbar & Filters -->
      <div class="flex flex-col md:flex-row items-stretch md:items-center justify-between gap-3 pb-3 border-b border-base-300">
        <!-- Search Bar -->
        <div class="relative flex-1 max-w-md">
          <Search class="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-base-content/50" />
          <input
            type="text"
            bind:value={searchQuery}
            placeholder="Search items by name or ID (e.g. sword, iron)..."
            class="input input-sm input-bordered w-full pl-9 bg-base-100"
          />
          {#if searchQuery}
            <button
              class="absolute right-2.5 top-1/2 -translate-y-1/2 text-base-content/50 hover:text-base-content"
              onclick={() => searchQuery = ''}
            >
              &times;
            </button>
          {/if}
        </div>

        <!-- Filter Categories & Actions -->
        <div class="flex flex-wrap items-center gap-2">
          <div role="tablist" class="tabs tabs-box tabs-sm bg-base-300/60 p-1">
            <button
              role="tab"
              class="tab {selectedCategory === 'all' ? 'tab-active font-semibold' : ''}"
              onclick={() => selectedCategory = 'all'}
            >
              All
            </button>
            <button
              role="tab"
              class="tab {selectedCategory === 'hotbar' ? 'tab-active font-semibold' : ''}"
              onclick={() => selectedCategory = 'hotbar'}
            >
              Hotbar (9)
            </button>
            <button
              role="tab"
              class="tab {selectedCategory === 'main' ? 'tab-active font-semibold' : ''}"
              onclick={() => selectedCategory = 'main'}
            >
              Storage (27)
            </button>
            <button
              role="tab"
              class="tab {selectedCategory === 'equipment' ? 'tab-active font-semibold' : ''}"
              onclick={() => selectedCategory = 'equipment'}
            >
              Gear (5)
            </button>
          </div>

          <label class="label cursor-pointer gap-2 py-0">
            <input type="checkbox" bind:checked={showEmptySlots} class="checkbox checkbox-xs checkbox-primary" />
            <span class="label-text text-xs text-base-content/70">Show empty</span>
          </label>

          <button
            class="btn btn-sm btn-ghost gap-1.5"
            onclick={handleRefresh}
            disabled={isLoading}
          >
            <RefreshCw class="w-3.5 h-3.5 {isLoading ? 'animate-spin' : ''}" />
            <span class="hidden sm:inline">Refresh</span>
          </button>
        </div>
      </div>

      <!-- Item Card Grid -->
      {#if filteredItems.length === 0}
        <div class="py-12 text-center text-base-content/50 space-y-2">
          <Package class="w-8 h-8 mx-auto opacity-40" />
          <p class="text-sm">No items match your query or filters.</p>
        </div>
      {:else}
        <div class="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-6 gap-3">
          {#each filteredItems as { category, item }}
            {@const rarity = getRarityClass(item.rarity)}
            <button
              type="button"
              class="card bg-base-100 border {rarity.border} hover:shadow-md transition-all rounded-box p-3 space-y-2 flex flex-col justify-between cursor-pointer text-left w-full"
              onclick={() => selectedSlotItem = item}
            >
              <div>
                <div class="flex items-center justify-between pb-1">
                  <span class="badge badge-ghost badge-xs font-mono uppercase text-[9px]">{category} #{item.slot}</span>
                  {#if item.count && item.count > 1}
                    <span class="badge {rarity.badge} badge-xs font-mono font-bold">x{item.count}</span>
                  {/if}
                </div>
                <div class="font-bold text-xs truncate mt-1 {rarity.text}" title={item.name || item.id}>
                  {item.empty ? 'Empty Slot' : (item.name || item.id)}
                </div>
                {#if !item.empty && item.id}
                  <div class="text-[10px] text-base-content/50 font-mono truncate" title={item.id}>
                    {item.id.replace('minecraft:', '')}
                  </div>
                {/if}
              </div>

              {#if !item.empty && item.maxDamage}
                <div class="space-y-1 pt-1">
                  <div class="flex justify-between text-[9px] text-base-content/60 font-mono">
                    <span>Durability</span>
                    <span>{item.maxDamage - (item.damage || 0)}/{item.maxDamage}</span>
                  </div>
                  <div class="w-full bg-base-300 rounded-full h-1 overflow-hidden">
                    <div
                      class="bg-success h-1 rounded-full"
                      style="width: {Math.max(0, 100 - (((item.damage || 0) / item.maxDamage) * 100))}%"
                    ></div>
                  </div>
                </div>
              {/if}
            </button>
          {/each}
        </div>
      {/if}
    </div>

    <!-- Item Inspector Modal (when clicked) -->
    {#if selectedSlotItem && !selectedSlotItem.empty}
      <div class="modal modal-open">
        <div class="modal-box max-w-md bg-base-200 border border-base-300">
          <div class="flex items-start justify-between pb-3 border-b border-base-300">
            <div>
              <div class="flex items-center gap-2">
                <h3 class="font-bold text-base text-base-content">{selectedSlotItem.name || selectedSlotItem.id}</h3>
                {#if selectedSlotItem.rarity}
                  <span class="badge {getRarityClass(selectedSlotItem.rarity).badge} badge-xs uppercase font-mono font-semibold">
                    {selectedSlotItem.rarity}
                  </span>
                {/if}
              </div>
              <p class="text-xs font-mono text-base-content/60">{selectedSlotItem.id}</p>
            </div>
            <button class="btn btn-sm btn-circle btn-ghost" onclick={() => selectedSlotItem = null}>&times;</button>
          </div>

          <div class="py-4 space-y-3 text-xs">
            <div class="grid grid-cols-2 gap-2">
              <div class="p-2.5 rounded-lg bg-base-100 border border-base-300">
                <span class="text-base-content/60 block">Slot Number</span>
                <span class="font-mono font-bold text-sm">#{selectedSlotItem.slot}</span>
              </div>
              <div class="p-2.5 rounded-lg bg-base-100 border border-base-300">
                <span class="text-base-content/60 block">Stack Count</span>
                <span class="font-mono font-bold text-sm">{selectedSlotItem.count || 1}</span>
              </div>
            </div>

            {#if selectedSlotItem.maxDamage}
              <div class="p-2.5 rounded-lg bg-base-100 border border-base-300 space-y-1">
                <div class="flex justify-between text-base-content/70">
                  <span>Durability</span>
                  <span class="font-mono font-bold">{selectedSlotItem.maxDamage - (selectedSlotItem.damage || 0)} / {selectedSlotItem.maxDamage}</span>
                </div>
                <div class="w-full bg-base-300 rounded-full h-2 overflow-hidden">
                  <div
                    class="bg-success h-2 rounded-full"
                    style="width: {Math.max(0, 100 - (((selectedSlotItem.damage || 0) / selectedSlotItem.maxDamage) * 100))}%"
                  ></div>
                </div>
              </div>
            {/if}

            {#if selectedSlotItem.components && Object.keys(selectedSlotItem.components).length > 0}
              <div class="p-2.5 rounded-lg bg-base-100 border border-base-300 space-y-1">
                <span class="text-base-content/60 font-semibold block">NBT / Components</span>
                <pre class="bg-base-300/60 p-2 rounded text-[11px] font-mono overflow-x-auto max-h-36">{JSON.stringify(selectedSlotItem.components, null, 2)}</pre>
              </div>
            {/if}
          </div>

          <div class="modal-action">
            <button class="btn btn-sm btn-neutral" onclick={() => selectedSlotItem = null}>Close</button>
          </div>
        </div>
      </div>
    {/if}
  {/if}
</div>
