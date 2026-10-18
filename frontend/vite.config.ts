import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import { loadEnv, type Plugin } from 'vite';
import { defineConfig } from 'vitest/config';

import { ENV_DIR, loadApiTarget } from './scripts/apiTarget.mjs';
import { headersFile } from './scripts/securityHeaders.mjs';

/** Writes the `_headers` file of the static host next to index.html (SECURITY.md, WEB-2). */
function securityHeaders(apiBaseUrl: string | undefined): Plugin {
  return {
    name: 'security-headers',
    apply: 'build',
    generateBundle() {
      this.emitFile({ type: 'asset', fileName: '_headers', source: headersFile(apiBaseUrl) });
    },
  };
}

export default defineConfig(({ mode }) => {
  const apiProxyTarget = loadApiTarget(mode);
  const apiBaseUrl = loadEnv(mode, ENV_DIR, 'VITE_').VITE_API_BASE_URL;

  return {
    plugins: [react(), tailwindcss(), securityHeaders(apiBaseUrl)],
    envDir: ENV_DIR,
    build: {
      // The code editor (CodeMirror, ~630 kB) is its own chunk, loaded only by the coding page.
      chunkSizeWarningLimit: 700,
    },
    server: {
      port: 5173,
      strictPort: true,
      proxy: {
        '/api': { target: apiProxyTarget, changeOrigin: false },
      },
    },
    test: {
      environment: 'jsdom',
      globals: false,
      setupFiles: ['./src/test/setup.ts'],
      restoreMocks: true,
      // Several user steps, each allowed up to 3 s (src/test/setup.ts), must fit into one test.
      testTimeout: 15_000,
      css: false,
    },
  };
});
