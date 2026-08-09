import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true, // fail loudly if 5173 is taken, rather than silently moving to 5174 and
                      // breaking the backend's CORS allow-list
  },
});
