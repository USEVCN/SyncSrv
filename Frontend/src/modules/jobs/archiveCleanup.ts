import type { Run, Snapshot } from "../../shared/types";

export type ArchiveItem = { id:string; kind:"VERSION"|"MANIFEST"; endpointId:string; location:string;
  size:number; hash:string; versionIds:string[]; snapshotId?:string };
export type ArchivePlan = {
  id:string; jobId:string; beforeMonth:string; cutoff:string; createdAt:string; actor:string;
  state:"READY"|"CLEANING"|"COMPLETED"|"FAILED"|"PAUSED";
  expired:Snapshot[]; retained:{snapshot:Snapshot;reasons:string[]}[]; items:ArchiveItem[]; estimatedBytes:number;
  runId?:string; runStatus?:string; error?:string; completedAt?:string; completedFiles?:number; deletedBytes?:number;
  missingFiles?:number; skippedFiles?:number; detailsOmitted?:boolean;
};
export const archiveTime = (value:string) => value
  ? new Date(value).toLocaleString("zh-CN", { timeZone:"Asia/Shanghai", hour12:false }) : "—";
export function cutoffMonth(months:number, now=new Date()) {
  const parts=new Intl.DateTimeFormat("en",{timeZone:"Asia/Shanghai",year:"numeric",month:"2-digit"}).formatToParts(now);
  const year=Number(parts.find(p=>p.type==="year")!.value);
  const month=Number(parts.find(p=>p.type==="month")!.value);
  const date=new Date(Date.UTC(year,month-1-months,1));
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth()+1).padStart(2,"0")}`;
}
export function archivePlanStatus(plan:ArchivePlan,runs:Run[]) {
  const related=runs.filter(r=>r.jobId===plan.jobId && r.kind==="ARCHIVE_CLEANUP" && r.target===plan.id);
  const run=plan.runId ? related.find(r=>r.id===plan.runId)
    : [...related].sort((a,b)=>(b.queuedAt || b.startedAt).localeCompare(a.queuedAt || a.startedAt))[0];
  if(plan.state==="COMPLETED") return {label:"已完成",active:false,run};
  const status=plan.runStatus || run?.status;
  if(status) return {label:({QUEUED:"待执行",RUNNING:"执行中",PAUSING:"正在暂停",PAUSED:"已暂停",FAILED:"失败",INTERRUPTED:"曾中断",COMPLETED:"已完成"} as Record<string,string>)[status] || status,
    active:["RUNNING","QUEUED","PAUSING"].includes(status),run};
  if(plan.runId || plan.state==="CLEANING")return {label:"执行状态待确认",active:true,run};
  return {label:({READY:"待确认",FAILED:"失败",PAUSED:"已暂停"} as Record<string,string>)[plan.state] || plan.state,active:false,run};
}
