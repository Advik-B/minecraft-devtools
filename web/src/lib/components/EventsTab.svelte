<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from '../stores.svelte'
  import { eventStream } from '../ws'
  import type { GameEvent } from '../types'

  let events = $state<GameEvent[]>([])
  let isPaused = $state(false)
  let filterTopic = $state('all')
  let searchQuery = $state('')
  let includeTicks = $state(false)
  let expandedRows = $state<Record<string, boolean>>({})
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
</script>

<article>
  <header>
    <nav>
      <ul>
        <li><strong>Realtime Event Stream</strong></li>
        <li>
          {#if appState.wsStatus === 'connected'}
            <ins>connected</ins>
          {:else}
            <del>{appState.wsStatus}</del>
          {/if}
          <small>({filteredEvents().length} events)</small>
        </li>
      </ul>
      <ul>
        <li>
          <label>
            <input type="checkbox" bind:checked={includeTicks} role="switch" />
            Include Ticks
          </label>
        </li>
      </ul>
    </nav>
  </header>

  <!-- Filter & Controls Toolbar -->
  <div role="group">
    <input
      type="search"
      bind:value={searchQuery}
      placeholder="Search payload..."
    />
    <select bind:value={filterTopic}>
      {#each topicOptions as opt}
        <option value={opt.value}>{opt.label}</option>
      {/each}
    </select>
    <button class={isPaused ? 'contrast' : 'secondary'} onclick={() => isPaused = !isPaused}>
      {isPaused ? 'Resume' : 'Pause'}
    </button>
    <button class="secondary outline" onclick={() => events = []}>
      Clear
    </button>
  </div>

  <!-- Events Table -->
  {#if filteredEvents().length === 0}
    <p><small>No events captured yet. Game events (chat, screens, player actions) will appear here as they occur.</small></p>
  {:else}
    <figure>
      <table class="striped">
        <thead>
          <tr>
            <th scope="col">Time</th>
            <th scope="col">Topic</th>
            <th scope="col">Payload</th>
          </tr>
        </thead>
        <tbody>
          {#each filteredEvents() as ev (ev.id)}
            <tr onclick={() => toggleRow(ev.id)} style="cursor: pointer;">
              <td><code>{formatTime(ev.timestamp)}</code></td>
              <td><mark>{ev.topic}</mark></td>
              <td>
                <small>{JSON.stringify(ev.data).substring(0, 100)}</small>
                {#if expandedRows[ev.id]}
                  <pre><code>{JSON.stringify(ev.data, null, 2)}</code></pre>
                {/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </figure>
  {/if}
</article>
