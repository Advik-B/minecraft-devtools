<script lang="ts">
  import { onMount, onDestroy } from 'svelte'
  import { appState } from './lib/stores.svelte'
  import { getHealth } from './lib/api'
  import { eventStream } from './lib/ws'
  import Header from './lib/components/Header.svelte'
  import TokenModal from './lib/components/TokenModal.svelte'
  import ToastContainer from './lib/components/ToastContainer.svelte'
  import TelemetryTab from './lib/components/TelemetryTab.svelte'
  import InventoryTab from './lib/components/InventoryTab.svelte'
  import RadarTab from './lib/components/RadarTab.svelte'
  import BaritoneTab from './lib/components/BaritoneTab.svelte'
  import ActionsTab from './lib/components/ActionsTab.svelte'
  import ReflectionTab from './lib/components/ReflectionTab.svelte'
  import EventsTab from './lib/components/EventsTab.svelte'
  import DocsTab from './lib/components/DocsTab.svelte'
  import TexturesTab from './lib/components/TexturesTab.svelte'
  import ViewerTab from './lib/components/ViewerTab.svelte'

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

<main class="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">
  {#if appState.activeTab === 'telemetry'}
    <TelemetryTab />
  {:else if appState.activeTab === 'inventory'}
    <InventoryTab />
  {:else if appState.activeTab === 'radar'}
    <RadarTab />
  {:else if appState.activeTab === 'viewer'}
    <ViewerTab />
  {:else if appState.activeTab === 'textures'}
    <TexturesTab />
  {:else if appState.activeTab === 'baritone'}
    <BaritoneTab />
  {:else if appState.activeTab === 'actions'}
    <ActionsTab />
  {:else if appState.activeTab === 'reflection'}
    <ReflectionTab />
  {:else if appState.activeTab === 'events'}
    <EventsTab />
  {:else if appState.activeTab === 'docs'}
    <DocsTab />
  {/if}
</main>

<footer class="bg-base-200 border-t border-base-300 py-4 px-4 sm:px-8 mt-auto">
  <div class="max-w-7xl mx-auto flex flex-col sm:flex-row items-center justify-between gap-3 text-xs text-base-content/60">
    <div class="flex items-center gap-2">
      <span class="font-semibold text-base-content/80">Minecraft DevTools</span>
      <span>&bull;</span>
      <span>Fabric 1.21.4</span>
    </div>
    <div class="flex items-center gap-4">
      <a href="/docs/scalar" class="link link-hover hover:text-primary transition-colors">Scalar Docs</a>
      <span>&bull;</span>
      <a href="/docs/redoc" class="link link-hover hover:text-primary transition-colors">ReDoc</a>
      <span>&bull;</span>
      <a href="/openapi.json" class="link link-hover hover:text-primary transition-colors">OpenAPI Spec</a>
    </div>
  </div>
</footer>

<TokenModal />
<ToastContainer />
