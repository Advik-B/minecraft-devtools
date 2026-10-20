/**
 * High-performance, zero-dependency Java syntax highlighter for the MCDT WebUI.
 * Tokenizes Java source code into sanitized, theme-aware HTML spans line-by-line.
 */

function escapeHtml(str: string): string {
  return str
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}

const TOKEN_REGEX = new RegExp(
  [
    '(?<blockComment>/\\*[\\s\\S]*?\\*/)',
    '(?<lineComment>//[^\\n]*)',
    '(?<string>"""[\\s\\S]*?"""|"(?:\\\\.|[^"\\\\])*")',
    "(?<char>'(?:\\\\.|[^'\\\\])+')",
    '(?<annotation>@[a-zA-Z_$][a-zA-Z0-9_$]*)',
    '(?<keyword>\\b(?:package|import|public|private|protected|class|interface|enum|record|extends|implements|static|final|abstract|void|return|if|else|for|while|do|switch|case|default|break|continue|new|this|super|throw|throws|try|catch|finally|synchronized|volatile|transient|native|strictfp|instanceof|assert|yield|permits|sealed|non-sealed)\\b)',
    '(?<primitive>\\b(?:boolean|byte|short|int|long|char|float|double|var)\\b)',
    '(?<literal>\\b(?:true|false|null)\\b)',
    '(?<number>\\b(?:0x[0-9a-fA-F_]+|[0-9][0-9_]*(?:\\.[0-9_]+)?(?:[eE][+-]?[0-9_]+)?[fFdDlL]?)\\b)',
    '(?<method>\\b[a-zA-Z_$][a-zA-Z0-9_$]*(?=\\s*\\())',
    '(?<type>\\b[A-Z][a-zA-Z0-9_$]*\\b)',
    '(?<ident>[a-zA-Z_$][a-zA-Z0-9_$]*)',
    '(?<other>[\\s\\S])'
  ].join('|'),
  'y'
)

function getClassForGroups(groups: Record<string, string | undefined>): string | null {
  if (groups.blockComment || groups.lineComment) return 'text-base-content/40 italic'
  if (groups.string || groups.char) return 'text-emerald-400'
  if (groups.annotation) return 'text-warning/80'
  if (groups.keyword) return 'text-secondary font-semibold'
  if (groups.primitive) return 'text-warning font-medium'
  if (groups.literal) return 'text-error font-medium'
  if (groups.number) return 'text-amber-400'
  if (groups.method) return 'text-primary'
  if (groups.type) return 'text-info font-medium'
  return null
}

/**
 * Transforms raw Java code into an array of HTML lines with syntax highlighting spans.
 */
export function highlightJava(code: string): string[] {
  if (!code) return []

  const rawLines: string[][] = [[]]
  let currentLine = 0

  function pushText(text: string, className: string | null) {
    const parts = text.split('\n')
    for (let i = 0; i < parts.length; i++) {
      if (i > 0) {
        currentLine++
        rawLines[currentLine] = []
      }
      const part = parts[i]
      if (part) {
        const escaped = escapeHtml(part)
        rawLines[currentLine].push(
          className ? `<span class="${className}">${escaped}</span>` : escaped
        )
      }
    }
  }

  TOKEN_REGEX.lastIndex = 0
  let match: RegExpExecArray | null

  while ((match = TOKEN_REGEX.exec(code)) !== null) {
    const text = match[0]
    const className = match.groups ? getClassForGroups(match.groups) : null
    pushText(text, className)
  }

  return rawLines.map(lineParts => {
    const html = lineParts.join('')
    return html.length > 0 ? html : '&nbsp;'
  })
}
