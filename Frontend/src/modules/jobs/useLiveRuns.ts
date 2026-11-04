import { useEffect, useState } from "react";
import { api, ApiError } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import type { Run } from "../../shared/types";
export const activeRun=(run:Run)=>["RUNNING","QUEUED","PAUSING"].includes(run.status);
export function useLiveRuns(enabled:boolean) {
  const [runs,setRuns]=useState<Run[]>([]);
  const [error,setError]=useState("");
  useEffect(()=>{
    if(!enabled){setRuns([]);setError("");return;}
    let closed=false;
    const load=async()=>{
      try {
        if(!document.hidden){
          const next=await api<Run[]>("/runs/live");
          if(!closed){setRuns(next);setError("");}
        }
      }catch(e){if(!closed){setError(e instanceof ApiError && e.status===404
        ? "实时文件进度需要新版服务端，请在当前备份结束后重启 IDEA 中的后端。"
        : "实时进度刷新失败，当前显示上次采样；正在重新连接。");}}
    };
    const stop=pollWhileVisible(load,1000);return()=>{closed=true;stop();};
  },[enabled]);
  return {runs,error};
}
