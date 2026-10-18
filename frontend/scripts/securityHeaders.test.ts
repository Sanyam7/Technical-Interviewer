import { describe, expect, it } from 'vitest';

import {
  caddySnippet,
  connectSources,
  contentSecurityPolicy,
  headersFile,
  headerRules,
} from './securityHeaders.mjs';

describe('the security headers of the static host', () => {
  it('lets the app talk to its own origin only when the API shares it', () => {
    expect(connectSources('')).toEqual(["'self'"]);
    expect(connectSources(undefined)).toEqual(["'self'"]);
  });

  it('adds the origin of a separate API, without its path', () => {
    expect(connectSources('https://api.example.com/v1/')).toEqual([
      "'self'",
      'https://api.example.com',
    ]);
  });

  it('refuses an API address that is not a URL, instead of writing a policy that blocks every call', () => {
    expect(() => connectSources('api.example.com')).toThrow(/full URL/);
    expect(() => connectSources('data:text/plain,x')).toThrow(/http\(s\)/);
  });

  it('forbids inline scripts, eval, framing, plugins and a changed base URL', () => {
    const policy = contentSecurityPolicy('https://api.example.com');
    expect(policy).toContain("script-src 'self'");
    expect(policy).not.toMatch(/script-src[^;]*unsafe/);
    expect(policy).toContain("frame-ancestors 'none'");
    expect(policy).toContain("object-src 'none'");
    expect(policy).toContain("font-src 'self' data:"); // Vite inlines small fonts as data: URIs
    expect(policy).toContain("base-uri 'self'");
    expect(policy).toContain("connect-src 'self' https://api.example.com");
  });

  it('is a valid _headers file: the policy for every path and caching for the hashed assets', () => {
    const file = headersFile('');
    expect(file.startsWith('/*\n  Content-Security-Policy: ')).toBe(true);
    expect(file).toContain('X-Content-Type-Options: nosniff');
    expect(file).toContain('/assets/*\n  Cache-Control: public, max-age=31536000, immutable');
  });

  it('gives the self-hosted web server (Caddy) exactly the headers of the _headers file', () => {
    const snippet = caddySnippet('https://api.example.com');
    expect(snippet.startsWith('(spa_headers) {\n\theader {\n')).toBe(true);
    for (const { headers } of headerRules('https://api.example.com')) {
      for (const [name, value] of headers) {
        expect(snippet).toContain(`\t\t${name} "${value}"`);
        expect(headersFile('https://api.example.com')).toContain(`  ${name}: ${value}`);
      }
    }
    expect(snippet).toContain(
      '\theader /assets/* {\n\t\tCache-Control "public, max-age=31536000, immutable"',
    );
  });
});
