import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useSourceActivity } from "../modules/jobs/activity";
import { useSourceTransfers,sourceTransfer } from "../modules/jobs/useSourceTransfers";
import { useLiveRuns } from "../modules/jobs/useLiveRuns";
import { api } from "../platform/http";
import type { Activity } from "../modules/jobs/activity";
import type { Run } from "../shared/types";
vi.mock("../platform/http",async()=>({...await vi.importActual("../platform/http"),api:vi.fn()}));
afterEach(()=>vi.mocked(api).mockReset());
describe("source status lifecycle",()=>{
  it("clears source activity when the last source device task is removed",async()=>{
    vi.mocked(api).mockResolvedValue({entries:[{id:"change1"}],total:1});
    const {result,rerender}=renderHook(({enabled})=>useSourceActivity(enabled),{initialProps:{enabled:true}});
    await waitFor(()=>expect(result.current.total).toBe(1));
    rerender({enabled:false});
    expect(result.current.changes).toEqual([]);expect(result.current.total).toBe(0);
  });
  it("clears source transfers when disabled and ignores an obsolete in-flight result",async()=>{
    let resolve!:(data:unknown)=>void;
    vi.mocked(api).mockImplementation(()=>new Promise(done=>{resolve=done;}));
    const {result,rerender}=renderHook(({enabled})=>useSourceTransfers(enabled),{initialProps:{enabled:true}});
    rerender({enabled:false});
    await act(async()=>resolve([{run:{id:"old"},telemetry:true}]));
    expect(result.current).toEqual([]);
  });
  it("selects the latest session when retries produce multiple source transfers",()=>{
    const row={jobId:"job",phase:"UPLOADING_B",changes:[{startedAt:"2026-09-19T01:00:00Z"}]} as Activity;
    const old={run:{id:"old",jobId:"job",startedAt:"2026-09-19T01:01:00Z"} as Run,telemetry:true};
    const latest={run:{...old.run,id:"latest",startedAt:"2026-09-19T01:02:00Z"},telemetry:true};
    expect(sourceTransfer(row,[old,latest])?.run.id).toBe("latest");
  });
  it("retains the last progress sample and marks it stale on a failed refresh",async()=>{
    vi.useFakeTimers();
    try {
      vi.mocked(api).mockResolvedValueOnce([{id:"run1"}]).mockRejectedValueOnce(new Error("offline"));
      const {result,unmount}=renderHook(()=>useLiveRuns(true));
      await act(async()=>{});
      await act(async()=>{await vi.advanceTimersByTimeAsync(1000);});
      expect(result.current.runs[0].id).toBe("run1");
      expect(result.current.error).toContain("上次采样");
      unmount();
    } finally {vi.useRealTimers();}
  });
});
