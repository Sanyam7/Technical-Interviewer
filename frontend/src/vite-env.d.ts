/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Backend origin. Unset or empty means same-origin (the Vite dev proxy forwards /api). */
  readonly VITE_API_BASE_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
