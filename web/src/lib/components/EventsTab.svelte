<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import { eventStream } from '../ws'
  import { toastStore } from '../toast.svelte'
  import type { GameEvent } from '../types'
  import {
    Radio,
    Search,
    Play,
    Pause,
    Trash2,
    Copy,
    Check,
    ChevronDown,
    ChevronRight,
    Inbox
  } from 'lucide-svelte'

  let events = $state<GameEvent[]>([])
  let isPaused = $state(false)
  let filterTopic = $state('all')
  let searchQuery = $state('')
  let includeTicks = $state(false)
  let expandedRows = $state<Record<string, boolean>>({})
  let copiedId = $state<string | null>(null)
  let unsubscribe: (() => void) | null = null

  const topicOptions = [
    { value: 'all', label: 'All Topics' },
    { value: 'chat', label: 'chat' },
    { value: 'screen', label: 'screen.*' },
    { value: 'player', label: 'player.*' },
    { value: 'world', label: 'world.*' },
    { value: 'entity', label: 'entity.*' },
    { value: 'block', label: 'block.*' },
    { value: 'packet', label: 'packet.*' },
    { value: 'tick', label: 'tick' }
  ]

  $effect(() => {
    eventStream.setSubscribeTicks(includeTicks)
  })

  onMount(() => {
    if (typeof window !== 'undefined' && (window as unknown as { __MOCK_EVENTS__?: GameEvent[] }).__MOCK_EVENTS__) {
      events = (window as unknown as { __MOCK_EVENTS__: GameEvent[] }).__MOCK_EVENTS__
    }
    unsubscribe = eventStream.subscribe((event) => {
      if (!isPaused) {
        events = [event, ...events.slice(0, 199)]
      }
    })
  })

  onDestroy(() => {
    if (unsubscribe) {
      unsubscribe()
      unsubscribe = null
    }
  })

  const filteredEvents = $derived(() => {
    return events.filter((ev) => {
      if (filterTopic !== 'all') {
        if (!ev.topic.startsWith(filterTopic)) return false
      }
      if (searchQuery.trim()) {
        const q = searchQuery.toLowerCase()
        const topicMatch = ev.topic.toLowerCase().includes(q)
        const dataMatch = JSON.stringify(ev.data).toLowerCase().includes(q)
        if (!topicMatch && !dataMatch) return false
      }
      return true
    })
  })

  function toggleRow(id: string) {
    expandedRows[id] = !expandedRows[id]
  }

  function formatTime(ts: number): string {
    const d = new Date(ts)
    return d.toLocaleTimeString() + '.' + String(d.getMilliseconds()).padStart(3, '0')
  }

  function getTopicBadgeClass(topic: string): string {
    if (topic.startsWith('chat')) return 'badge-success'
    if (topic.startsWith('player')) return 'badge-info'
    if (topic.startsWith('world')) return 'badge-primary'
    if (topic.startsWith('entity')) return 'badge-accent'
    if (topic.startsWith('block')) return 'badge-secondary'
    if (topic.startsWith('packet')) return 'badge-warning'
    return 'badge-neutral'
  }

  function copyPayload(id: string, data: unknown, e: MouseEvent) {
    e.stopPropagation()
    try {
      navigator.clipboard?.writeText(JSON.stringify(data, null, 2)).catch(() => {})
    } catch {
      // ignore clipboard error in headless environments
    }
    copiedId = id
    toastStore.show('Event JSON payload copied to clipboard!', 'success')
    setTimeout(() => {
      if (copiedId === id) copiedId = null
    }, 1500)
  }
</script>

