import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import type { Run } from "../../shared/types";
import type { SourceChange } from "../../shared/types";

export type Activity = { id: string; jobId: string; status: string; phase: string; at: string; run?: Run; changes: SourceChange[] };
export const sourcePhases: Record<string,string> = {
  DEBOUNCING:"等待连续保存结束", PREPARING:"整理文件清单", CONNECTING_B:"连接备份库", HASHING_SOURCE:"计算源文件校验值",
  UPLOADING_B:"源目录正在推送到备份库", VERIFYING_B:"校验备份库",
  WAITING_COMMIT:"等待服务端归档与提交", CENTER_BACKUP:"服务端归档与提交恢复节点",
  COMPLETE:"备份与恢复节点已提交", SOURCE_RETRY:"等待源端重试", SOURCE_BUSY:"文件占用，等待自动重试",
  AUTHORIZATION_REQUIRED:"设备授权已撤销，等待重新授权",
};
export function mergeActivity(runs: Run[], changes: SourceChange[]): Activity[] {
  const result = new Map<string,Activity>();
  const byRun = new Map(runs.map(r => [r.id,r]));
  for (const run of runs) result.set(`run:${run.id}`, {id:`run:${run.id}`,jobId:run.jobId,status:run.status,phase:run.phase,at:run.queuedAt || run.startedAt,run,changes:[]});
  for (const change of changes) {
    const run = byRun.get(change.runId);
    // Match both identity and job, never attach a pending generation to an unrelated run.
    const key = run && run.jobId === change.jobId ? `run:${run.id}` : `source:${change.jobId}:${change.runId || "pending"}`;
    let row = result.get(key);
    if (!row) { row={id:key,jobId:change.jobId,status:"QUEUED",phase:change.phase,at:change.receivedAt,changes:[]}; result.set(key,row); }
    row.changes.push(change);
  }
  for (const row of result.values()) {
    if (row.run) continue;
    const active = row.changes.find(c => c.status === "RUNNING") || row.changes.find(c => c.status === "FAILED") || row.changes.find(c => c.status === "WAITING" || c.status === "READY") || row.changes[0];
    row.status = {WAITING:"QUEUED",READY:"QUEUED"}[active.status] || active.status;
    row.phase = active.phase;
    row.at = row.changes.reduce((earliest,c) => c.receivedAt < earliest ? c.receivedAt : earliest, row.at);
  }
  return [...result.values()].sort((a,b)=>b.at.localeCompare(a.at));
}
export function useSourceActivity(enabled: boolean) {
  const [changes,setChanges] = useState<SourceChange[]>([]);
  const [error,setError] = useState("");
  const [total,setTotal] = useState(0);
  useEffect(()=>{
    if (!enabled) {setChanges([]);setTotal(0);setError("");return;}
    let alive=true;
    const update=async()=>{
      try {
        const value=await api<{entries:SourceChange[];total:number}>("/source-changes");
        if(alive){setChanges(value.entries);setTotal(value.total);setError("");}
      }catch{if(alive)setError("源端变化刷新失败，当前显示上次收到的状态；正在重新连接。");}
    };
    const stop=pollWhileVisible(update,1000); return()=>{alive=false;stop();};
  },[enabled]);
  return {changes,error,total};
}
