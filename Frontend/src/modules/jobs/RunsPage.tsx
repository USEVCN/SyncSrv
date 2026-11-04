import { Hint } from "../../shared/Hint";
import { Alert, Button, Card, Input, Select, Space } from "antd";
import { PauseCircleOutlined, SearchOutlined } from "@ant-design/icons";
import { useState } from "react";
import { useStateData } from "../../app/State";
import { DataTable, PageHeader, Status, useAction } from "../../shared/ui";
import { executionPhase, runKindLabel, statusLabels, time } from "../../shared/format";
import { api } from "../../platform/http";
import { mergeActivity, sourcePhases, useSourceActivity, type Activity } from "./activity";
import { sourceTransfer, useSourceTransfers } from "./useSourceTransfers";
import { ExecutionProgress } from "./ExecutionProgress";
import { RunFilesDrawer } from "./RunFilesDrawer";
import { activeRun, useLiveRuns } from "./useLiveRuns";
import { RunDispositionModal } from "./RunDispositionModal";
import type { Run } from "../../shared/types";
import { RunReportDrawer } from "./RunReportDrawer";
import { ActivityDetail } from "./ActivityDetail";
import { ArchiveCleanupDrawer } from "./ArchiveCleanupDrawer";

import {useHistory} from "../../shared/useHistory";

