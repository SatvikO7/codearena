import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

export default defineConfig({
  plugins: [react()],
  server: {
    // 0.0.0.0 so the dev server is reachable from outside its container.
    host: true,
    port: Number(process.env.FRONTEND_PORT ?? 5173),
    strictPort: true,
  },
  preview: {
    host: true,
    port: Number(process.env.FRONTEND_PORT ?? 5173),
  },
});