<div class="card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
  <div class="card-body p-4 sm:p-6 space-y-4">
    
    <!-- Header with Stream Status & Tick Toggle -->
    <div class="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-3 border-b border-base-300">
      <div class="flex items-center gap-3">
        <div class="p-2.5 bg-primary/10 rounded-xl text-primary">
          <Radio class="w-5 h-5 {appState.wsStatus === 'connected' && !isPaused ? 'animate-pulse' : ''}" />
        </div>
        <div>
          <h2 class="card-title text-base font-bold text-base-content">Realtime Event Stream</h2>
          <div class="flex items-center gap-2 mt-0.5">
            <span class="badge badge-soft {appState.wsStatus === 'connected' ? 'badge-success' : 'badge-warning'} badge-xs gap-1 py-2">
              <span class="status {appState.wsStatus === 'connected' ? 'status-success' : 'status-warning'} status-xs"></span>
              <span>{appState.wsStatus}</span>
            </span>
            <span class="text-xs text-base-content/60 font-mono">
              ({filteredEvents().length} events)
            </span>
            {#if isPaused}
              <span class="badge badge-soft badge-warning badge-xs">Stream Paused</span>
            {/if}
          </div>
        </div>
      </div>

      <div class="flex items-center gap-3">
        <label class="label cursor-pointer gap-2 bg-base-300/60 px-3 py-1.5 rounded-lg">
          <span class="label-text text-xs font-medium">Include Ticks</span>
          <input type="checkbox" bind:checked={includeTicks} class="toggle toggle-xs toggle-primary" />
        </label>
      </div>
    </div>

    <!-- Filter & Controls Toolbar -->
    <div class="flex flex-col md:flex-row items-stretch md:items-center gap-2">
      <!-- Search Input -->
      <div class="relative flex-1">
        <Search class="w-4 h-4 text-base-content/50 absolute left-3 top-1/2 -translate-y-1/2 pointer-events-none" />
        <input
          type="search"
          bind:value={searchQuery}
          placeholder="Filter payload or topic..."
          class="input input-sm input-bordered w-full pl-9 bg-base-100"
        />
      </div>

      <!-- Controls Group -->
      <div class="join w-full md:w-auto">
        <select bind:value={filterTopic} class="select select-sm select-bordered join-item bg-base-100 text-xs">
          {#each topicOptions as opt}
            <option value={opt.value}>{opt.label}</option>
          {/each}
        </select>

        <button
          class="btn btn-sm join-item gap-1 {isPaused ? 'btn-warning' : 'btn-outline'}"
          onclick={() => isPaused = !isPaused}
          title={isPaused ? 'Resume stream' : 'Pause stream'}
        >
          {#if isPaused}
            <Play class="w-3.5 h-3.5" />
            <span>Resume</span>
          {:else}
            <Pause class="w-3.5 h-3.5" />
            <span>Pause</span>
          {/if}
        </button>

        <button
          class="btn btn-sm btn-ghost join-item text-error/80 hover:text-error gap-1"
          onclick={() => events = []}
          title="Clear events"
        >
          <Trash2 class="w-3.5 h-3.5" />
          <span class="hidden sm:inline">Clear</span>
        </button>
      </div>
    </div>

    <!-- Events Table -->
    {#if filteredEvents().length === 0}
      <div class="text-center py-12 px-4 rounded-xl border border-dashed border-base-300 bg-base-100/50">
        <div class="w-12 h-12 mx-auto rounded-full bg-base-200 flex items-center justify-center text-base-content/40 mb-3">
          <Inbox class="w-6 h-6" />
        </div>
        <h3 class="font-medium text-sm text-base-content">No events match your criteria</h3>
        <p class="text-xs text-base-content/60 mt-1 max-w-sm mx-auto">
          Game events (chat, screens, player actions, packets) will appear here live as they are received.
        </p>
      </div>
    {:else}
      <div class="overflow-x-auto rounded-xl border border-base-300 bg-base-100">
        <table class="table table-zebra table-sm w-full">
          <thead>
            <tr class="bg-base-200/80 text-base-content/70 text-xs">
              <th class="w-8"></th>
              <th class="w-32">Timestamp</th>
              <th class="w-40">Topic</th>
              <th>Payload Preview</th>
              <th class="w-16 text-right">Action</th>
            </tr>
          </thead>
          <tbody>
            {#each filteredEvents() as ev (ev.id)}
              {@const isExpanded = !!expandedRows[ev.id]}
              <tr
                class="hover:bg-base-200/50 cursor-pointer transition-colors"
                onclick={() => toggleRow(ev.id)}
              >
                <td class="text-base-content/50 pl-3">
                  {#if isExpanded}
                    <ChevronDown class="w-3.5 h-3.5" />
                  {:else}
                    <ChevronRight class="w-3.5 h-3.5" />
                  {/if}
                </td>
                <td class="font-mono text-xs opacity-75 whitespace-nowrap">
                  {formatTime(ev.timestamp)}
                </td>
                <td>
                  <span class="badge badge-sm badge-soft {getTopicBadgeClass(ev.topic)} font-mono text-[11px]">
                    {ev.topic}
                  </span>
                </td>
                <td class="max-w-xs md:max-w-md truncate font-mono text-xs text-base-content/80">
                  {JSON.stringify(ev.data)}
                </td>
                <td class="text-right pr-3" onclick={(e) => e.stopPropagation()}>
                  <button
                    class="btn btn-ghost btn-xs btn-square"
                    title="Copy JSON payload"
                    onclick={(e) => copyPayload(ev.id, ev.data, e)}
                  >
                    {#if copiedId === ev.id}
                      <Check class="w-3 h-3 text-success" />
                    {:else}
                      <Copy class="w-3 h-3" />
                    {/if}
                  </button>
                </td>
              </tr>
              {#if isExpanded}
                <tr class="bg-base-200/40">
                  <td colspan="5" class="p-3">
                    <div class="mockup-code text-xs bg-base-300 text-base-content border border-base-300/80 rounded-lg">
                      <pre><code>{JSON.stringify(ev.data, null, 2)}</code></pre>
                    </div>
                  </td>
                </tr>
              {/if}
            {/each}
          </tbody>
        </table>
      </div>
    {/if}

  </div>
</div>
