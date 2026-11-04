import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import type { Run } from "../../shared/types";
import type { Activity } from "./activity";
export type SourceTransfer = {run:Run;telemetry:boolean};
export function sourceTransfer(row:Activity,transfers:SourceTransfer[]) {
  if(row.run || ["DEBOUNCING","PREPARING"].includes(row.phase))return undefined;
  const started=row.changes.reduce((last,c)=>c.startedAt>last ? c.startedAt : last,"");
  return transfers.filter(t=>t.run.jobId===row.jobId && (!started || t.run.startedAt>=started))
    .sort((a,b)=>b.run.startedAt.localeCompare(a.run.startedAt))[0];
}
export function useSourceTransfers(enabled:boolean) {
  const [transfers,setTransfers]=useState<SourceTransfer[]>([]);
  useEffect(()=>{
    if(!enabled){setTransfers([]);return;}
    let closed=false;
    const load=async()=>{
      try{if(!document.hidden){const next=await api<SourceTransfer[]>("/source-transfers");if(!closed)setTransfers(Array.isArray(next) ? next : []);}}
      catch{if(!closed)setTransfers([]);}
    };
    const stop=pollWhileVisible(load,1000);return()=>{closed=true;stop();};
  },[enabled]);
  return transfers;
}
