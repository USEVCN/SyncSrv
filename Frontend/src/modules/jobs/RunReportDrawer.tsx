import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Button, Card, Descriptions, Space, Spin, Tag } from "antd";
import { DownloadOutlined, ReloadOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import { Hint } from "../../shared/Hint";
import { bytes, executionPhase, phaseLabels, time } from "../../shared/format";
import { DataTable, Status, useAction } from "../../shared/ui";
import type { Run } from "../../shared/types";

type Stage = { phase:string; millis:number; readMillis:number; writeMillis:number; throttleMillis:number;
  transferBytes:number; verificationBytes:number; writtenBytes:number; readCalls:number; writeCalls:number };
type FileWork = { path:string; kind:string; status:string; millis:number; transferBytes:number;
  verificationBytes:number; writtenBytes:number; error:string };
export type RunReport = { run:Run; coverage:string; generatedAt:string; queueMillis:number; wallMillis:number;
  configuration?: {globalLimitMiB:number; taskLimitMiB:number; sourceMode:string; sourceProtocol:string; targetProtocol:string; archiveProtocol:string};
  metrics?: {schemaVersion?:number; serverCopyBytes?:number; serverCopyMillis?:number; elapsedMillis:number; workerCpuMillis:number; peakHeapBytes:number; lastProgressAt:string;
    scannedEntries:number; resumedBytes:number; resumeCount:number; checkpointRestarts:number; checksumCount:number;
    recoveredOperations:number; cleanupFiles:number; cleanupBytes:number; stages:Stage[]; slowestOperations:FileWork[]; notes:string[]} };
const duration = (ms:number) => ms < 0 ? "未采集" : ms < 1000 ? `${ms} ms` : ms < 60000
  ? `${(ms/1000).toFixed(1)} 秒` : `${Math.floor(ms/60000)} 分 ${Math.floor(ms%60000/1000)} 秒`;
const active = (r:Run) => ["QUEUED","RUNNING","PAUSING"].includes(r.status);

export function RunReportDrawer({runId,onClose}:{runId:string;onClose:()=>void}) {
  const [report,setReport]=useState<RunReport>();
  const [error,setError]=useState("");
  const [revision,setRevision]=useState(0);
  const [cleanupMessage,setCleanupMessage]=useState("");
  const action=useAction();
  useEffect(()=>{
    let closed=false;
    setReport(undefined);setError("");
    const load=async()=>{
      let repeat=true;
      try {
        const next=await api<RunReport>(`/runs/${runId}/report`);
        if(!closed){setReport(next);setError("");repeat=active(next.run);}
      } catch(e) { if(!closed)setError((e as Error).message); }
      return repeat;
    };
    const stop=pollWhileVisible(load,2000);
    return ()=>{closed=true;stop();};
  },[runId,revision]);
  const m=report?.metrics;
  const totalRead=m?.stages.reduce((s,r)=>s+r.transferBytes+r.verificationBytes,0) || 0;
  const totalWrite=m?.stages.reduce((s,r)=>s+r.writtenBytes,0) || 0;
  const elapsed=m?.elapsedMillis || 0;
  const exportReport=()=>{
    const url=URL.createObjectURL(new Blob([JSON.stringify(report,null,2)],{type:"application/json"}));
    const link=document.createElement("a");link.href=url;link.download=`syncsrv-run-${runId}.json`;link.click();
    window.setTimeout(()=>URL.revokeObjectURL(url),1000);
  };
  return <ResizableDrawer storageKey="report" title="执行性能报告" open initialWidth={1100} onClose={onClose} extra={<Space>
    <Button icon={<ReloadOutlined />} onClick={()=>setRevision(v=>v+1)}>刷新</Button>
    <Button icon={<DownloadOutlined />} disabled={!report} onClick={exportReport}>导出 JSON</Button>
  </Space>}>
    {error && <Hint type="error" label="报告读取失败" title={error} />}
    {!report ? <Spin /> : <>
      <Space wrap className="space-bottom"><Status value={report.run.status} />
        <Tag>{report.coverage==="LIVE" ? "实时测量" : report.coverage==="LEGACY" ? "历史摘要" : report.coverage==="PARTIAL" ? "中断前采样" : "已保存"}</Tag>
        <Hint label="报告统计口径" title="记录中心服务本次执行；源目录 → 备份库在源端 Agent 完成的上传不计入中心吞吐。存储端复制的数据不经过中心，仍由中心回读校验；复制耗时不含校验。并发执行的阶段耗时、读取、写入和限速等待均为各线程累计值，可能超过执行总耗时；阶段速率是累计字节除以累计等待，不代表汇总网速，其余包括哈希计算、元数据操作和审计提交。内存为服务进程采样峰值，CPU 为执行工作线程累计耗时。运行中每 10 秒持久化一次，结束时保存最终报告和签名。" />
      </Space>
      {report.coverage==="LEGACY" && <Hint type="warning" label="旧执行未采集性能指标" title="此执行发生在性能采集启用之前，只能显示原有时间、传输量、状态和错误，无法补算阶段耗时、校验量或瞬时速度。" />}
      {report.coverage==="PARTIAL" && <Hint type="warning" label="报告包含中断前的数据" title="异常中断后仅能保留最近一次持久化的统计，最多可能缺失最后 10 秒及持久化阻塞期间的数据。" />}
      <Descriptions bordered size="small" column={2} className="space-top" items={[
        {key:"id",label:"执行编号",children:<span className="mono">{runId}</span>,span:2},
        {key:"start",label:"执行开始",children:time(report.run.actualStartedAt || report.run.startedAt)},
        {key:"end",label:"结束时间",children:time(report.run.completedAt)},
        {key:"wall",label:"执行耗时",children:duration(m ? elapsed : report.wallMillis)},
        {key:"queue",label:"排队耗时",children:report.coverage==="LEGACY" ? "未采集" : duration(report.queueMillis)},
        ...((report.run.progress?.concurrencyLimit || 0)>0 ? [{key:"concurrency",label:"中心文件并发",children:`当前 ${report.run.progress!.activeFiles ?? 0} / 上限 ${report.run.progress!.concurrencyLimit} · 峰值 ${report.run.progress!.peakActiveFiles ?? 0}`}]:[]),
        ...((report.run.progress?.peakChecks || 0)>0 ? [{key:"checks",label:"中心校验并发",children:`当前 ${report.run.progress!.activeChecks ?? 0} / 上限 ${report.run.progress!.concurrencyLimit} · 峰值 ${report.run.progress!.peakChecks}`}]:[]),
        {key:"phase",label:"当前阶段",children:executionPhase(report.run) || "准备中"},
        {key:"progress",label:"最近进展",children:m ? time(m.lastProgressAt) : "未采集"},
        {key:"path",label:"当前路径",children:<span className="mono wrap">{report.run.currentPath || "—"}</span>,span:2},
        {key:"bytes",label:"本次传输读取",children:bytes(report.run.bytes)},
        {key:"verify",label:"本次校验读取",children:m ? bytes(report.run.verifiedBytes) : "未采集"},
        {key:"write",label:"本次写入",children:m ? bytes(totalWrite) : "未采集"},
        {key:"throughput",label:<>全程平均写入 <Hint label="计算口径" title="本次写入量除以执行总耗时，包含校验和等待，不代表网卡实时速度。阶段读写速率见下表。" /></>,children:m && elapsed ? `${bytes(totalWrite/(elapsed/1000))}/s` : "未采集"},
        {key:"read",label:"总读取 / 写入",children:m ? `${bytes(totalRead)} / ${bytes(totalWrite)}` : "未采集"},
        {key:"copy-mode",label:"存储端直接复制",children:m?.serverCopyBytes ? `${bytes(m.serverCopyBytes)} · ${duration(m.serverCopyMillis || 0)} · SMB 存储端执行` : "本次尚无存储端复制记录",span:2},
        {key:"operations",label:"完成操作",children:`${report.run.done} / ${report.run.total}`},
        {key:"cpu",label:"执行线程 CPU 耗时",children:m ? duration(m.workerCpuMillis) : "未采集"},
        {key:"memory",label:"进程堆内存采样峰值",children:m ? bytes(m.peakHeapBytes) : "未采集"},
        ...(report.configuration ? [{key:"limits",label:"限速快照",children:`全局 ${report.configuration.globalLimitMiB || "不限"} / 任务 ${report.configuration.taskLimitMiB || "不限"} MiB/s`},
          {key:"protocols",label:"源目录 → 备份库 → 归档库",children:`${report.configuration.sourceProtocol} → ${report.configuration.targetProtocol} → ${report.configuration.archiveProtocol}`}] : []),
        ...(m ? [{key:"scan",label:"扫描 / 完整哈希次数",children:`${m.scannedEntries} / ${m.checksumCount}`},
          {key:"resume",label:"断点复用",children:`${m.resumeCount} 次 · ${bytes(m.resumedBytes)}`},
          {key:"retry",label:"断点弃用重传",children:`${m.checkpointRestarts} 次`},
          {key:"recover",label:"恢复未完成操作",children:`${m.recoveredOperations} 项`},
          {key:"cleanup",label:report.run.kind==="ARCHIVE_CLEANUP" ? "清理文件 / 释放空间" : "无主暂存清理",children:`${m.cleanupFiles} 个 · ${bytes(m.cleanupBytes)}`}] : []),
      ]} />
      {report.run.error && <Hint className="space-top" type="error" label="执行错误" title={report.run.error} />}
      {m && <>
        <Card className="space-top" title="阶段耗时与 I/O">
          <DataTable<Stage> rowKey="phase" size="small" pagination={false} dataSource={m.stages} scroll={{x:1100}} columns={[
            {title:"阶段",dataIndex:"phase",render:p=>phaseLabels[p] || p},
            {title:(m.schemaVersion || 1)>=2 ? "累计线程耗时" : "总耗时",dataIndex:"millis",render:duration},
            {title:"读取等待",dataIndex:"readMillis",render:duration},
            {title:"写入等待",dataIndex:"writeMillis",render:duration},
            {title:"限速等待",dataIndex:"throttleMillis",render:duration},
            {title:"读取量",render:(_,r)=>bytes(r.transferBytes+r.verificationBytes)},
            {title:"写入量",dataIndex:"writtenBytes",render:bytes},
            {title:"读取速率",render:(_,r)=>r.readMillis ? `${bytes((r.transferBytes+r.verificationBytes)*1000/r.readMillis)}/s` : "—"},
            {title:"写入速率",render:(_,r)=>r.writeMillis ? `${bytes(r.writtenBytes*1000/r.writeMillis)}/s` : "—"},
            {title:"读 / 写次数",render:(_,r)=>`${r.readCalls} / ${r.writeCalls}`},
          ]} />
        </Card>
        <Card className="space-top" title="最耗时的文件操作 · 最多 20 项">
          <DataTable<FileWork> rowKey={r=>`${r.kind}:${r.path}`} size="small" pagination={false} dataSource={m.slowestOperations} scroll={{x:850}} columns={[
            {title:"文件路径",dataIndex:"path",render:p=><span className="mono wrap">{p}</span>},
            {title:"操作",dataIndex:"kind"}, {title:"状态",dataIndex:"status",render:s=><Status value={s} />},
            {title:"耗时",dataIndex:"millis",render:duration},
            {title:"传输读取",dataIndex:"transferBytes",render:bytes},
            {title:"校验读取",dataIndex:"verificationBytes",render:bytes},
            {title:"写入",dataIndex:"writtenBytes",render:bytes},
          ]} />
        </Card>
        {m.notes.map(note=><Hint key={note} className="space-top" type="warning" title={note} />)}
      </>}
      {cleanupMessage && <Hint className="space-top" type="success" label="清理结果" title={cleanupMessage} />}
      {!active(report.run) && report.run.kind==="SYNC" && <Button className="space-top" loading={action.loading} onClick={()=>action.run(async()=>{
        const result=await api<{files:number;bytes:number;retained:number}>(`/jobs/${report.run.jobId}/cleanup`,"POST");
        setError("");return result;
      }).then(result=>{if(result)setCleanupMessage(`清理完成：${result.files} 个无主暂存文件，${bytes(result.bytes)}；保留 ${result.retained} 个受保护条目。`);})}>清理无主暂存文件</Button>}
    </>}
  </ResizableDrawer>;
}
