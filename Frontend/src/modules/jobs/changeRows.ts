import type { Discovery, Job, SourceChange } from "../../shared/types";
import { sourcePhases } from "./activity";

export type ChangeRow = {
  id: string; path: string; status: string; phase: string; receivedAt: string;
  source?: SourceChange; pending?: Discovery["pending"][number];
};
export const changeLabels: Record<string, string> = {
  WAITING: "待执行", RUNNING: "执行中", READY: "待提交", COMPLETED: "已完成", FAILED: "失败待重试",
};
export const changeColors: Record<string, string> = {
  WAITING: "gold", RUNNING: "processing", READY: "cyan", COMPLETED: "success", FAILED: "error",
};

// One row per exact path within this job. The two channels are stages of one workflow;
// never infer generation identity or completion from a matching path alone.
export function mergeJobChanges(job: Job, changes: SourceChange[], discovery?: Discovery): ChangeRow[] {
  const rows = new Map<string, ChangeRow>();
  for (const source of changes.filter(c => c.jobId === job.id)) {
    const previous = rows.get(source.path)?.source;
    if (previous && previous.updatedAt > source.updatedAt) continue;
    rows.set(source.path, {
      id: `${job.id}:${source.path}`, path: source.path, source,
      status: source.status, receivedAt: source.receivedAt,
      phase: source.phase === "AUTHORIZATION_REQUIRED" || source.phase === "SOURCE_BUSY" ? sourcePhases[source.phase] : source.status === "WAITING" ? (job.enabled ? `连续 ${job.intervalSeconds} 秒无新变化后执行` : "自动处理已暂停，变化已登记") : sourcePhases[source.phase] || source.phase,
    });
  }
  for (const pending of discovery?.jobId === job.id ? discovery.pending : []) {
    let row = rows.get(pending.path);
    if (!row) {
      row = { id: `${job.id}:${pending.path}`, path: pending.path, status: "WAITING",
        phase: "等待服务端处理", receivedAt: pending.firstSeen };
      rows.set(pending.path, row);
    }
    row.pending = pending;
    // A new source save can coexist with an older center retry. Keep the newest
    // source stage and expose the outstanding center record in this row's details.
    if (!row.source || ["READY", "COMPLETED"].includes(row.source.status)) {
      row.status = pending.error ? "FAILED" : "READY";
      row.phase = pending.error ? "服务端处理失败，等待重试" : "等待服务端归档与提交";
    }
  }
  return [...rows.values()].sort((a, b) => b.receivedAt.localeCompare(a.receivedAt));
}
