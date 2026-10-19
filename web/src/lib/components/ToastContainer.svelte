<script lang="ts">
  import { toastStore } from '../toast.svelte'
  import { CheckCircle2, AlertCircle, Info, AlertTriangle, X } from 'lucide-svelte'
</script>

{#if toastStore.toasts.length > 0}
  <div class="toast toast-bottom toast-end fixed bottom-6 right-6 z-50 p-2 space-y-2 pointer-events-none">
    {#each toastStore.toasts as toast (toast.id)}
      <div
        role="alert"
        class="alert alert-soft shadow-lg pointer-events-auto flex items-center gap-2 border border-base-300 bg-base-200"
        class:alert-info={toast.type === 'info'}
        class:alert-success={toast.type === 'success'}
        class:alert-warning={toast.type === 'warning'}
        class:alert-error={toast.type === 'error'}
      >
        {#if toast.type === 'success'}
          <CheckCircle2 class="w-4 h-4 text-success shrink-0" />
        {:else if toast.type === 'error'}
          <AlertCircle class="w-4 h-4 text-error shrink-0" />
        {:else if toast.type === 'warning'}
          <AlertTriangle class="w-4 h-4 text-warning shrink-0" />
        {:else}
          <Info class="w-4 h-4 text-info shrink-0" />
        {/if}
        <span class="text-xs sm:text-sm font-medium">{toast.message}</span>
        <button
          class="btn btn-xs btn-ghost btn-circle ml-auto"
          aria-label="Dismiss toast"
          onclick={() => toastStore.dismiss(toast.id)}
        >
          <X class="w-3.5 h-3.5" />
        </button>
      </div>
    {/each}
  </div>
{/if}
