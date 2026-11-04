import { afterEach, describe, expect, it, vi } from "vitest";
import { pollWhileVisible } from "../platform/polling";

afterEach(() => vi.useRealTimers());
describe("visible-page polling", () => {
  it("does not overlap slow reads and refreshes when the page becomes visible", async () => {
    vi.useFakeTimers();
    let hidden=false, resolve!:()=>void;
    vi.spyOn(document,"hidden","get").mockImplementation(()=>hidden);
    const task=vi.fn().mockImplementationOnce(()=>new Promise<void>(done=>{resolve=done;})).mockResolvedValue(undefined);
    const stop=pollWhileVisible(task,1000);
    await vi.advanceTimersByTimeAsync(10000);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(task).toHaveBeenCalledTimes(1);
    resolve();await Promise.resolve();
    hidden=true;document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(10000);
    expect(task).toHaveBeenCalledTimes(1);
    hidden=false;document.dispatchEvent(new Event("visibilitychange"));
    await Promise.resolve();expect(task).toHaveBeenCalledTimes(2);
    stop();await vi.advanceTimersByTimeAsync(10000);
    expect(task).toHaveBeenCalledTimes(2);
  });
  it("waits while initially hidden and stops when a terminal result is received",async()=>{
    vi.useFakeTimers();let hidden=true;
    vi.spyOn(document,"hidden","get").mockImplementation(()=>hidden);
    const task=vi.fn().mockResolvedValue(false);
    const stop=pollWhileVisible(task,1000);
    expect(task).not.toHaveBeenCalled();
    hidden=false;document.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(1000);
    document.dispatchEvent(new Event("visibilitychange"));
    expect(task).toHaveBeenCalledOnce();stop();
  });
});
