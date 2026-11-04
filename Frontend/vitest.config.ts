import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
export default defineConfig({
  cacheDir: "node_modules/.vite-vitest",
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    // Hosted runners spend substantially longer rendering the full Ant Design forms.
    // Rendering waits are bounded separately in the test setup.
    testTimeout: process.env.CI ? (process.platform === "win32" ? 60000 : 30000) : 5000,
    hookTimeout: process.env.CI ? 30000 : 10000,
  },
});
