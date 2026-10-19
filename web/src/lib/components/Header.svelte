<script lang="ts">
  import { appState } from '../stores.svelte'

  const tabs = [
    { id: 'telemetry', label: 'Telemetry' },
    { id: 'events', label: 'Event Stream' },
    { id: 'actions', label: 'Quick Actions' },
    { id: 'docs', label: 'API Docs' }
  ] as const
</script>

<header class="container">
  <nav>
    <ul>
      <li>
        <strong>MCDT</strong>
        {#if appState.mcdtVersion}
          <kbd>{appState.mcdtVersion}</kbd>
        {/if}
        {#if appState.gameVersion}
          <small>MC {appState.gameVersion}</small>
        {/if}
      </li>
    </ul>

    <ul>
      {#each tabs as tab}
        <li>
          <a
            href="#{tab.id}"
            aria-current={appState.activeTab === tab.id ? 'page' : undefined}
            onclick={(e) => { e.preventDefault(); appState.activeTab = tab.id; }}
          >
            {tab.label}
          </a>
        </li>
      {/each}
    </ul>

    <ul>
      <li>
        {#if appState.serverOnline}
          <ins>{appState.inGame ? 'In World' : 'Main Menu'}</ins>
          {#if appState.fps > 0}
            <small>({appState.fps} FPS)</small>
          {/if}
        {:else}
          <del>Connecting...</del>
        {/if}
      </li>
      <li>
        <button
          class={appState.token ? 'outline' : 'secondary'}
          onclick={() => appState.tokenModalOpen = true}
        >
          {appState.token ? 'Auth OK' : 'Set Token'}
        </button>
      </li>
    </ul>
  </nav>
</header>
