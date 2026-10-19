<script lang="ts">
  import { sendChat, pressKey, captureScreenshot, playerMove, playerLook, playerAction } from '../api'
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
    Check,
    Compass,
    ArrowUp,
    ArrowDown,
    ArrowLeft,
    ArrowRight,
    Square,
    Eye,
    Swords,
    Sparkle,
    RotateCcw
  } from 'lucide-svelte'

  let chatMessage = $state('')
  let isSendingChat = $state(false)
  let lastPressedKey = $state<string | null>(null)

  let isCapturing = $state(false)
  let screenshotUrl = $state<string | null>(null)
  let screenshotTime = $state<string | null>(null)

  // Move states
  let isMoving = $state(false)

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
      toastStore.show(`Key simulated: ${label}`, 'info', 1000)
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

  async function handleMove(direction: 'forward' | 'backward' | 'left' | 'right' | 'jump' | 'stop') {
    isMoving = true
    try {
      if (direction === 'stop') {
        await playerMove({ stop: true })
        toastStore.show('Player movement stopped', 'info', 800)
      } else if (direction === 'jump') {
        await playerMove({ jump: true, ticks: 5 })
      } else {
        await playerMove({ [direction]: true, ticks: 10 })
      }
    } catch {
      toastStore.show('Move action failed', 'error')
    } finally {
      isMoving = false
    }
  }

  async function handleSnapLook(yaw: number, pitch = 0, label = '') {
    try {
      await playerLook({ yaw, pitch })
      toastStore.show(`Aimed ${label || (yaw + '°')}`, 'info', 1000)
    } catch {
      toastStore.show('Look command failed', 'error')
    }
  }

  async function handleDeltaPitch(delta: number) {
    try {
      await playerLook({ deltaPitch: delta })
    } catch {
      // ignore
    }
  }

  async function handleAction(act: 'attack' | 'use' | 'drop' | 'swap_hands' | 'respawn', label: string) {
    try {
      const ok = await playerAction(act)
      if (ok) {
        toastStore.show(`Action: ${label}`, 'info', 1000)
      } else {
        toastStore.show(`Failed action: ${label}`, 'error')
      }
    } catch {
      toastStore.show(`Error performing ${label}`, 'error')
    }
  }
</script>

