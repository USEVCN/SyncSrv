import { act, renderHook, waitFor } from "@testing-library/react";
import { App } from "antd";
import { afterEach, describe, expect, it, vi } from "vitest";
import { StateProvider, useStateData } from "../app/State";
import { useAction } from "../shared/ui";
import { api } from "../platform/http";
import type { AppState } from "../shared/types";
vi.mock("../platform/http",()=>({api:vi.fn()}));
afterEach(()=>vi.mocked(api).mockReset());
describe("mutation refresh",()=>{
  it("reads again after a pre-mutation state request instead of skipping the refresh",async()=>{
    let resolve!:(value:AppState)=>void;
    vi.mocked(api).mockImplementationOnce(()=>new Promise<AppState>(done=>{resolve=done;}))
      .mockResolvedValueOnce({jobs:[{id:"new-job"}]} as AppState);
    const {result}=renderHook(()=>useStateData(),{wrapper:({children})=><StateProvider>{children}</StateProvider>});
    let refresh!:Promise<void>;
    act(()=>{refresh=result.current.refresh();});
    expect(api).toHaveBeenCalledTimes(1);
    await act(async()=>{resolve({jobs:[]} as unknown as AppState);await refresh;});
    expect(api).toHaveBeenCalledTimes(2);
    expect(result.current.data?.jobs[0].id).toBe("new-job");
  });
  it("submits a backup action only once when clicked twice before React rerenders",async()=>{
    let resolve!:()=>void;
    const submit=vi.fn(()=>new Promise<void>(done=>{resolve=done;}));
    const {result}=renderHook(()=>useAction(),{wrapper:({children})=><App>{children}</App>});
    let first!:Promise<unknown>,second!:Promise<unknown>;
    act(()=>{first=result.current.run(submit);second=result.current.run(submit);});
    expect(submit).toHaveBeenCalledOnce();
    await act(async()=>{resolve();await Promise.all([first,second]);});
    await waitFor(()=>expect(result.current.loading).toBe(false));
  });
});
