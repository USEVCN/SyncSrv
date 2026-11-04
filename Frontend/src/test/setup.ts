import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach, vi } from "vitest";

// Rendering on slower hosts needs a bounded budget; visibility and business
// assertions stay intact, including tests with their own explicit deadlines.
configure({ asyncUtilTimeout: 5000 });

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
