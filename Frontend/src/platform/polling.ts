/** Poll serially while the page is visible, then refresh immediately on return. */
export function pollWhileVisible(task: () => Promise<void | boolean>, interval: number) {
  let stopped = false, running = false, finished = false;
  let timer: number | undefined;
  const load = async () => {
    if (stopped || running || finished || document.hidden) return;
    running = true;
    try { finished = (await task()) === false; }
    finally {
      running = false;
      if (!stopped && !finished && !document.hidden) timer = window.setTimeout(() => void load(), interval);
    }
  };
  const visibility = () => {
    window.clearTimeout(timer);
    if (!document.hidden) void load();
  };
  document.addEventListener("visibilitychange", visibility);
  void load();
  return () => { stopped = true; window.clearTimeout(timer); document.removeEventListener("visibilitychange", visibility); };
}
