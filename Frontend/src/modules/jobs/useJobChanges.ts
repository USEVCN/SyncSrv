import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import type { Discovery, Job, SourceChange } from "../../shared/types";

type State = { changes: SourceChange[]; discovery?: Discovery; total: number;
  sourceError: string; centerError: string; loaded: boolean };
const initial: State = { changes: [], total: 0, sourceError: "", centerError: "", loaded: false };
export function useJobChanges(job: Job) {
  const [data, setData] = useState(initial);
  useEffect(() => {
    let active = true;
    setData(initial);
    const refresh = async () => {
      const [source, center] = await Promise.allSettled([
        job.sourceMode === "AGENT" ? api<{ entries: SourceChange[]; total: number }>(`/source-changes?jobId=${encodeURIComponent(job.id)}`) : Promise.resolve({ entries: [], total: 0 }),
        api<Discovery[]>("/discovery"),
      ]);
      if (!active) return;
      setData(previous => ({
        changes: source.status === "fulfilled" ? source.value.entries.filter(c => c.jobId === job.id) : previous.changes,
        total: source.status === "fulfilled" ? source.value.total : previous.total,
        discovery: center.status === "fulfilled" ? center.value.find(d => d.jobId === job.id) : previous.discovery,
        sourceError: source.status === "rejected" ? "源端变化状态暂不可用，已显示的状态可能过期；服务端待处理路径仍单独读取。请确认服务端已升级。" : "",
        centerError: center.status === "rejected" ? "服务端待处理状态刷新失败，已显示的状态可能过期。" : "",
        loaded: true,
      }));
    };
    const stop = pollWhileVisible(refresh, 1000);
    return () => { active = false; stop(); };
  }, [job.id, job.sourceMode]);
  return data;
}
