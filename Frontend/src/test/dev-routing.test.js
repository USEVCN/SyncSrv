import { createServer as createHttpServer } from "node:http";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterAll, beforeAll, expect, it } from "vitest";
import { createServer } from "vite";
import config from "../../vite.config";

let backend;
let vite;
let origin;
let cache;
const received = [];

beforeAll(async () => {
  cache = await mkdtemp(join(tmpdir(), "syncsrv-vite-routing-"));
  backend = createHttpServer((req, res) => {
    received.push(req.url || "");
    res.setHeader("Content-Type", "text/plain");
    res.end(`backend:${req.url}`);
  });
  await new Promise((resolve) => backend.listen(0, "127.0.0.1", resolve));
  const target = `http://127.0.0.1:${backend.address().port}`;
  const proxy = Object.fromEntries(Object.entries(config.server.proxy).map(([path, value]) => [
    path, { ...value, target },
  ]));
  vite = await createServer({
    ...config, configFile: false, cacheDir: cache,
    optimizeDeps: { noDiscovery: true, include: [] },
    server: { ...config.server, host: "127.0.0.1", port: 0, proxy, watch: null, hmr: false },
  });
  await vite.listen();
  origin = `http://127.0.0.1:${vite.httpServer.address().port}`;
});

afterAll(async () => {
  backend?.closeAllConnections();
  vite?.httpServer?.closeAllConnections();
  await vite?.close();
  if (cache) await rm(cache, { recursive: true, force: true });
  await new Promise((resolve, reject) => backend?.close((err) => err ? reject(err) : resolve()));
});

it("serves source-device page refreshes locally while proxying agent APIs and installer downloads", async () => {
  for (const path of ["/agents", "/agents?tab=devices", "/jobs"]) {
    const response = await fetch(origin + path, { headers: { Accept: "text/html" } });
    expect(response.status).toBe(200);
    expect(await response.text()).toContain('id="root"');
  }
  expect(received).toEqual([]);
  for (const path of ["/api/agents", "/agent/v1/config", "/agent/releases/0.5.0-dev/agent.exe", "/install", "/install?platform=windows"]) {
    const response = await fetch(origin + path);
    expect(await response.text()).toBe(`backend:${path}`);
  }
  expect(received).toHaveLength(5);
});
