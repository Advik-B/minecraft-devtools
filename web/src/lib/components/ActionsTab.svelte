<script lang="ts">
  import { sendChat, pressKey, captureScreenshot } from '../api'

  let chatMessage = $state('')
  let isSendingChat = $state(false)
  let chatFeedback = $state<{ type: 'success' | 'error'; msg: string } | null>(null)

  let isCapturing = $state(false)
  let screenshotUrl = $state<string | null>(null)
  let screenshotTime = $state<string | null>(null)

  async function handleSendChat() {
    const msg = chatMessage.trim()
    if (!msg) return

    isSendingChat = true
    chatFeedback = null

    try {
      const ok = await sendChat(msg)
      if (ok) {
        chatFeedback = { type: 'success', msg: msg.startsWith('/') ? 'Command dispatched' : 'Chat sent' }
        chatMessage = ''
      } else {
        chatFeedback = { type: 'error', msg: 'Failed to send - verify permission is ACT or world is loaded.' }
      }
    } catch (err: unknown) {
      chatFeedback = { type: 'error', msg: err instanceof Error ? err.message : 'Error sending message' }
    } finally {
      isSendingChat = false
      setTimeout(() => chatFeedback = null, 3000)
    }
  }

  async function handlePressKey(key: string, label: string) {
    try {
      await pressKey(key, 'press')
    } catch {
      // ignore
    }
  }

  async function handleScreenshot() {
    isCapturing = true
    try {
      const url = await captureScreenshot()
      if (url) {
        if (screenshotUrl) URL.revokeObjectURL(screenshotUrl)
        screenshotUrl = url
        screenshotTime = new Date().toLocaleTimeString()
      }
    } finally {
      isCapturing = false
    }
  }
</script>

<article>
  <header><strong>Chat & Command Dispatcher</strong></header>
  <form onsubmit={(e) => { e.preventDefault(); handleSendChat(); }}>
    <div role="group">
      <input
        type="text"
        bind:value={chatMessage}
        placeholder="Type a message or command (e.g. /time set day, /gamemode creative)..."
      />
      <input
        type="submit"
        value={isSendingChat ? 'Sending...' : 'Send'}
        disabled={isSendingChat || !chatMessage.trim()}
      />
    </div>
  </form>

  {#if chatFeedback}
    <p>
      <small>
        {#if chatFeedback.type === 'success'}
          <ins>{chatFeedback.msg}</ins>
        {:else}
          <mark>{chatFeedback.msg}</mark>
        {/if}
      </small>
    </p>
  {/if}

  <p>
    <small>
      Quick commands: 
      <a href="#cmd" onclick={(e) => { e.preventDefault(); chatMessage = '/time set day'; }}>/time set day</a> &bull;
      <a href="#cmd" onclick={(e) => { e.preventDefault(); chatMessage = '/weather clear'; }}>/weather clear</a> &bull;
      <a href="#cmd" onclick={(e) => { e.preventDefault(); chatMessage = '/gamemode creative'; }}>/gamemode creative</a> &bull;
      <a href="#cmd" onclick={(e) => { e.preventDefault(); chatMessage = '/gamemode survival'; }}>/gamemode survival</a>
    </small>
  </p>
</article>

<div class="grid">
  <article>
    <header><strong>Input Actions & Key Simulation</strong></header>
    <p><small>Simulates client key mappings via <code>/v1/input/keys</code> (Requires ACT permission).</small></p>

    <div class="grid">
      <button class="outline" onclick={() => handlePressKey('key.keyboard.space', 'Jump')}>Jump</button>
      <button class="outline" onclick={() => handlePressKey('key.keyboard.left.shift', 'Sneak')}>Sneak</button>
      <button class="outline" onclick={() => handlePressKey('key.keyboard.e', 'Inventory')}>Inventory</button>
    </div>
    <div class="grid">
      <button class="outline" onclick={() => handlePressKey('key.mouse.left', 'Attack')}>Attack</button>
      <button class="outline" onclick={() => handlePressKey('key.mouse.right', 'Use')}>Use</button>
      <button class="outline" onclick={() => handlePressKey('key.keyboard.q', 'Drop')}>Drop</button>
    </div>
  </article>

  <article>
    <header><strong>Client Frame Capture</strong></header>
    <button aria-busy={isCapturing} onclick={handleScreenshot}>
      {isCapturing ? 'Capturing...' : 'Take Screenshot'}
    </button>

    {#if screenshotUrl}
      <figure>
        <img src={screenshotUrl} alt="Minecraft Frame" />
        <figcaption>
          <small>
            Captured at {screenshotTime} &bull; 
            <a href={screenshotUrl} download="mcdt-screenshot.png">Download</a> &bull; 
            <a href={screenshotUrl} target="_blank" rel="noreferrer">Full View</a>
          </small>
        </figcaption>
      </figure>
    {:else}
      <p><small>Click "Take Screenshot" to grab a realtime frame from the Minecraft render window.</small></p>
    {/if}
  </article>
</div>
