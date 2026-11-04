import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// Slow hosted runners need a longer render budget; visibility and business
// assertions stay intact, including tests with their own explicit deadlines.
configure({ asyncUtilTimeout: Reflect.get(globalThis, "process")?.env?.CI ? 5000 : 1000 });

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: vi
    .fn()
    .mockImplementation((query) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
});
class ResizeObserver {
  observe() {}
  unobserve() {}
  disconnect() {}
}
vi.stubGlobal("ResizeObserver", ResizeObserver);
