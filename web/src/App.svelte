<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from './lib/stores.svelte'
  import { getHealth } from './lib/api'
  import { eventStream } from './lib/ws'
  import Header from './lib/components/Header.svelte'
  import TokenModal from './lib/components/TokenModal.svelte'
  import TelemetryTab from './lib/components/TelemetryTab.svelte'
  import EventsTab from './lib/components/EventsTab.svelte'
  import ActionsTab from './lib/components/ActionsTab.svelte'
  import DocsTab from './lib/components/DocsTab.svelte'

  onMount(() => {
    getHealth().catch(() => null)
    if (appState.token) {
      eventStream.connect()
    }
  })

  onDestroy(() => {
    eventStream.disconnect()
  })
</script>

<Header />

<main class="container">
  {#if appState.activeTab === 'telemetry'}
    <TelemetryTab />
  {:else if appState.activeTab === 'events'}
    <EventsTab />
  {:else if appState.activeTab === 'actions'}
    <ActionsTab />
  {:else if appState.activeTab === 'docs'}
    <DocsTab />
  {/if}
</main>

<footer class="container">
  <small>
    Minecraft DevTools (MCDT) &bull; Fabric 1.21.4 &bull; 
    <a href="/docs/scalar">Scalar Docs</a> &bull; 
    <a href="/docs/redoc">ReDoc</a> &bull; 
    <a href="/openapi.json">OpenAPI Spec</a>
  </small>
</footer>

<TokenModal />
