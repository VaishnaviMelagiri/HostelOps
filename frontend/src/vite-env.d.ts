/// <reference types="vite/client" />

/**
 * Types for our own VITE_* environment variables, so `import.meta.env.VITE_API_BASE_URL` is a
 * known string rather than `any`. Add a line here whenever a new VITE_ variable is introduced.
 */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
