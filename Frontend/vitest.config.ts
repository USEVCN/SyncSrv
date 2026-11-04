import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  cacheDir: "node_modules/.vite-vitest",
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    // Bound the heavy jsdom/Ant Design workers for both local and hosted runs.
    // Business assertion waits stay independently bounded in the test setup.
    maxWorkers: 2,
    testTimeout: process.platform === "win32" ? 60000 : 30000,
    hookTimeout: 30000,
  },
});
