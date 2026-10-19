<script lang="ts">
  import { appState } from '../stores.svelte'
  import { testToken } from '../api'

  let inputToken = $state(appState.token)
  let validating = $state(false)
  let errorMessage = $state('')
  let successNotice = $state(false)

  $effect(() => {
    if (appState.tokenModalOpen) {
      inputToken = appState.token
      errorMessage = ''
      successNotice = false
    }
  })

  async function handleSave() {
    const trimmed = inputToken.trim()
    if (!trimmed) {
      errorMessage = 'Please enter a token.'
      return
    }

    validating = true
    errorMessage = ''
    successNotice = false

    const valid = await testToken(trimmed)
    validating = false

    if (!valid) {
      errorMessage = 'Invalid token or server unreachable. Please verify the token.'
      return
    }

    appState.setToken(trimmed)
    successNotice = true
    setTimeout(() => {
      appState.tokenModalOpen = false
    }, 500)
  }

  function handleClear() {
    appState.clearToken()
    inputToken = ''
    errorMessage = ''
  }
</script>

{#if appState.tokenModalOpen}
  <dialog open>
    <article>
      <header>
        {#if appState.token}
          <button aria-label="Close" onclick={() => appState.tokenModalOpen = false}></button>
        {/if}
        <p><strong>API Bearer Authentication</strong></p>
      </header>

      <p>
        MCDT generates a random 256-bit authentication token on each launch to protect local API calls.
      </p>

      <blockquote>
        <small>
          How to obtain your token:<br />
          1. Open <code>config/mcdt/token</code> in your game folder<br />
          2. Or copy it from the in-game <strong>Mod Menu</strong> settings<br />
          3. Or click the link in in-game chat (<code>#token=...</code>)
        </small>
      </blockquote>

      <label for="token-input">Bearer Token</label>
      <input
        id="token-input"
        type="password"
        bind:value={inputToken}
        placeholder="Paste token here..."
        aria-invalid={errorMessage ? 'true' : undefined}
        onkeydown={(e) => e.key === 'Enter' && handleSave()}
      />

      {#if errorMessage}
        <p><small><mark>{errorMessage}</mark></small></p>
      {/if}

      {#if successNotice}
        <p><small><ins>Token verified & saved successfully!</ins></small></p>
      {/if}

      <footer>
        {#if appState.token}
          <button class="secondary outline" onclick={handleClear}>Clear Token</button>
          <button class="secondary" onclick={() => appState.tokenModalOpen = false}>Cancel</button>
        {/if}
        <button aria-busy={validating} onclick={handleSave}>
          {validating ? 'Verifying...' : 'Save & Authenticate'}
        </button>
      </footer>
    </article>
  </dialog>
{/if}
