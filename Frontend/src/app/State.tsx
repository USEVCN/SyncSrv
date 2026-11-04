import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { api } from "../platform/http";
import { pollWhileVisible } from "../platform/polling";
import type { AppState } from "../shared/types";

type StateContext = {
  data?: AppState;
  error: string;
  refresh: () => Promise<void>;
};
const Context = createContext<StateContext>({
  error: "",
  refresh: async () => {},
});
export function StateProvider({ children }: { children: ReactNode }) {
  const [data, setData] = useState<AppState>();
  const [error, setError] = useState("");
  const pending = useRef<Promise<void> | undefined>(undefined);
  const alive = useRef(true);
  const load = useCallback((): Promise<void> => {
    if (pending.current) return pending.current;
    const request = (async () => {
      try {
        const state = await api<AppState>("/state");
        if (alive.current) { setData(state); setError(""); }
      } catch (e) {
        if (alive.current) setError((e as Error).message);
      } finally { pending.current = undefined; }
    })();
    pending.current = request;
    return request;
  }, []);
  const refresh = useCallback(async () => {
    // A mutation needs a new read after any pre-mutation request has finished.
    if (pending.current) await pending.current;
    if (alive.current) await load();
  }, [load]);
  useEffect(() => {
    alive.current = true;
    const stop = pollWhileVisible(load, 3000);
    return () => {
      alive.current = false;
      stop();
    };
  }, [load]);
  return (
    <Context.Provider value={{ data, error, refresh }}>
      {children}
    </Context.Provider>
  );
}
export const useStateData = () => useContext(Context);
