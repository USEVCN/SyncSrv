import type { Run } from "./types";
import { bytes } from "./format";

export function runProgress(run: Run) {
  if (run.status === "COMPLETED") return 100;
  if (run.progress) {
    const p=run.progress;
    if(!p.planComplete)return 0;
    const fraction=p.totalBytes>0 ? p.processedBytes/p.totalBytes : p.totalFiles>0 ? p.processedFiles/p.totalFiles : 0;
    return Math.max(0,Math.min(99,Math.floor(fraction*100)));
  }
  const ratio = (run.phaseTotal || 0) > 0
    ? (run.phaseBytes || 0) / run.phaseTotal!
    : run.total ? run.done / run.total : 0;
  return Math.max(0, Math.min(100, Math.floor(ratio * 100)));
}

export function runReadSummary(run: Run) {
  if ((run.phaseTotal || 0) > 0)
    return `当前文件 ${bytes(run.phaseBytes)} / ${bytes(run.phaseTotal)}`;
  return `传输 ${bytes(run.bytes)}${run.verifiedBytes ? ` · 校验 ${bytes(run.verifiedBytes)}` : ""}`;
}
