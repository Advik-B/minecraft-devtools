<script lang="ts">
  import { onMount } from 'svelte'
  import { appState } from '../stores.svelte'
  import {
    getObjectRoots,
    inspectObject,
    inspectClass,
    sendPacket
  } from '../api'
  import { toastStore } from '../toast.svelte'
  import type { RootsResponse, ObjectInspectResponse, RootDescriptor } from '../types'
  import {
    Cpu,
    Terminal,
    Send,
    Search,
    ChevronRight,
    CornerDownRight,
    FolderTree,
    Play,
    Copy,
    Check,
    Layers,
    Key,
    ExternalLink
  } from 'lucide-svelte'

  let rootsData = $state<RootsResponse | null>(null)
  let activeHandle = $state<string>('client')
  let activeObject = $state<ObjectInspectResponse | null>(null)
  let breadcrumbs = $state<Array<{ handle: string; label: string }>>([])

  // REPL evaluator
  let replPath = $state('player.getHealth()')
  let replResult = $state<string | null>(null)
  let isEvaluating = $state(false)

  // Packet Dispatcher
  let packetPayload = $state(JSON.stringify({
    type: 'minecraft:chat',
    action: 'message',
    data: { text: 'Hello from MCDT Packet Dispatcher' }
  }, null, 2))
  let isSendingPacket = $state(false)

  let activeSubTab = $state<'inspector' | 'repl' | 'packets'>('inspector')
  let fieldSearch = $state('')
  let methodSearch = $state('')

  async function loadRoots() {
    if (!appState.token) return
    try {
      const roots = await getObjectRoots()
      if (roots) {
        rootsData = roots
        if (!activeHandle && roots.roots['client']) {
          selectRoot('client', roots.roots['client'].$handle)
        }
      }
    } catch {
      // ignore
    }
  }

  async function selectRoot(name: string, handle: string) {
    activeHandle = handle
    breadcrumbs = [{ handle, label: name }]
    await loadObject(handle)
  }

  async function loadObject(handle: string, path?: string) {
    try {
      const obj = await inspectObject(handle, path)
      if (obj) {
        activeObject = obj
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Failed to inspect object', 'error')
    }
  }

  async function navigateToHandle(handle: string, label: string) {
    breadcrumbs = [...breadcrumbs, { handle, label }]
    activeHandle = handle
    await loadObject(handle)
  }

  async function jumpToBreadcrumb(index: number) {
    const item = breadcrumbs[index]
    breadcrumbs = breadcrumbs.slice(0, index + 1)
    activeHandle = item.handle
    await loadObject(item.handle)
  }

  async function handleEvaluateRepl() {
    if (!replPath.trim()) return
    isEvaluating = true
    replResult = null
    try {
      // Evaluate against client root
      const clientHandle = rootsData?.roots?.['client']?.$handle || 'client'
      const res = await inspectObject(clientHandle, replPath.trim())
      if (res) {
        replResult = JSON.stringify(res, null, 2)
        toastStore.show('Expression evaluated', 'success')
      } else {
        replResult = '// No result returned or path not found'
      }
    } catch (err: unknown) {
      replResult = `// Error: ${err instanceof Error ? err.message : String(err)}`
      toastStore.show('Evaluation error', 'error')
    } finally {
      isEvaluating = false
    }
  }

  async function handleSendPacket() {
    isSendingPacket = true
    try {
      const parsed = JSON.parse(packetPayload)
      const ok = await sendPacket(parsed)
      if (ok) {
        toastStore.show('Packet dispatched to client pipeline', 'success')
      } else {
        toastStore.show('Packet rejected (check REFLECT_WRITE role or packet syntax)', 'error')
      }
    } catch (err: unknown) {
      toastStore.show(err instanceof Error ? err.message : 'Invalid JSON packet payload', 'error')
    } finally {
      isSendingPacket = false
    }
  }

  onMount(() => {
    loadRoots()
  })

  const filteredFields = $derived.by(() => {
    if (!activeObject?.fields) return []
    const entries = Object.entries(activeObject.fields)
    if (!fieldSearch.trim()) return entries
    const q = fieldSearch.toLowerCase()
    return entries.filter(([k, v]) => k.toLowerCase().includes(q) || String(v).toLowerCase().includes(q))
  })

  const filteredMethods = $derived.by(() => {
    if (!activeObject?.methods) return []
    if (!methodSearch.trim()) return activeObject.methods
    const q = methodSearch.toLowerCase()
    return activeObject.methods.filter((m) => m.name.toLowerCase().includes(q) || m.returnType.toLowerCase().includes(q))
  })
</script>

<div class="space-y-6">
  {#if !appState.token}
    <div role="alert" class="alert alert-soft alert-warning border border-warning/30 shadow-md p-6 rounded-box flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
      <div class="flex items-center gap-3">
        <div class="p-3 bg-warning/20 rounded-xl text-warning">
          <Key class="w-6 h-6" />
        </div>
        <div>
          <h3 class="font-bold text-base text-base-content">Authentication Required</h3>
          <p class="text-sm text-base-content/70">Connect with your Bearer token to inspect live Java reflection trees and packet streams.</p>
        </div>
      </div>
      <button class="btn btn-warning btn-sm shrink-0" onclick={() => appState.tokenModalOpen = true}>Set Token</button>
    </div>
  {:else}
    <!-- Header with Sub-tabs switcher -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-4">
      <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
        <div class="flex items-center gap-3">
          <div class="w-10 h-10 rounded-xl bg-primary/10 text-primary flex items-center justify-center">
            <Cpu class="w-6 h-6" />
          </div>
          <div>
            <h2 class="text-base font-bold text-base-content">Minecraft Runtime Reflection & Packets</h2>
            <p class="text-xs text-base-content/60">Live handle tables, class inspection, dot-path evaluator, and raw packet injector.</p>
          </div>
        </div>

        <div role="tablist" class="tabs tabs-box tabs-sm bg-base-300/60 p-1">
          <button
            role="tab"
            class="tab {activeSubTab === 'inspector' ? 'tab-active font-semibold' : ''}"
            onclick={() => activeSubTab = 'inspector'}
          >
            Object Inspector
          </button>
          <button
            role="tab"
            class="tab {activeSubTab === 'repl' ? 'tab-active font-semibold' : ''}"
            onclick={() => activeSubTab = 'repl'}
          >
            Path REPL
          </button>
          <button
            role="tab"
            class="tab {activeSubTab === 'packets' ? 'tab-active font-semibold' : ''}"
            onclick={() => activeSubTab = 'packets'}
          >
            Packet Sender
          </button>
        </div>
      </div>
    </div>

    {#if activeSubTab === 'inspector'}
      <!-- Root Handles Carousel / Selector -->
      <div class="space-y-4">
        <div class="flex items-center gap-2 overflow-x-auto pb-1">
          <span class="text-xs font-bold text-base-content/60 uppercase tracking-wider shrink-0 mr-2">Roots:</span>
          {#if rootsData?.roots}
            {#each Object.entries(rootsData.roots) as [name, desc]}
              <button
                class="btn btn-xs {activeHandle === desc.$handle ? 'btn-primary' : 'btn-outline btn-neutral'} font-mono shrink-0"
                onclick={() => selectRoot(name, desc.$handle)}
              >
                <span>{name}</span>
                <span class="opacity-60 text-[10px]">({desc.$handle})</span>
              </button>
            {/each}
          {:else}
            <button class="btn btn-xs btn-primary font-mono" onclick={() => selectRoot('client', 'client')}>client</button>
            <button class="btn btn-xs btn-outline font-mono" onclick={() => selectRoot('player', 'player')}>player</button>
            <button class="btn btn-xs btn-outline font-mono" onclick={() => selectRoot('level', 'level')}>level</button>
            <button class="btn btn-xs btn-outline font-mono" onclick={() => selectRoot('options', 'options')}>options</button>
          {/if}
        </div>

        <!-- Breadcrumbs Navigation -->
        {#if breadcrumbs.length > 1}
          <div class="breadcrumbs text-xs px-2 py-1 bg-base-200 rounded-lg border border-base-300">
            <ul>
              {#each breadcrumbs as item, idx}
                <li>
                  {#if idx === breadcrumbs.length - 1}
                    <span class="font-bold text-primary font-mono">{item.label}</span>
                  {:else}
                    <button class="link link-hover font-mono text-base-content/70" onclick={() => jumpToBreadcrumb(idx)}>
                      {item.label}
                    </button>
                  {/if}
                </li>
              {/each}
            </ul>
          </div>
        {/if}

        <!-- Active Object Card -->
        <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4">
          <div class="flex flex-col sm:flex-row items-start sm:items-center justify-between gap-3 pb-3 border-b border-base-300">
            <div>
              <span class="badge badge-primary badge-xs font-mono">{activeObject?.$handle || activeHandle}</span>
              <h3 class="font-bold text-sm text-base-content font-mono mt-1">
                {activeObject?.$class || 'Loading class metadata...'}
              </h3>
            </div>
            <button class="btn btn-xs btn-ghost gap-1 font-mono" onclick={() => loadObject(activeHandle)}>
              <span>Reload Handle</span>
            </button>
          </div>

          <!-- Fields and Methods Tabs -->
          <div class="grid grid-cols-1 lg:grid-cols-2 gap-6">
            <!-- Fields Table -->
            <div class="space-y-3">
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-base-content">Fields ({filteredFields.length})</span>
                <input
                  type="text"
                  bind:value={fieldSearch}
                  placeholder="Filter fields..."
                  class="input input-xs input-bordered w-36 bg-base-100 font-mono"
                />
              </div>

              <div class="overflow-x-auto max-h-96 rounded-lg border border-base-300 bg-base-100">
                <table class="table table-xs table-zebra w-full font-mono">
                  <thead>
                    <tr class="text-base-content/60">
                      <th>Name</th>
                      <th>Value / Handle</th>
                    </tr>
                  </thead>
                  <tbody>
                    {#if filteredFields.length === 0}
                      <tr>
                        <td colspan="2" class="text-center py-6 text-base-content/40 italic">No fields found</td>
                      </tr>
                    {:else}
                      {#each filteredFields as [fieldName, val]}
                        <tr>
                          <td class="font-bold text-base-content">{fieldName}</td>
                          <td class="text-xs">
                            {#if val && typeof val === 'object' && ('$handle' in (val as Record<string, unknown>))}
                              {@const handleObj = val as { $handle: string; $class?: string }}
                              <button
                                class="btn btn-xs btn-outline btn-primary font-mono gap-1 text-[11px]"
                                onclick={() => navigateToHandle(handleObj.$handle, fieldName)}
                              >
                                <span>{handleObj.$handle}</span>
                                <ChevronRight class="w-3 h-3" />
                              </button>
                            {:else}
                              <span class="text-base-content/80 truncate block max-w-xs">{String(val)}</span>
                            {/if}
                          </td>
                        </tr>
                      {/each}
                    {/if}
                  </tbody>
                </table>
              </div>
            </div>

            <!-- Methods Table -->
            <div class="space-y-3">
              <div class="flex items-center justify-between">
                <span class="text-xs font-bold text-base-content">Methods ({filteredMethods.length})</span>
                <input
                  type="text"
                  bind:value={methodSearch}
                  placeholder="Filter methods..."
                  class="input input-xs input-bordered w-36 bg-base-100 font-mono"
                />
              </div>

              <div class="overflow-x-auto max-h-96 rounded-lg border border-base-300 bg-base-100">
                <table class="table table-xs table-zebra w-full font-mono">
                  <thead>
                    <tr class="text-base-content/60">
                      <th>Method Signature</th>
                      <th>Return Type</th>
                    </tr>
                  </thead>
                  <tbody>
                    {#if filteredMethods.length === 0}
                      <tr>
                        <td colspan="2" class="text-center py-6 text-base-content/40 italic">No methods found</td>
                      </tr>
                    {:else}
                      {#each filteredMethods as m}
                        <tr>
                          <td class="font-bold text-base-content">
                            <span>{m.name}({m.parameterTypes ? m.parameterTypes.join(', ') : ''})</span>
                          </td>
                          <td class="text-primary text-xs">{m.returnType || 'void'}</td>
                        </tr>
                      {/each}
                    {/if}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      </div>
    {:else if activeSubTab === 'repl'}
      <!-- Dot-Path Evaluator REPL -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4">
        <div>
          <h3 class="font-bold text-sm text-base-content flex items-center gap-2">
            <Terminal class="w-4 h-4 text-primary" />
            <span>Dot-Path Expression Evaluator</span>
          </h3>
          <p class="text-xs text-base-content/60">
            Evaluate object navigation paths directly on the live client heap (e.g. <code>player.getHealth()</code>, <code>options.renderDistance</code>).
          </p>
        </div>

        <form onsubmit={(e) => { e.preventDefault(); handleEvaluateRepl(); }} class="flex items-center gap-2">
          <div class="join w-full">
            <span class="join-item btn btn-sm btn-neutral font-mono text-xs">client.</span>
            <input
              type="text"
              bind:value={replPath}
              placeholder="player.inventory.getSelected().getName()"
              class="join-item input input-sm input-bordered flex-1 bg-base-100 font-mono text-xs"
            />
            <button type="submit" class="join-item btn btn-sm btn-primary gap-1" disabled={isEvaluating}>
              <Play class="w-3.5 h-3.5" />
              <span>Evaluate</span>
            </button>
          </div>
        </form>

        {#if replResult !== null}
          <div class="space-y-1">
            <div class="flex items-center justify-between text-xs text-base-content/60">
              <span>Result Output</span>
              <button
                class="btn btn-ghost btn-xs gap-1"
                onclick={() => {
                  if (replResult) {
                    navigator.clipboard?.writeText(replResult).catch(() => {})
                    toastStore.show('Copied to clipboard', 'info', 1000)
                  }
                }}
              >
                <Copy class="w-3 h-3" />
                <span>Copy JSON</span>
              </button>
            </div>
            <pre class="bg-base-300/80 p-4 rounded-xl font-mono text-xs overflow-x-auto max-h-80 border border-base-300">{replResult}</pre>
          </div>
        {/if}
      </div>
    {:else if activeSubTab === 'packets'}
      <!-- Packet Sender -->
      <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box p-5 space-y-4">
        <div>
          <h3 class="font-bold text-sm text-base-content flex items-center gap-2">
            <Send class="w-4 h-4 text-secondary" />
            <span>Raw Packet Injector</span>
          </h3>
          <p class="text-xs text-base-content/60">
            Dispatch serialized client packets into the network pipeline. Requires <code>REFLECT_WRITE</code> role.
          </p>
        </div>

        <!-- Preset buttons -->
        <div class="flex items-center gap-2">
          <span class="text-xs text-base-content/60">Presets:</span>
          <button
            class="btn btn-xs btn-outline btn-neutral font-mono"
            onclick={() => {
              packetPayload = JSON.stringify({
                type: 'minecraft:chat',
                action: 'message',
                data: { text: 'Test chat packet' }
              }, null, 2)
            }}
          >
            Chat Packet
          </button>
          <button
            class="btn btn-xs btn-outline btn-neutral font-mono"
            onclick={() => {
              packetPayload = JSON.stringify({
                type: 'minecraft:player_action',
                action: 'swing_arm',
                data: { hand: 'MAIN_HAND' }
              }, null, 2)
            }}
          >
            Swing Arm
          </button>
        </div>

        <textarea
          bind:value={packetPayload}
          rows={8}
          class="textarea textarea-bordered w-full font-mono text-xs bg-base-100"
        ></textarea>

        <div class="flex justify-end">
          <button
            class="btn btn-sm btn-secondary gap-1.5"
            onclick={handleSendPacket}
            disabled={isSendingPacket}
          >
            <Send class="w-3.5 h-3.5" />
            <span>Dispatch Packet</span>
          </button>
        </div>
      </div>
    {/if}
  {/if}
</div>
