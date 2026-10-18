import { resolve } from 'node:path';
import { loadEnv } from 'vite';

/** Directory of the shared root .env (read by Docker Compose, the backend and Vite). */
export const ENV_DIR = resolve(import.meta.dirname, '..', '..');

const DEFAULT_SERVER_PORT = '8080';

/**
 * Where the dev server proxies /api and where `pnpm gen:api` downloads the OpenAPI document from.
 * VITE_DEV_API_PROXY_TARGET wins when it is not blank; otherwise the backend's own SERVER_PORT is used.
 *
 * @param {Record<string, string | undefined>} env
 * @returns {string} an origin without a trailing path, for example http://localhost:8080
 */
export function resolveApiTarget(env) {
  const override = env.VITE_DEV_API_PROXY_TARGET?.trim();
  if (override) {
    return override;
  }
  const port = env.SERVER_PORT?.trim() || DEFAULT_SERVER_PORT;
  if (!/^\d{1,5}$/.test(port) || Number(port) < 1 || Number(port) > 65535) {
    throw new Error(`SERVER_PORT must be a port number between 1 and 65535, got "${port}".`);
  }
  return `http://localhost:${port}`;
}

/**
 * Reads the two relevant keys from the root .env files (process environment included) and resolves the target.
 *
 * @param {string} mode Vite mode, for example "development"
 * @param {string} [envDir] directory holding the .env files
 * @returns {string}
 */
export function loadApiTarget(mode, envDir = ENV_DIR) {
  return resolveApiTarget(loadEnv(mode, envDir, ['SERVER_PORT', 'VITE_DEV_API_PROXY_TARGET']));
}
