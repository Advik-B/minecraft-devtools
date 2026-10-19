<script lang="ts">
  import { BookOpen, FileCode, Code, ExternalLink, X, Eye } from 'lucide-svelte'

  let embeddedUrl = $state<string | null>(null)
  let embeddedTitle = $state<string>('')

  function openEmbedded(url: string, title: string) {
    embeddedUrl = url
    embeddedTitle = title
  }

  function closeEmbedded() {
    embeddedUrl = null
    embeddedTitle = ''
  }
</script>

<div class="space-y-6">
  <!-- Documentation Cards Grid -->
  <div class="grid grid-cols-1 md:grid-cols-3 gap-6">
    
    <!-- Card 1: Scalar Reference -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box hover:border-primary/40 transition-colors">
      <div class="card-body p-5 space-y-3">
        <div class="flex items-center justify-between pb-1">
          <div class="w-9 h-9 rounded-lg bg-primary/10 text-primary flex items-center justify-center">
            <BookOpen class="w-5 h-5" />
          </div>
          <span class="badge badge-soft badge-primary font-mono text-[11px]">/docs/scalar</span>
        </div>
        <h2 class="card-title text-base font-bold text-base-content">Scalar API Reference</h2>
        <p class="text-xs text-base-content/70 leading-relaxed">
          Modern interactive API reference with offline JavaScript bundle, search, request playground, and live examples.
        </p>
        <div class="card-actions justify-end pt-2 border-t border-base-300">
          <button class="btn btn-sm btn-outline btn-primary gap-1.5" onclick={() => openEmbedded('/docs/scalar', 'Scalar API Reference')}>
            <Eye class="w-3.5 h-3.5" />
            <span>View Here</span>
          </button>
          <a href="/docs/scalar" target="_blank" rel="noreferrer" class="btn btn-sm btn-ghost gap-1">
            <ExternalLink class="w-3.5 h-3.5" />
            <span>Open Tab</span>
          </a>
        </div>
      </div>
    </div>

    <!-- Card 2: ReDoc UI -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box hover:border-secondary/40 transition-colors">
      <div class="card-body p-5 space-y-3">
        <div class="flex items-center justify-between pb-1">
          <div class="w-9 h-9 rounded-lg bg-secondary/10 text-secondary flex items-center justify-center">
            <FileCode class="w-5 h-5" />
          </div>
          <span class="badge badge-soft badge-secondary font-mono text-[11px]">/docs/redoc</span>
        </div>
        <h2 class="card-title text-base font-bold text-base-content">ReDoc UI</h2>
        <p class="text-xs text-base-content/70 leading-relaxed">
          Three-panel responsive OpenAPI documentation engine with deep linking and exhaustive schema definitions.
        </p>
        <div class="card-actions justify-end pt-2 border-t border-base-300">
          <button class="btn btn-sm btn-outline btn-secondary gap-1.5" onclick={() => openEmbedded('/docs/redoc', 'ReDoc UI')}>
            <Eye class="w-3.5 h-3.5" />
            <span>View Here</span>
          </button>
          <a href="/docs/redoc" target="_blank" rel="noreferrer" class="btn btn-sm btn-ghost gap-1">
            <ExternalLink class="w-3.5 h-3.5" />
            <span>Open Tab</span>
          </a>
        </div>
      </div>
    </div>

    <!-- Card 3: OpenAPI Spec -->
    <div class="card bg-base-200 border border-base-300 shadow-sm rounded-box hover:border-accent/40 transition-colors">
      <div class="card-body p-5 space-y-3">
        <div class="flex items-center justify-between pb-1">
          <div class="w-9 h-9 rounded-lg bg-accent/10 text-accent flex items-center justify-center">
            <Code class="w-5 h-5" />
          </div>
          <span class="badge badge-soft badge-accent font-mono text-[11px]">/openapi.json</span>
        </div>
        <h2 class="card-title text-base font-bold text-base-content">OpenAPI 3.0 JSON</h2>
        <p class="text-xs text-base-content/70 leading-relaxed">
          Raw machine-readable OpenAPI specification containing all endpoint routes, schemas, parameters, and responses.
        </p>
        <div class="card-actions justify-end pt-2 border-t border-base-300">
          <a href="/openapi.json" target="_blank" rel="noreferrer" class="btn btn-sm btn-outline gap-1.5">
            <ExternalLink class="w-3.5 h-3.5" />
            <span>Open JSON</span>
          </a>
        </div>
      </div>
    </div>

  </div>

  <!-- Embedded Documentation Mockup Window -->
  {#if embeddedUrl}
    <div class="mockup-window border border-base-300 bg-base-300 shadow-xl rounded-box">
      <!-- Browser Top Bar -->
      <div class="flex items-center justify-between px-4 py-2 bg-base-200 border-b border-base-300">
        <div class="flex items-center gap-2">
          <span class="font-semibold text-xs text-base-content">{embeddedTitle}</span>
          <kbd class="kbd kbd-xs font-mono text-[10px] bg-base-100">{embeddedUrl}</kbd>
        </div>
        <div class="flex items-center gap-2">
          <a
            href={embeddedUrl}
            target="_blank"
            rel="noreferrer"
            class="btn btn-xs btn-ghost gap-1"
            title="Open in new window"
          >
            <ExternalLink class="w-3 h-3" />
            <span>Pop out</span>
          </a>
          <button
            class="btn btn-xs btn-circle btn-ghost"
            aria-label="Close embedded docs"
            onclick={closeEmbedded}
          >
            <X class="w-3.5 h-3.5" />
          </button>
        </div>
      </div>

      <!-- Content Iframe -->
      <iframe
        src={embeddedUrl}
        title={embeddedTitle}
        class="w-full h-[680px] border-0 bg-base-100"
      ></iframe>
    </div>
  {/if}
</div>
