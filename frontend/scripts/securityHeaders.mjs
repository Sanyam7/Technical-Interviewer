/**
 * The HTTP headers the static host must send with the single-page app (SECURITY.md, WEB-2). Cloudflare Pages and Netlify read
 * them from a file named `_headers` next to index.html; the Vite build writes it (see vite.config.ts). The self-hosted web
 * container (Caddy) gets the same headers as a snippet (`pnpm caddy:headers`, run by deploy/Dockerfile).
 *
 * The policy: scripts only from the app's own origin (no inline script, no eval, so an injected script tag runs nothing); styles may
 * be inline because CodeMirror and React style attributes need it; the browser may talk only to the app's origin and the API's origin;
 * nothing may frame the app, embed objects or change the base URL.
 */

/** @param {string | undefined} apiBaseUrl the API's address (VITE_API_BASE_URL); blank when the app and the API share an origin */
export function connectSources(apiBaseUrl) {
  const sources = ["'self'"];
  const base = apiBaseUrl?.trim();
  if (base) {
    let origin;
    try {
      origin = new URL(base).origin;
    } catch {
      throw new Error(
        `VITE_API_BASE_URL must be a full URL such as https://api.example.com, got "${base}".`,
      );
    }
    if (origin === 'null') {
      throw new Error(`VITE_API_BASE_URL must be an http(s) URL, got "${base}".`);
    }
    sources.push(origin);
  }
  return sources;
}

/** @param {string | undefined} apiBaseUrl */
export function contentSecurityPolicy(apiBaseUrl) {
  return [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    `connect-src ${connectSources(apiBaseUrl).join(' ')}`,
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
    "frame-ancestors 'none'",
  ].join('; ');
}

/**
 * The headers per path pattern: every page gets the security headers, the hashed build assets are cached for good.
 *
 * @param {string | undefined} apiBaseUrl
 * @returns {{ path: string, headers: [string, string][] }[]}
 */
export function headerRules(apiBaseUrl) {
  return [
    {
      path: '/*',
      headers: [
        ['Content-Security-Policy', contentSecurityPolicy(apiBaseUrl)],
        ['X-Content-Type-Options', 'nosniff'],
        ['Referrer-Policy', 'no-referrer'],
        ['Permissions-Policy', 'camera=(), microphone=(), geolocation=(), payment=(), usb=()'],
        ['Cross-Origin-Opener-Policy', 'same-origin'],
        ['Strict-Transport-Security', 'max-age=31536000; includeSubDomains'],
      ],
    },
    { path: '/assets/*', headers: [['Cache-Control', 'public, max-age=31536000, immutable']] },
  ];
}

/** The `_headers` file of Cloudflare Pages and Netlify. @param {string | undefined} apiBaseUrl */
export function headersFile(apiBaseUrl) {
  return headerRules(apiBaseUrl)
    .flatMap(({ path, headers }) => [
      path,
      ...headers.map(([name, value]) => `  ${name}: ${value}`),
      '',
    ])
    .join('\n');
}

/**
 * The same headers as a Caddy snippet, for the self-hosted web container (deploy/Caddyfile imports it as `spa_headers`).
 *
 * @param {string | undefined} apiBaseUrl
 */
export function caddySnippet(apiBaseUrl) {
  /** @param {string} value */
  const quote = (value) => `"${value.replaceAll('\\', '\\\\').replaceAll('"', '\\"')}"`;
  const lines = ['(spa_headers) {'];
  for (const { path, headers } of headerRules(apiBaseUrl)) {
    const matcher = path === '/*' ? '' : ` ${path}`;
    lines.push(`\theader${matcher} {`);
    for (const [name, value] of headers) {
      lines.push(`\t\t${name} ${quote(value)}`);
    }
    lines.push('\t}');
  }
  lines.push('}', '');
  return lines.join('\n');
}
