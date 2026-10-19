<script lang="ts">
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

<div class="grid">
  <article>
    <header>
      <strong>Scalar API Reference</strong>
      <br /><small><code>/docs/scalar</code></small>
    </header>
    <p>Modern interactive API reference with offline JavaScript bundle, search, request playground, and rich examples.</p>
    <footer>
      <button class="outline" onclick={() => openEmbedded('/docs/scalar', 'Scalar API Reference')}>View Here</button>
      <a href="/docs/scalar" role="button" target="_blank" rel="noreferrer">Open Tab</a>
    </footer>
  </article>

  <article>
    <header>
      <strong>ReDoc UI</strong>
      <br /><small><code>/docs/redoc</code></small>
    </header>
    <p>Three-panel responsive OpenAPI documentation engine with deep linking and exhaustive schema definitions.</p>
    <footer>
      <button class="outline" onclick={() => openEmbedded('/docs/redoc', 'ReDoc UI')}>View Here</button>
      <a href="/docs/redoc" role="button" class="secondary" target="_blank" rel="noreferrer">Open Tab</a>
    </footer>
  </article>

  <article>
    <header>
      <strong>OpenAPI 3.0 JSON</strong>
      <br /><small><code>/openapi.json</code></small>
    </header>
    <p>Raw machine-readable OpenAPI specification containing all endpoint routes, schemas, parameters, and responses.</p>
    <footer>
      <a href="/openapi.json" role="button" class="contrast" target="_blank" rel="noreferrer">Open JSON</a>
    </footer>
  </article>
</div>

{#if embeddedUrl}
  <article>
    <header>
      <nav>
        <ul>
          <li><strong>{embeddedTitle}</strong> <small>({embeddedUrl})</small></li>
        </ul>
        <ul>
          <li><a href={embeddedUrl} target="_blank" rel="noreferrer">Open in Tab</a></li>
          <li><button aria-label="Close" onclick={closeEmbedded}></button></li>
        </ul>
      </nav>
    </header>
    <iframe
      src={embeddedUrl}
      title={embeddedTitle}
      style="width: 100%; height: 650px; border: 0;"
    ></iframe>
  </article>
{/if}
