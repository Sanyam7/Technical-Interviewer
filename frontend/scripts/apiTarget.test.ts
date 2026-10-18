import { existsSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { ENV_DIR, loadApiTarget, resolveApiTarget } from './apiTarget.mjs';

describe('resolveApiTarget', () => {
  it('defaults to port 8080 when nothing is configured', () => {
    expect(resolveApiTarget({})).toBe('http://localhost:8080');
  });

  it('follows the backend SERVER_PORT', () => {
    expect(resolveApiTarget({ SERVER_PORT: '18084' })).toBe('http://localhost:18084');
  });

  it('trims SERVER_PORT and treats a blank one as unset', () => {
    expect(resolveApiTarget({ SERVER_PORT: ' 9090 ' })).toBe('http://localhost:9090');
    expect(resolveApiTarget({ SERVER_PORT: '   ' })).toBe('http://localhost:8080');
    expect(resolveApiTarget({ SERVER_PORT: '' })).toBe('http://localhost:8080');
  });

  it('lets a non-blank VITE_DEV_API_PROXY_TARGET override SERVER_PORT', () => {
    expect(
      resolveApiTarget({ SERVER_PORT: '9090', VITE_DEV_API_PROXY_TARGET: 'http://backend:7000' }),
    ).toBe('http://backend:7000');
    expect(resolveApiTarget({ VITE_DEV_API_PROXY_TARGET: ' http://backend:7000 ' })).toBe(
      'http://backend:7000',
    );
  });

  it('ignores a blank VITE_DEV_API_PROXY_TARGET', () => {
    expect(resolveApiTarget({ SERVER_PORT: '9090', VITE_DEV_API_PROXY_TARGET: '' })).toBe(
      'http://localhost:9090',
    );
    expect(resolveApiTarget({ SERVER_PORT: '9090', VITE_DEV_API_PROXY_TARGET: '  ' })).toBe(
      'http://localhost:9090',
    );
  });

  it('accepts the whole port range and rejects everything else', () => {
    expect(resolveApiTarget({ SERVER_PORT: '1' })).toBe('http://localhost:1');
    expect(resolveApiTarget({ SERVER_PORT: '65535' })).toBe('http://localhost:65535');
    for (const invalid of ['0', '65536', '80a', '-1', '8080.5', '123456', 'http://x']) {
      expect(() => resolveApiTarget({ SERVER_PORT: invalid })).toThrow(
        `SERVER_PORT must be a port number between 1 and 65535, got "${invalid}".`,
      );
    }
  });
});

describe('loadApiTarget', () => {
  let envDir: string;

  beforeEach(() => {
    envDir = mkdtempSync(join(tmpdir(), 'api-target-'));
    vi.stubEnv('SERVER_PORT', undefined);
    vi.stubEnv('VITE_DEV_API_PROXY_TARGET', undefined);
  });

  afterEach(() => {
    rmSync(envDir, { recursive: true, force: true });
  });

  it('reads SERVER_PORT from the .env file', () => {
    writeFileSync(join(envDir, '.env'), 'POSTGRES_PASSWORD=secret\nSERVER_PORT=18084\n');

    expect(loadApiTarget('development', envDir)).toBe('http://localhost:18084');
  });

  it('treats a blank VITE_DEV_API_PROXY_TARGET line as unset', () => {
    writeFileSync(join(envDir, '.env'), 'SERVER_PORT=18084\nVITE_DEV_API_PROXY_TARGET=\n');

    expect(loadApiTarget('development', envDir)).toBe('http://localhost:18084');
  });

  it('honours a VITE_DEV_API_PROXY_TARGET override from the .env file', () => {
    writeFileSync(
      join(envDir, '.env'),
      'SERVER_PORT=18084\nVITE_DEV_API_PROXY_TARGET=http://backend:7000\n',
    );

    expect(loadApiTarget('development', envDir)).toBe('http://backend:7000');
  });

  it('prefers the process environment over the .env file', () => {
    writeFileSync(join(envDir, '.env'), 'SERVER_PORT=18084\n');
    vi.stubEnv('SERVER_PORT', '19000');

    expect(loadApiTarget('development', envDir)).toBe('http://localhost:19000');
  });

  it('falls back to 8080 without any .env file', () => {
    expect(loadApiTarget('development', envDir)).toBe('http://localhost:8080');
  });

  it('points at the repository root by default', () => {
    expect(ENV_DIR).toBe(resolve(import.meta.dirname, '..', '..'));
    expect(existsSync(join(ENV_DIR, '.env.example'))).toBe(true);
  });
});
