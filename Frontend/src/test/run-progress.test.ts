import { expect, it } from "vitest";
import { runProgress, runReadSummary } from "../shared/runProgress";
import type { Run } from "../shared/types";
import { bytes } from "../shared/format";

const base = {status:"RUNNING",bytes:0,done:0,total:0} as Run;
it("formats sub-byte speeds and rejects invalid telemetry without NaN units",()=>{
  expect(bytes(0.5)).toBe("1 B");
  expect(bytes(-1)).toBe("—");
  expect(bytes(Infinity)).toBe("—");
  expect(bytes(NaN)).toBe("—");
});
it("shows resume verification progress before the operation plan is populated", () => {
  const run = {...base,phase:"VERIFYING_RESUME",phaseBytes:4*1024**2,phaseTotal:8*1024**2};
  expect(runProgress(run)).toBe(50);
  expect(runReadSummary(run)).toBe("当前文件 4.0 MiB / 8.0 MiB");
  expect(run.bytes).toBe(0);
});
it("preserves legacy operation progress and separates copied bytes from verification", () => {
  expect(runProgress({...base,done:1,total:4})).toBe(25);
  expect(runProgress(base)).toBe(0);
  expect(runProgress({...base,status:"COMPLETED"})).toBe(100);
  expect(runReadSummary({...base,bytes:1024,verifiedBytes:2048})).toBe("传输 1.0 KiB · 校验 2.0 KiB");
});
