<script lang="ts">
  import { sendChat, pressKey, captureScreenshot } from '../api'
  import { toastStore } from '../toast.svelte'
  import {
    Terminal,
    Send,
    Gamepad2,
    Camera,
    Download,
    ExternalLink,
    Sparkles,
    MousePointer,
    Check
  } from 'lucide-svelte'

  let chatMessage = $state('')
  let isSendingChat = $state(false)
  let lastPressedKey = $state<string | null>(null)

  let isCapturing = $state(false)
  let screenshotUrl = $state<string | null>(null)
  let screenshotTime = $state<string | null>(null)

  const quickCommands = [
    { label: '/time set day', desc: 'Daytime' },
    { label: '/weather clear', desc: 'Clear Weather' },
    { label: '/gamemode creative', desc: 'Creative Mode' },
    { label: '/gamemode survival', desc: 'Survival Mode' },
    { label: '/tp ~ ~10 ~', desc: 'Ascend 10 blocks' }
  ]

  async function handleSendChat() {
    const msg = chatMessage.trim()
    if (!msg) return

    isSendingChat = true

    try {
      const ok = await sendChat(msg)
      if (ok) {
        toastStore.show(msg.startsWith('/') ? 'Command dispatched successfully!' : 'Chat message sent!', 'success')
        chatMessage = ''
      } else {
        toastStore.show('Failed to send - verify ACT permission or loaded world.', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Error sending message', 'error')
    } finally {
      isSendingChat = false
    }
  }

  async function handlePressKey(key: string, label: string) {
    lastPressedKey = key
    try {
      await pressKey(key, 'press')
      toastStore.show(`Key simulated: ${label}`, 'info', 1500)
    } catch {
      toastStore.show(`Failed to simulate key: ${label}`, 'error')
    } finally {
      setTimeout(() => {
        if (lastPressedKey === key) lastPressedKey = null
      }, 300)
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
        toastStore.show('Frame captured successfully!', 'success')
      } else {
        toastStore.show('Failed to capture frame from client.', 'error')
      }
    } catch {
      toastStore.show('Error communicating with screenshot endpoint.', 'error')
    } finally {
      isCapturing = false
    }
  }
</script>

<div class="space-y-6">
  <!-- Full-Width Card: Chat & Command Dispatcher -->
  <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
    <div class="card-body p-5 sm:p-6 space-y-4">
      <div class="flex items-center gap-3 pb-2 border-b border-base-300">
        <div class="p-2.5 bg-primary/10 rounded-xl text-primary">
          <Terminal class="w-5 h-5" />
        </div>
        <div>
          <h2 class="card-title text-base font-bold text-base-content">Chat & Command Dispatcher</h2>
          <p class="text-xs text-base-content/60">Dispatch chat messages or slash commands directly to the Minecraft client.</p>
        </div>
      </div>

      <!-- Command Input Box -->
      <form onsubmit={(e) => { e.preventDefault(); handleSendChat(); }} class="space-y-3">
        <div class="join w-full">
          <input
            type="text"
            bind:value={chatMessage}
            placeholder="Type a message or command (e.g. /time set day, /gamemode creative)..."
            class="input input-bordered join-item w-full bg-base-100 font-mono text-sm focus:outline-primary"
          />
          <button
            type="submit"
            class="btn btn-primary join-item px-5 gap-2"
            disabled={isSendingChat || !chatMessage.trim()}
          >
            {#if isSendingChat}
              <span class="loading loading-spinner loading-xs"></span>
              <span>Sending...</span>
            {:else}
              <Send class="w-4 h-4" />
              <span>Send</span>
            {/if}
          </button>
        </div>
      </form>

      <!-- Clickable Preset Chips -->
      <div class="pt-1 space-y-2">
        <div class="flex items-center gap-1.5 text-xs text-base-content/70 font-medium">
          <Sparkles class="w-3.5 h-3.5 text-primary" />
          <span>Quick Command Presets:</span>
        </div>
        <div class="flex flex-wrap gap-2">
          {#each quickCommands as cmd}
            <button
              class="badge badge-outline badge-md hover:badge-primary hover:text-primary-content transition-all cursor-pointer font-mono text-xs py-3 px-3 gap-1.5 bg-base-100"
              onclick={() => chatMessage = cmd.label}
              title={cmd.desc}
            >
              <span>{cmd.label}</span>
            </button>
          {/each}
        </div>
      </div>
    </div>
  </div>

  <!-- Lower 2-Column Grid: Macro Controller & Screenshot Capture -->
  <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">

    <!-- Card 1: Input Actions & Key Simulation (Macro Controller) -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
      <div class="card-body p-5 sm:p-6 space-y-4">
        <div class="flex items-center gap-3 pb-2 border-b border-base-300">
          <div class="p-2.5 bg-secondary/10 rounded-xl text-secondary">
            <Gamepad2 class="w-5 h-5" />
          </div>
          <div>
            <h2 class="card-title text-base font-bold text-base-content">Key Simulation Pad</h2>
            <p class="text-xs text-base-content/60">Simulates physical client input via <code>/v1/input/keys</code> (Requires ACT permission).</p>
          </div>
        </div>

        <!-- Macro Groups -->
        <div class="space-y-4 pt-1">
          <!-- Movement Group -->
          <div class="space-y-2">
            <div class="text-xs font-semibold text-base-content/70 uppercase tracking-wider">Movement</div>
            <div class="grid grid-cols-2 gap-3">
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.keyboard.space' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.keyboard.space', 'Jump')}
              >
                <span>Jump</span>
                <kbd class="kbd kbd-xs">Space</kbd>
              </button>
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.keyboard.left.shift' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.keyboard.left.shift', 'Sneak')}
              >
                <span>Sneak</span>
                <kbd class="kbd kbd-xs">Shift</kbd>
              </button>
            </div>
          </div>

          <!-- Actions Group -->
          <div class="space-y-2">
            <div class="text-xs font-semibold text-base-content/70 uppercase tracking-wider">Interactions</div>
            <div class="grid grid-cols-2 gap-3">
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.mouse.left' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.mouse.left', 'Attack')}
              >
                <span>Attack / Mine</span>
                <kbd class="kbd kbd-xs">M1</kbd>
              </button>
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.mouse.right' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.mouse.right', 'Use Item')}
              >
                <span>Use / Place</span>
                <kbd class="kbd kbd-xs">M2</kbd>
              </button>
            </div>
          </div>

          <!-- Inventory & Items -->
          <div class="space-y-2">
            <div class="text-xs font-semibold text-base-content/70 uppercase tracking-wider">Inventory & Items</div>
            <div class="grid grid-cols-2 gap-3">
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.keyboard.e' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.keyboard.e', 'Inventory')}
              >
                <span>Inventory</span>
                <kbd class="kbd kbd-xs">E</kbd>
              </button>
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.keyboard.q' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.keyboard.q', 'Drop')}
              >
                <span>Drop Item</span>
                <kbd class="kbd kbd-xs">Q</kbd>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Card 2: Client Frame Capture -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
      <div class="card-body p-5 sm:p-6 space-y-4">
        <div class="flex items-center justify-between pb-2 border-b border-base-300">
          <div class="flex items-center gap-3">
            <div class="p-2.5 bg-accent/10 rounded-xl text-accent">
              <Camera class="w-5 h-5" />
            </div>
            <div>
              <h2 class="card-title text-base font-bold text-base-content">Client Frame Capture</h2>
              <p class="text-xs text-base-content/60">Capture realtime rendered frames from the Minecraft viewport.</p>
            </div>
          </div>
          <button
            class="btn btn-primary btn-sm gap-2"
            disabled={isCapturing}
            onclick={handleScreenshot}
          >
            {#if isCapturing}
              <span class="loading loading-spinner loading-xs"></span>
              <span>Capturing...</span>
            {:else}
              <Camera class="w-4 h-4" />
              <span>Capture Frame</span>
            {/if}
          </button>
        </div>

        {#if screenshotUrl}
          <div class="space-y-3 pt-1">
            <div class="rounded-xl overflow-hidden border border-base-300 bg-base-100 shadow-inner">
              <img src={screenshotUrl} alt="Minecraft Frame Capture" class="w-full object-cover max-h-72" />
            </div>
            <div class="flex items-center justify-between text-xs text-base-content/70 px-1">
              <span>Captured at <strong class="text-base-content">{screenshotTime}</strong></span>
              <div class="flex items-center gap-2">
                <a
                  href={screenshotUrl}
                  download="mcdt-screenshot.png"
                  class="btn btn-xs btn-outline gap-1"
                >
                  <Download class="w-3 h-3" />
                  <span>Download</span>
                </a>
                <a
                  href={screenshotUrl}
                  target="_blank"
                  rel="noreferrer"
                  class="btn btn-xs btn-ghost gap-1"
                >
                  <ExternalLink class="w-3 h-3" />
                  <span>Full View</span>
                </a>
              </div>
            </div>
          </div>
        {:else}
          <div class="text-center py-10 px-4 rounded-xl border border-dashed border-base-300 bg-base-100/50 space-y-2">
            <div class="w-12 h-12 mx-auto rounded-full bg-base-200 flex items-center justify-center text-base-content/40">
              <Camera class="w-6 h-6" />
            </div>
            <p class="text-xs text-base-content/60 max-w-xs mx-auto">
              Click "Capture Frame" to grab a realtime screenshot from the active Minecraft render window.
            </p>
          </div>
        {/if}
      </div>
    </div>

  </div>
</div>
