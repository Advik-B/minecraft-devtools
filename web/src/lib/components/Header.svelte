<script lang="ts">
  import { appState } from '../stores.svelte'
  import {
    Activity,
    Package,
    Radar,
    Bot,
    Gamepad2,
    Cpu,
    Radio,
    BookOpen,
    Key,
    Image,
    Box
  } from 'lucide-svelte'

  const tabs = [
    { id: 'telemetry', label: 'Telemetry', icon: Activity },
    { id: 'inventory', label: 'Inventory', icon: Package },
    { id: 'radar', label: 'Radar', icon: Radar },
    { id: 'viewer', label: '3D Viewer', icon: Box },
    { id: 'textures', label: 'Textures', icon: Image },
    { id: 'baritone', label: 'Baritone', icon: Bot },
    { id: 'actions', label: 'Controls & Actions', icon: Gamepad2 },
    { id: 'reflection', label: 'Reflection', icon: Cpu },
    { id: 'events', label: 'Events', icon: Radio },
    { id: 'docs', label: 'Docs', icon: BookOpen }
  ] as const
</script>

<header class="w-full">
  <!-- Top Primary Navbar -->
  <div class="navbar bg-base-200 border-b border-base-300 px-4 sm:px-8 min-h-16">
    <div class="navbar-start flex items-center gap-3">
      <div class="flex items-center gap-2.5">
        <img
          src="/icon.svg"
          alt="Minecraft DevTools"
          class="w-9 h-9 rounded-lg shadow-xs [image-rendering:pixelated] border border-base-300 shrink-0"
        />
        <span class="font-bold tracking-tight text-lg text-base-content whitespace-nowrap">Minecraft DevTools</span>
      </div>

      {#if appState.mcdtVersion}
        <span class="badge badge-neutral badge-sm font-mono">{appState.mcdtVersion}</span>
      {/if}
      {#if appState.gameVersion}
        <span class="badge badge-outline badge-sm font-mono hidden md:inline-flex">MC {appState.gameVersion}</span>
      {/if}
    </div>

    <div class="navbar-end flex items-center gap-2 sm:gap-3">
      <!-- Live Status Pill -->
      <div>
        {#if appState.serverOnline}
          <div class="badge badge-soft badge-success gap-2 py-3 px-3 text-xs font-medium">
            <span class="status status-success status-xs"></span>
            <span>{appState.inGame ? 'In World' : 'Main Menu'}</span>
            {#if appState.fps > 0}
              <span class="opacity-60 hidden sm:inline font-mono">({appState.fps} FPS)</span>
            {/if}
          </div>
        {:else}
          <div class="badge badge-soft badge-warning gap-2 py-3 px-3 text-xs font-medium">
            <span class="status status-warning status-xs animate-ping"></span>
            <span>Connecting...</span>
          </div>
        {/if}
      </div>

      <!-- Auth Trigger Button -->
      <button
        class="btn btn-sm gap-1.5 {appState.token ? 'btn-outline btn-success' : 'btn-warning'}"
        onclick={() => appState.tokenModalOpen = true}
      >
        <Key class="w-3.5 h-3.5" />
        <span>{appState.token ? 'Auth OK' : 'Set Token'}</span>
      </button>
    </div>
  </div>

  <!-- Sticky Sub-Nav Tabs Bar -->
  <div class="sticky top-0 z-30 bg-base-100/95 backdrop-blur border-b border-base-300 px-4 sm:px-8">
    <div role="tablist" class="tabs tabs-border overflow-x-auto flex flex-nowrap py-0.5">
      {#each tabs as tab}
        {@const IconComponent = tab.icon}
        <button
          role="tab"
          class="tab gap-2 text-sm font-medium h-12 whitespace-nowrap {appState.activeTab === tab.id ? 'tab-active text-primary font-semibold' : 'text-base-content/70 hover:text-base-content'}"
          aria-selected={appState.activeTab === tab.id}
          onclick={(e) => {
            e.preventDefault()
            appState.activeTab = tab.id
          }}
        >
          <IconComponent class="w-4 h-4" />
          <span>{tab.label}</span>
        </button>
      {/each}
    </div>
  </div>
</header>