export function RunsPage() {
  const {data,refresh}=useStateData();
  const [status,setStatus]=useState("");
  const [search,setSearch]=useState("");
  const [page,setPage]=useState(1);
  const [selected,setSelected]=useState<string>();
  const action=useAction();
  const [reportId,setReportId]=useState<string>();
  const [archiveRun,setArchiveRun]=useState<Run>();
  const [files,setFiles]=useState<{id:string;source:boolean}>();
  const sourceTransfers=useSourceTransfers(!!data?.jobs.some(j=>j.sourceMode==="AGENT"));
  const live=useLiveRuns(!!data?.runs.some(activeRun));
  const [dispositionRun,setDispositionRun]=useState<Run>();
  const source=useSourceActivity(!!data?.jobs.some(j=>j.sourceMode==="AGENT"));
  const history=useHistory<Run>("runs",data?.runs||[]);
  const changeStatus=(value:string)=>{setStatus(value);setPage(1);};
  if(!data)return null;
  const liveById=new Map(live.runs.map(run=>[run.id,run]));
  const all=mergeActivity(history.rows.map(run=>activeRun(run) ? liveById.get(run.id) || run : run),source.changes);
  const current=all.find(r=>r.id===selected || r.changes.some(c=>c.id===selected));
  const name=(r:Activity)=>data.jobs.find(j=>j.id===r.jobId)?.name || r.jobId;
  const matching=all.filter(r=>`${name(r)} ${r.run?.id || ""} ${r.run?.currentPath || ""} ${r.changes.map(c=>c.path).join(" ")}`.toLowerCase().includes(search.toLowerCase()));
  const rows=matching.filter(r=>!status || r.status===status);
  const filters=[{value:"",label:"已加载"},...Object.entries(statusLabels).map(([value,label])=>({value,label}))];
  const phase=(row:Activity)=>{
    if(row.run)return executionPhase(row.run) || "等待执行";
    if(row.phase==="DEBOUNCING") {
      const job=data.jobs.find(j=>j.id===row.jobId);
      return job?.enabled ? `连续 ${job.intervalSeconds} 秒无新变化后执行` : "自动处理已暂停，变化已登记";
    }
    return sourcePhases[row.phase] || row.phase;
  };
  return <>
    <PageHeader title="执行记录" description="源端变化到达即显示待执行，传输、归档与完成在同一列表追踪；关联文件变化在详情中查看。" eyebrow="ACTIVITY / 执行" />
    {source.error && <Hint className="space-bottom" type="warning" showIcon title={source.error} />}
    {live.error && <Alert className="space-bottom" type="warning" showIcon title={live.error} />}
    <Card className="list-table-card table-card">
      <div className="table-toolbar">
        <Input prefix={<SearchOutlined />} placeholder="搜索任务、路径或执行编号" value={search} onChange={e=>{setSearch(e.target.value);setPage(1);}} allowClear />
        <Select aria-label="执行状态" value={status} onChange={changeStatus} options={[{value:"",label:"全部状态"},...Object.entries(statusLabels).map(([value,label])=>({value,label}))]} />
      </div>
      <Space wrap className="space-bottom" style={{paddingInline:20}}>
        <div className="run-status-filters" role="group" aria-label="执行状态快捷筛选">
          {filters.map(({value,label})=><button key={value} type="button" className="run-status-filter"
            aria-pressed={status===value} onClick={()=>changeStatus(value)}>
            {label} <span>{value ? matching.filter(r=>r.status===value).length : matching.length}</span>
          </button>)}
        </div>
        <span className="muted">源端变化每秒更新 · 按执行批次合并显示 · 历史分批加载</span>{history.control}
      </Space>
      <DataTable<Activity> rowKey="id" dataSource={rows}
        pagination={{current:page,onChange:setPage,pageSize:10,showSizeChanger:false,showTotal:total=>`共 ${total} 条`}} columns={[
        {title:"任务 / 执行类型",render:(_,r)=><div className="table-name"><div><strong>{name(r)}</strong><small>{runKindLabel[r.run?.kind || "SYNC"] || r.run?.kind}{r.changes.length ? ` · ${r.changes.length} 条关联变化` : ""}</small></div></div>},
        {title:"状态",dataIndex:"status",render:v=><Status value={v} />},
        {title:"总体进度 / 实时速度",width:390,render:(_,r)=>{
          const transfer=sourceTransfer(r,sourceTransfers);
          return r.run ? <ExecutionProgress compact run={r.run} stale={!!live.error} />
            : transfer ? <ExecutionProgress compact run={transfer.run} stale={!transfer.telemetry} />
            : <small className="muted">{phase(r)} · 文件总量待采集</small>;
        }},
        {title:"触发方式",render:(_,r)=>r.run ? ({EVENTS:"源端变化",CALIBRATION:"独立校准",BASELINE:"首次基线",MANUAL:"手动 / 定时"}[r.run.trigger || "MANUAL"] || r.run.trigger) : "源端变化"},
        {title:"登记时间",render:(_,r)=>time(r.at)},
        {title:"操作",render:(_,r)=><Space>
          <Button type="link" onClick={()=>setSelected(r.run ? r.id : r.changes[0]?.id)}>详情</Button>
          {r.run && <Button type="link" onClick={()=>setFiles({id:r.run!.id,source:false})}>文件列表</Button>}
          {!r.run && sourceTransfer(r,sourceTransfers) && <Button type="link" onClick={()=>setFiles({id:sourceTransfer(r,sourceTransfers)!.run.id,source:true})}>文件列表</Button>}
          {r.run && <Button type="link" onClick={()=>setReportId(r.run!.id)}>性能报告</Button>}
          {r.run?.status==="FAILED" && <Button type="link" onClick={()=>setDispositionRun(r.run)}>失败处置</Button>}
          {r.run && ["RUNNING","QUEUED"].includes(r.status) && <Button icon={<PauseCircleOutlined />} loading={action.loading} onClick={()=>action.run(async()=>{
            await api(`/runs/${r.run!.id}/pause`,"POST");await refresh();
          },"暂停请求已发出")}>暂停</Button>}
        </Space>},
      ]} />
      {source.total>500 && <Hint label="列表范围" title={<>变化索引共 {source.total} 条路径，当前合并最近更新的 500 条；完整历史可在审计中心查询。</>} />}
    </Card>
    {files && <RunFilesDrawer key={files.id} runId={files.id} source={files.source} onClose={()=>setFiles(undefined)} />}
    {reportId && <RunReportDrawer key={reportId} runId={reportId} onClose={()=>setReportId(undefined)} />}
    {dispositionRun && <RunDispositionModal key={dispositionRun.id} run={dispositionRun} onClose={()=>setDispositionRun(undefined)} />}
    {archiveRun && data.jobs.find(job=>job.id===archiveRun.jobId) && <ArchiveCleanupDrawer key={archiveRun.id}
      job={data.jobs.find(job=>job.id===archiveRun.jobId)!} runs={data.runs} initialPlanId={archiveRun.target}
      onClose={()=>setArchiveRun(undefined)} onQueued={refresh} />}
    <ActivityDetail row={current} data={data} onClose={()=>setSelected(undefined)} refresh={refresh} onArchiveCleanup={setArchiveRun} />
  </>;
}