<div class="space-y-6">
  <!-- Top Full-Width Card: Chat & Command Dispatcher -->
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

  <!-- Middle Row: Interactive Directional Movement D-Pad & Camera Controls -->
  <div class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">

    <!-- Card 1: Movement D-Pad -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4 flex flex-col justify-between">
      <div class="flex items-center justify-between pb-2 border-b border-base-300">
        <div class="flex items-center gap-2 text-sm font-bold text-base-content">
          <Gamepad2 class="w-4 h-4 text-primary" />
          <span>Movement D-Pad</span>
        </div>
        <span class="badge badge-primary badge-xs font-mono">10 Ticks / Step</span>
      </div>

      <!-- D-Pad Buttons -->
      <div class="flex flex-col items-center justify-center gap-2 py-2">
        <button
          class="btn btn-sm btn-outline w-24 gap-1"
          onclick={() => handleMove('forward')}
        >
          <ArrowUp class="w-4 h-4" />
          <span>Forward</span>
        </button>
        <div class="flex items-center gap-2">
          <button
            class="btn btn-sm btn-outline w-24 gap-1"
            onclick={() => handleMove('left')}
          >
            <ArrowLeft class="w-4 h-4" />
            <span>Left</span>
          </button>
          <button
            class="btn btn-sm btn-error btn-square"
            onclick={() => handleMove('stop')}
            title="Stop Movement"
          >
            <Square class="w-4 h-4 fill-current" />
          </button>
          <button
            class="btn btn-sm btn-outline w-24 gap-1"
            onclick={() => handleMove('right')}
          >
            <ArrowRight class="w-4 h-4" />
            <span>Right</span>
          </button>
        </div>
        <button
          class="btn btn-sm btn-outline w-24 gap-1"
          onclick={() => handleMove('backward')}
        >
          <ArrowDown class="w-4 h-4" />
          <span>Back</span>
        </button>
      </div>

      <!-- Jump & Special Movement -->
      <div class="grid grid-cols-2 gap-2 pt-2 border-t border-base-300">
        <button class="btn btn-xs btn-outline btn-primary" onclick={() => handleMove('jump')}>
          <span>Jump</span>
          <kbd class="kbd kbd-xs">Space</kbd>
        </button>
        <button class="btn btn-xs btn-outline" onclick={() => handleMove('stop')}>
          <span>Halt All</span>
        </button>
      </div>
    </div>

    <!-- Card 2: Camera & Look Orientation -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4 flex flex-col justify-between">
      <div class="flex items-center justify-between pb-2 border-b border-base-300">
        <div class="flex items-center gap-2 text-sm font-bold text-base-content">
          <Compass class="w-4 h-4 text-secondary" />
          <span>Camera & Heading</span>
        </div>
        <span class="badge badge-secondary badge-xs font-mono">Snap Cardinal</span>
      </div>

      <!-- Cardinal Snap Buttons -->
      <div class="space-y-2 py-1">
        <span class="text-xs font-semibold text-base-content/70">Snap Facing:</span>
        <div class="grid grid-cols-2 gap-2">
          <button class="btn btn-xs btn-outline" onclick={() => handleSnapLook(180, 0, 'North')}>North (-Z)</button>
          <button class="btn btn-xs btn-outline" onclick={() => handleSnapLook(0, 0, 'South')}>South (+Z)</button>
          <button class="btn btn-xs btn-outline" onclick={() => handleSnapLook(270, 0, 'East')}>East (+X)</button>
          <button class="btn btn-xs btn-outline" onclick={() => handleSnapLook(90, 0, 'West')}>West (-X)</button>
        </div>
      </div>

      <!-- Pitch Adjustment -->
      <div class="space-y-2 pt-2 border-t border-base-300">
        <span class="text-xs font-semibold text-base-content/70">Vertical Tilt:</span>
        <div class="grid grid-cols-3 gap-2">
          <button class="btn btn-xs btn-outline" onclick={() => handleDeltaPitch(-15)}>Look Up (-15°)</button>
          <button class="btn btn-xs btn-outline" onclick={() => handleSnapLook(0, 0, 'Level')}>Level (0°)</button>
          <button class="btn btn-xs btn-outline" onclick={() => handleDeltaPitch(15)}>Look Down (+15°)</button>
        </div>
      </div>
    </div>

    <!-- Card 3: Interactive Action Triggers -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4 flex flex-col justify-between">
      <div class="flex items-center justify-between pb-2 border-b border-base-300">
        <div class="flex items-center gap-2 text-sm font-bold text-base-content">
          <Swords class="w-4 h-4 text-accent" />
          <span>Player Actions</span>
        </div>
        <span class="badge badge-accent badge-xs font-mono">M1 / M2 / Hands</span>
      </div>

      <div class="space-y-2 py-1">
        <button
          class="btn btn-sm btn-outline btn-error w-full justify-between"
          onclick={() => handleAction('attack', 'Attack / Mine (M1)')}
        >
          <span>Attack / Strike</span>
          <kbd class="kbd kbd-xs">Left Click</kbd>
        </button>
        <button
          class="btn btn-sm btn-outline btn-success w-full justify-between"
          onclick={() => handleAction('use', 'Use / Place Item (M2)')}
        >
          <span>Use / Place Item</span>
          <kbd class="kbd kbd-xs">Right Click</kbd>
        </button>
        <button
          class="btn btn-sm btn-outline btn-warning w-full justify-between"
          onclick={() => handleAction('drop', 'Drop Item (Q)')}
        >
          <span>Drop Selected Item</span>
          <kbd class="kbd kbd-xs">Q</kbd>
        </button>
        <button
          class="btn btn-sm btn-outline btn-info w-full justify-between"
          onclick={() => handleAction('swap_hands', 'Swap Main/Offhand (F)')}
        >
          <span>Swap Hands (Main & Offhand)</span>
          <kbd class="kbd kbd-xs">F</kbd>
        </button>
      </div>

      <div class="pt-2 border-t border-base-300">
        <button
          class="btn btn-xs btn-ghost text-error w-full justify-center gap-1"
          onclick={() => handleAction('respawn', 'Respawn')}
        >
          <RotateCcw class="w-3 h-3" />
          <span>Trigger Respawn (if dead)</span>
        </button>
      </div>
    </div>

  </div>

  <!-- Lower 2-Column Grid: Macro Controller & Screenshot Capture -->
  <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">

    <!-- Card 1: Key Simulation Pad -->
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

        <div class="space-y-4 pt-1">
          <!-- Movement Group -->
          <div class="space-y-2">
            <div class="text-xs font-semibold text-base-content/70 uppercase tracking-wider">Movement Keys</div>
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

          <!-- Interactions Group -->
          <div class="space-y-2">
            <div class="text-xs font-semibold text-base-content/70 uppercase tracking-wider">Mouse Hotkeys</div>
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

          <!-- Inventory & UI -->
          <div class="space-y-2">
            <div class="text-xs font-semibold text-base-content/70 uppercase tracking-wider">UI & Inventory</div>
            <div class="grid grid-cols-2 gap-3">
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.keyboard.e' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.keyboard.e', 'Inventory')}
              >
                <span>Inventory</span>
                <kbd class="kbd kbd-xs">E</kbd>
              </button>
              <button
                class="btn btn-outline btn-sm justify-between bg-base-100 hover:btn-primary {lastPressedKey === 'key.keyboard.escape' ? 'btn-active' : ''}"
                onclick={() => handlePressKey('key.keyboard.escape', 'Escape')}
              >
                <span>Escape / Menu</span>
                <kbd class="kbd kbd-xs">Esc</kbd>
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Card 2: Client Viewport Frame Capture (Manual On-Demand) -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box overflow-hidden">
      <div class="card-body p-5 sm:p-6 space-y-4">
        <div class="flex items-center justify-between pb-2 border-b border-base-300">
          <div class="flex items-center gap-3">
            <div class="p-2.5 bg-accent/10 rounded-xl text-accent">
              <Camera class="w-5 h-5" />
            </div>
            <div>
              <h2 class="card-title text-base font-bold text-base-content">Client Frame Capture</h2>
              <p class="text-xs text-base-content/60">On-demand frame capture from Minecraft viewport.</p>
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
                  download="minecraft-devtools-screenshot.png"
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
              Click "Capture Frame" to grab an on-demand screenshot from the active Minecraft render window.
            </p>
          </div>
        {/if}
      </div>
    </div>

  </div>
</div>
