import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  // Development and validation servers must not invalidate each other's optimized modules.
  cacheDir: "node_modules/.vite-dev",
  plugins: [react()],
  server: {
    host: "0.0.0.0",
    port: 18011,
    strictPort: true,
    // Match complete path segments so /agents stays a frontend page.
    proxy: Object.fromEntries(["^/api(?:/|\\?|$)", "^/agent(?:/|\\?|$)", "^/install(?:\\?|$)"].map((path) => [path, {
        target: "http://127.0.0.1:18010",
        // Keep the browser's Host so it matches Origin in the backend check.
        changeOrigin: false,
      }])),
  },
  build: {
    outDir: "../Backend/src/main/resources/static",
    emptyOutDir: true,
    chunkSizeWarningLimit: 1100,
  },
});
