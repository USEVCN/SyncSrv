import { Popover, Progress, Tag } from "antd";
import { useEffect, useState } from "react";
import type { Run } from "../../shared/types";
import { bytes, executionPhase, statusLabels } from "../../shared/format";
import { runProgress, runReadSummary } from "../../shared/runProgress";
import { sourcePhases } from "./activity";
import { activeRun } from "./useLiveRuns";
import { TransferSpeedGauge } from "./TransferSpeedGauge";
import "./execution-overview.css";
const validDuration=(ms:number|null|undefined):ms is number=>typeof ms==="number" && Number.isFinite(ms) && ms>=0;
export const elapsed=(ms:number|null|undefined)=>{
  if(!validDuration(ms))return "—";
  const seconds=Math.max(0,Math.floor(ms/1000));
  return `${Math.floor(seconds/3600).toString().padStart(2,"0")}:${Math.floor(seconds%3600/60).toString().padStart(2,"0")}:${(seconds%60).toString().padStart(2,"0")}`;
};
export function ExecutionProgress({run,stale=false,compact=false}:{run:Run;stale?:boolean;compact?:boolean}) {
  const [open,setOpen]=useState(false);
  const [,refreshSampleAge]=useState(0);
  const p=run.progress,active=activeRun(run);
  const cleanup=run.kind==="ARCHIVE_CLEANUP";
  const local=run.executionMode === "AGENT_LOCAL";
  const rateLabel=local ? "设备处理" : "传输";
  const routes:Record<string,string>={AGENT_LOCAL:"设备本地执行",AGENT_DIRECT:"设备直接传输",CENTER_RELAY:"中心中转"};
  const fresh=!!p && !stale && (!active || Date.now()-Date.parse(p.sampledAt)<6000);
  useEffect(()=>{
    if(!active || !p?.sampledAt)return;
    const remaining=Date.parse(p.sampledAt)+6000-Date.now();
    if(!Number.isFinite(remaining) || remaining<=0)return;
    const timer=window.setTimeout(()=>refreshSampleAge(n=>n+1),remaining);
    return()=>window.clearTimeout(timer);
  },[active,p?.sampledAt]);
  const verifyingOnly=fresh && run.status==="RUNNING" && p!.verificationBytesPerSecond>0 && p!.transferBytesPerSecond===0;
  const estimate=p && fresh && (p.planComplete || run.status==="COMPLETED") && validDuration(p.estimatedTotalMillis)
    ? `约 ${elapsed(p.estimatedTotalMillis)}` : active ? "估算中" : "—";
  const ratio=p?.planComplete ? `${bytes(p.processedBytes)} / ${bytes(p.totalBytes)}` : p ? "统计中" : "未采集";
  const files=p?.planComplete ? `${p.processedFiles} / ${p.totalFiles}` : p ? "统计中" : "未采集";
  const phase=executionPhase(run) || sourcePhases[run.phase] || "等待执行";
  const percent=p || run.status==="COMPLETED" ? runProgress(run) : 0;
  const route=run.executionMode && <small className="muted">{routes[run.executionMode] || run.executionMode}{run.executionNote ? ` · ${run.executionNote}` : ""}</small>;
  const concurrency=p && (p.concurrencyLimit || 0)>0 && <Tag color={active && fresh && (p.activeFiles || 0)>1 ? "cyan" : "default"}>
    {active ? `并行文件 ${fresh ? p.activeFiles ?? 0 : "—"} / ${p.concurrencyLimit}` : `并行峰值 ${p.peakActiveFiles ?? 0} / ${p.concurrencyLimit}`}
  </Tag>;
  const checks=p && ((p.activeChecks || 0)>0 || !active && (p.peakChecks || 0)>0) && <Tag color={active && fresh ? "cyan" : "default"}>
    {active ? `并行校验 ${fresh ? p.activeChecks : "—"} / ${p.concurrencyLimit}` : `校验峰值 ${p.peakChecks}`}
  </Tag>;
  const notes=<>
    {active && p && !p.planComplete && <small className="muted">正在整理本次处理清单，完成后显示总量；预计时长随执行进度估算。</small>}
    {!p && <small className="muted">{runReadSummary(run)} · 完整文件总量未采集</small>}
    {p && (run.phaseTotal || 0)>0 && <small className="muted">当前文件校验 {bytes(run.phaseBytes)} / {bytes(run.phaseTotal)}</small>}
    {run.kind==="SOURCE_UPLOAD" && <small className="muted">源目录 → 备份库 · 归档与恢复节点提交由服务端继续执行</small>}
    {run.kind!=="SOURCE_UPLOAD" && p?.planComplete && active && p.processedFiles===p.totalFiles && <small className="muted">{cleanup ? "清理文件已处理，等待提交整理报告" : "文件处理完毕，等待整批校验与提交"}</small>}
  </>;
  const summary=<>
    <div className="execution-phase">{phase}
      {active && <span>{!fresh ? cleanup ? "等待最新采样" : "传输速度 —" : cleanup && !verifyingOnly ? `已处理 ${p!.processedFiles} / ${p!.totalFiles} 项` : verifyingOnly ? `校验 ${bytes(p!.verificationBytesPerSecond)}/s` : `${rateLabel} ${bytes(run.status==="RUNNING" ? p!.transferBytesPerSecond : 0)}/s`}</span>}
    </div>
    <Progress percent={percent} size="small" showInfo={!!p?.planComplete || run.status==="COMPLETED"}
      status={run.status==="FAILED" ? "exception" : undefined} />
  </>;
  const details=<>
    {route}{concurrency}{checks}
    <div className="execution-totals">
      <span>数据 <strong>{ratio}</strong></span>
      <span>文件 <strong>{files}</strong></span>
      <span className="execution-time">已用 / 预计总时长 <strong>{elapsed(p?.elapsedMillis)} / {estimate}</strong></span>
    </div>
    {verifyingOnly ? <small className="muted">{cleanup ? "正在核验待清理文件" : "传输 0 B/s · 当前正在读取校验"}</small>
      : active && fresh && p!.verificationBytesPerSecond>0 && <small className="muted">校验 {bytes(p!.verificationBytesPerSecond)}/s</small>}
    {notes}
  </>;
  if(compact)return <Popover title="执行进度详情" content={<div className="execution-details">{details}</div>}
    open={open} onOpenChange={setOpen} trigger={["hover","focus","click"]}
    placement="topLeft" mouseEnterDelay={0.15} mouseLeaveDelay={0.2}
    classNames={{root:"hint-popover execution-progress-popover"}}
    styles={{root:{maxWidth:"calc(100vw - 32px)"}}}>
    <div className="execution-progress execution-progress-trigger" tabIndex={0} role="button"
      aria-label="查看执行进度详情" aria-expanded={open}
      onKeyDown={e=>{if(e.key==="Escape"){setOpen(false);e.stopPropagation();}
        if(e.key==="Enter" || e.key===" "){e.preventDefault();setOpen(value=>!value);}}}>
      {summary}
    </div>
  </Popover>;
  const speed=active && fresh ? run.status==="RUNNING" ? verifyingOnly ? p!.verificationBytesPerSecond : p!.transferBytesPerSecond : 0 : null;
  const statusColor=run.status==="COMPLETED" ? "success" : run.status==="FAILED" ? "error" : active ? "processing" : "default";
  return <section className="execution-overview" aria-label="执行进度概览">
    <div className="execution-overview-layout" style={cleanup ? {gridTemplateColumns:"minmax(0, 1fr)"} : undefined}>
      <div className="execution-overview-main">
        <div className="execution-overview-heading"><span>执行概览</span><Tag color={statusColor}>{statusLabels[run.status] || run.status}</Tag>{concurrency}{checks}</div>
        <h3>{phase}</h3>
        <div className="execution-overview-progress-label"><span>总体进度</span><strong>{p?.planComplete || run.status==="COMPLETED" ? <>{percent}<small>%</small></> : "—"}</strong></div>
        <Progress percent={percent} showInfo={false} size={{height:7}} strokeColor={run.status==="COMPLETED" ? "#42d392" : run.status==="FAILED" ? "#ff667a" : "#74caff"} />
        <div className="execution-metrics">
          <div className="execution-metric"><span>已处理 / 总数据</span><strong>{ratio}</strong></div>
          <div className="execution-metric"><span>已处理 / 总文件</span><strong>{files}</strong></div>
          <div className="execution-metric"><span>已用时长</span><strong>{elapsed(p?.elapsedMillis)}</strong></div>
          <div className="execution-metric"><span>预计总时长</span><strong>{estimate}</strong></div>
        </div>
        <div className="execution-overview-notes">{route}{notes}</div>
      </div>
      {!cleanup && <aside className="execution-speed-panel" aria-label="实时速度">
        <TransferSpeedGauge key={`${run.id}:${verifyingOnly ? "verification" : "transfer"}`} value={speed}
          label={verifyingOnly ? "校验" : rateLabel} verification={verifyingOnly}
          state={active ? !fresh ? "等待最新采样" : run.status==="RUNNING" ? "实时" : statusLabels[run.status] : statusLabels[run.status] || "已结束"} />
        {active && fresh && <div className="execution-speed-secondary"><span>{verifyingOnly ? "传输速度" : "校验速度"}</span><strong>{bytes(run.status==="RUNNING" && !verifyingOnly ? p!.verificationBytesPerSecond : 0)}/s</strong></div>}
      </aside>}
    </div>
  </section>;
}
