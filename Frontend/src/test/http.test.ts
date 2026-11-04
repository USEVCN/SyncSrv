import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../platform/http";

afterEach(()=>vi.useRealTimers());
const hang=()=>vi.spyOn(globalThis,"fetch").mockImplementation((_url,options)=>new Promise((_resolve,reject)=>{
  options?.signal?.addEventListener("abort",()=>reject(new DOMException("aborted","AbortError")));
}));
describe("API recovery",()=>{
  it("releases a stalled read so a later refresh can succeed",async()=>{
    vi.useFakeTimers();const fetcher=hang();
    const first=api("/state");
    const check=expect(first).rejects.toMatchObject({status:408,message:expect.stringContaining("读取服务状态超时")});
    await vi.advanceTimersByTimeAsync(30000);await check;
    fetcher.mockResolvedValueOnce(new Response(JSON.stringify({success:true,data:{ok:true}})));
    await expect(api("/state")).resolves.toEqual({ok:true});
    expect(vi.getTimerCount()).toBe(0);
  });
  it("does not retry an uncertain mutation and explains that its outcome is unknown",async()=>{
    vi.useFakeTimers();const fetcher=hang();
    const request=api("/restores","POST",{snapshotId:"s1"});
    const check=expect(request).rejects.toMatchObject({status:408,message:expect.stringContaining("执行结果尚未确认")});
    await vi.advanceTimersByTimeAsync(120000);await check;
    expect(fetcher).toHaveBeenCalledOnce();
  });
});
