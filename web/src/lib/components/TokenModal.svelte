<script lang="ts">
  import { appState } from '../stores.svelte'
  import { testToken } from '../api'
  import { toastStore } from '../toast.svelte'
  import { Key, ShieldCheck, Info, X } from 'lucide-svelte'

  let inputToken = $state(appState.token)
  let validating = $state(false)
  let errorMessage = $state('')

  let serverUnreachable = $state(false)

  $effect(() => {
    if (appState.tokenModalOpen) {
      inputToken = appState.token
      errorMessage = ''
      serverUnreachable = false
    }
  })

  async function handleSave(force = false) {
    const trimmed = inputToken.trim()
    if (!trimmed) {
      errorMessage = 'Please enter a token.'
      return
    }

    if (!force) {
      validating = true
      errorMessage = ''
      serverUnreachable = false

      const valid = await testToken(trimmed)
      validating = false

      if (!valid) {
        errorMessage = 'Server unreachable or token unverified.'
        serverUnreachable = true
        return
      }
    }

    appState.setToken(trimmed)
    toastStore.show('Token saved successfully!', 'success')
    appState.tokenModalOpen = false
  }

  function handleClear() {
    appState.clearToken()
    inputToken = ''
    errorMessage = ''
    serverUnreachable = false
    toastStore.show('Session token cleared.', 'info')
  }
</script>

{#if appState.tokenModalOpen}
  <dialog class="modal modal-open" open>
    <div class="modal-box bg-base-200 border border-base-300 shadow-2xl relative max-w-md p-6 rounded-box">
      <button
        class="btn btn-sm btn-circle btn-ghost absolute right-3 top-3 text-base-content/60 hover:text-base-content"
        aria-label="Close modal"
        onclick={() => appState.tokenModalOpen = false}
      >
        <X class="w-4 h-4" />
      </button>

      <div class="flex items-center gap-3 mb-4">
        <div class="p-2.5 rounded-xl bg-primary/10 text-primary">
          <Key class="w-5 h-5" />
        </div>
        <div>
          <h3 class="font-bold text-base text-base-content">API Bearer Authentication</h3>
          <p class="text-xs text-base-content/60">MCDT generates a random 256-bit token on launch.</p>
        </div>
      </div>

      <!-- Help Callout -->
      <div class="alert alert-soft alert-info text-xs py-3 px-3.5 rounded-xl border border-info/20 mb-4">
        <Info class="w-4 h-4 text-info shrink-0 mt-0.5" />
        <div class="space-y-1">
          <div class="font-semibold text-base-content">How to obtain your token:</div>
          <ol class="list-decimal list-inside space-y-0.5 text-base-content/80 text-[11px]">
            <li>Open <code class="font-mono bg-base-300 px-1 py-0.5 rounded">config/mcdt/token</code> in game folder</li>
            <li>Or copy from in-game <strong>Mod Menu &gt; MCDT</strong> settings</li>
            <li>Or click the link printed in in-game chat</li>
          </ol>
        </div>
      </div>

      <!-- Input Field -->
      <div class="space-y-1.5 mb-5">
        <label for="token-input" class="label-text text-xs font-semibold text-base-content/70">
          Bearer Token
        </label>
        <input
          id="token-input"
          type="password"
          bind:value={inputToken}
          placeholder="Paste token here..."
          class="input input-bordered w-full bg-base-100 font-mono text-xs focus:outline-primary"
          class:input-error={!!errorMessage}
          onkeydown={(e) => e.key === 'Enter' && handleSave()}
        />
        {#if errorMessage}
          <div class="text-xs text-error font-medium mt-1">{errorMessage}</div>
        {/if}
      </div>

      <!-- Actions -->
      <div class="modal-action flex items-center justify-between pt-3 border-t border-base-300">
        <div>
          {#if appState.token}
            <button class="btn btn-sm btn-ghost text-error/80 hover:text-error" onclick={handleClear}>
              Clear Token
            </button>
          {/if}
        </div>
        <div class="flex items-center gap-2">
          {#if serverUnreachable}
            <button class="btn btn-sm btn-outline btn-warning" onclick={() => handleSave(true)}>
              Save Anyway
            </button>
          {/if}
          <button class="btn btn-sm btn-ghost" onclick={() => appState.tokenModalOpen = false}>
            Cancel
          </button>
          <button
            class="btn btn-sm btn-primary gap-1.5"
            disabled={validating}
            onclick={() => handleSave(false)}
          >
            {#if validating}
              <span class="loading loading-spinner loading-xs"></span>
              <span>Verifying...</span>
            {:else}
              <ShieldCheck class="w-4 h-4" />
              <span>Save & Authenticate</span>
            {/if}
          </button>
        </div>
      </div>
    </div>
    
    <!-- Backdrop Click to Dismiss -->
    <button
      type="button"
      class="modal-backdrop bg-black/50 backdrop-blur-xs cursor-default"
      onclick={() => {
        appState.tokenModalOpen = false
      }}
      aria-label="Close dialog backdrop"
    ></button>
  </dialog>
{/if}
