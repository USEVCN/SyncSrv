import { useResizableApp } from "../../shared/ResizableModal";
import { Button, Card, Input, Space, Tag } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { api, ApiError } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import { Hint } from "../../shared/Hint";
import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { bytes } from "../../shared/format";
import { DataTable } from "../../shared/ui";
import type { Job, Run } from "../../shared/types";
import { ArchiveCleanupPreview } from "./ArchiveCleanupPreview";
import { archivePlanStatus, archiveTime, cutoffMonth, type ArchivePlan } from "./archiveCleanup";
import "./archive-cleanup.css";
import {RetentionPolicy} from "./RetentionPolicy";

type Props={job:Job; runs:Run[]; initialPlanId?:string; onClose:()=>void; onQueued:()=>Promise<void>};
export function ArchiveCleanupDrawer(props:Props) {
  return <ArchiveCleanupContent key={`${props.job.id}:${props.initialPlanId || ""}`} {...props} />;
}
function ArchiveCleanupContent({job,runs,initialPlanId,onClose,onQueued}:Props) {
  const {modal,message}=useResizableApp();
  const [month,setMonth]=useState(()=>cutoffMonth(3));
  const [plan,setPlan]=useState<ArchivePlan>();
  const [plans,setPlans]=useState<ArchivePlan[]>([]);
  const [localRuns,setLocalRuns]=useState<Run[]>([]);
  const [previewing,setPreviewing]=useState(false);
  const [executing,setExecuting]=useState(false);
  const [error,setError]=useState("");
  const [historyError,setHistoryError]=useState("");
  const [refreshError,setRefreshError]=useState("");
  const [uncertain,setUncertain]=useState(false);
  const historyRequest=useRef(0);
  const confirmation=useRef<{destroy:()=>void}|null>(null);
  const latestPlan=useRef(plan);
  latestPlan.current=plan;
  const version=useRef(0);
  const submitting=useRef(false);
  const alive=useRef(true);
  const currentMonth=cutoffMonth(0);
  const validMonth=/^\d{4}-(0[1-9]|1[0-2])$/.test(month) && month<=currentMonth;
  const knownPlans=plan ? [plan,...plans.filter(p=>p.id!==plan.id)] : plans;
  const knownRuns=[...runs,...localRuns.filter(local=>!runs.some(run=>run.id===local.id))].map(run=>{
    const observed=knownPlans.find(p=>p.id===run.target && p.runId===run.id);
    return observed?.runStatus ? {...run,status:observed.runStatus} : run;
  });
  const activeJob=knownRuns.some(run=>run.jobId===job.id && ["RUNNING","QUEUED","PAUSING"].includes(run.status))
    || knownPlans.some(p=>archivePlanStatus(p,knownRuns).active);
  const state=plan && archivePlanStatus(plan,knownRuns);
  const canExecute=!!plan && plan.jobId===job.id && !plan.detailsOmitted && plan.state!=="COMPLETED" && !state?.active && !activeJob
    && !executing && !previewing && !uncertain && (plan.expired.length>0 || plan.items.length>0);
  const canExecuteNow=useRef(canExecute);
  canExecuteNow.current=canExecute;
  const loadHistory=useCallback(async()=>{
    const request=++historyRequest.current;
    const selectionRevision=version.current;
    const current=()=>alive.current && request===historyRequest.current;
    try {
      const next=await api<ArchivePlan[]>(`/jobs/${job.id}/archive/plans`);
      if(!current())return;
      setPlans(next);setHistoryError("");
      const selected=latestPlan.current;
      if(selected && selectionRevision===version.current){
        const summary=next.find(p=>p.id===selected.id);
        const updated=summary && !summary.detailsOmitted ? summary
          : await api<ArchivePlan>(`/jobs/${job.id}/archive/plans/${selected.id}`);
        if(current() && selectionRevision===version.current)setPlan(previous=>{
          if(previous?.id!==selected.id)return previous;
          // Completion is irreversible for this plan ID. A late summary must not
          // re-enable destructive actions after the detail endpoint confirmed it.
          if(previous.state==="COMPLETED" && updated.state!=="COMPLETED")return previous;
          return updated;
        });
      }
    } catch(e) {if(current())setHistoryError((e as Error).message);}
  },[job.id]);
  function closeConfirmation() {
    const current=confirmation.current;confirmation.current=null;current?.destroy();
  }
  function invalidateSelection() {version.current++;closeConfirmation();}
  useEffect(()=>{
    alive.current=true;
    const stop=pollWhileVisible(loadHistory,5000);
    return()=>{alive.current=false;version.current++;historyRequest.current++;closeConfirmation();stop();};
  },[loadHistory]);
  useEffect(()=>{
    if(!initialPlanId)return;
    const revision=++version.current;
    void api<ArchivePlan>(`/jobs/${job.id}/archive/plans/${initialPlanId}`).then(next=>{
      if(alive.current && revision===version.current){setPlan(next);setMonth(next.beforeMonth);}
    }).catch(e=>{if(alive.current && revision===version.current)setError((e as Error).message);});
  },[job.id,initialPlanId]);
  function changeMonth(value:string) {
    invalidateSelection();setMonth(value);setPlan(undefined);setError("");setPreviewing(false);
  }
  async function preview() {
    if(!validMonth || previewing || submitting.current)return;
    invalidateSelection();const revision=version.current;
    setPreviewing(true);setError("");setPlan(undefined);
    try {
      const next=await api<ArchivePlan>(`/jobs/${job.id}/archive/preview`,"POST",{beforeMonth:month,keepSnapshotIds:[]});
      if(alive.current && revision===version.current){setPlan(next);void loadHistory();}
    } catch(e) {if(alive.current && revision===version.current)setError((e as Error).message);}
    finally {if(alive.current && revision===version.current)setPreviewing(false);}
  }
  async function selectPlan(next:ArchivePlan) {
    invalidateSelection();const revision=version.current;
    setPreviewing(true);setPlan(undefined);setMonth(next.beforeMonth);setError("");
    try {
      const selected=await api<ArchivePlan>(`/jobs/${job.id}/archive/plans/${next.id}`);
      if(alive.current && revision===version.current)setPlan(selected);
    } catch(e) {if(alive.current && revision===version.current)setError((e as Error).message);}
    finally {if(alive.current && revision===version.current)setPreviewing(false);}
  }
  function confirm(selected:ArchivePlan) {
    if(confirmation.current || !canExecuteNow.current)return;
    const resuming=selected.state!=="READY";
    const revision=version.current;
    const times=selected.expired.map(snapshot=>snapshot.completedAt).sort();
    const dialog=modal.confirm({title:resuming ? "继续整理归档" : "确认整理归档",okText:resuming ? "确认继续整理" : "确认清理",cancelText:"取消",
      okButtonProps:{danger:true},
      afterClose:()=>{if(confirmation.current===dialog)confirmation.current=null;},
      content:<div className="archive-cleanup-confirm">
        <p>任务：{job.name}</p>
        <p>清理边界：{archiveTime(selected.cutoff)} 之前（北京时间）</p>
        <p>移除 {selected.expired.length} 个恢复节点，候选文件 {selected.items.length} 个，预计释放 {bytes(selected.estimatedBytes)}。</p>
        {times.length>0 && <p>将移除的节点范围：{archiveTime(times[0])} 至 {archiveTime(times[times.length-1])}。这些节点将不能再用于恢复。</p>}
        <p>{resuming ? "仅继续此计划剩余项目，不会扩大清理范围。" : "已列出的保留节点仍可完整恢复。"}源目录、备份库和被保留节点引用的归档文件不会删除。</p>
      </div>,
      onOk:async()=>{
        if(submitting.current)return;
        if(!alive.current || revision!==version.current || latestPlan.current?.id!==selected.id
            || !canExecuteNow.current){void message.warning("清理条件或执行状态已变化，请刷新后重新确认。");return;}
        version.current++;submitting.current=true;setExecuting(true);setError("");setRefreshError("");
        let accepted=false;
        try {
          const run=await api<Run>(`/jobs/${job.id}/archive/execute`,"POST",{planId:selected.id,confirmed:true});
          accepted=true;
          if(alive.current){
            setLocalRuns(current=>[run,...current.filter(previous=>previous.id!==run.id)]);
            setPlan(current=>current?.id===selected.id ? {...current,runId:run.id,runStatus:run.status} : current);
            void message.success("归档整理已加入执行队列");
          }
        } catch(e) {
          if(alive.current){
            const definitive=e instanceof ApiError && e.status>=400 && e.status<500 && e.status!==408;
            if(definitive)setError((e as Error).message);
            else setUncertain(true);
          }
        }
        // Submission and refreshing are separate outcomes: a refresh failure must not invite a retry.
        const refreshed=await Promise.allSettled([loadHistory(),Promise.resolve().then(onQueued)]);
        if(alive.current && refreshed.some(result=>result.status==="rejected"))
          setRefreshError(accepted ? "清理已提交；页面状态刷新失败，请查看执行记录。" : "执行状态刷新失败，请查看执行记录确认提交结果。");
        submitting.current=false;if(alive.current)setExecuting(false);
      },
    });
    confirmation.current=dialog;
  }
  return <ResizableDrawer storageKey="archive-cleanup" title={`${job.name} · 整理归档`} open initialWidth={1000} onClose={onClose}
    extra={<Button onClick={()=>void loadHistory()}>刷新</Button>}>
    <div className="archive-cleanup-content">
      <RetentionPolicy jobId={job.id}/>
      <Card size="small" title="按月份快捷清理" extra={<Hint iconOnly label="快捷清理说明"
        title="先预览清理范围与空间，再确认执行。按恢复节点完成时间划分月份；系统会保留必需的版本。每次整理及逐文件处置都会进入审计记录。" />}>
        <div className="archive-cleanup-filter">
          <label className="archive-cleanup-month">清理此月之前
            <Input aria-label="清理此月之前" type="month" value={month} max={currentMonth}
              disabled={executing} onChange={e=>changeMonth(e.target.value)} />
          </label>
          <Space wrap>{([[3,"近 3 个月"],[6,"近 6 个月"],[12,"近 1 年"]] as const).map(([months,label])=>
            <Button key={months} disabled={executing} onClick={()=>changeMonth(cutoffMonth(months))}>保留{label}</Button>)}</Space>
          <Button type="primary" disabled={!validMonth || executing} loading={previewing} onClick={()=>void preview()}>预览清理</Button>
          <Hint label="按月计算" title="快捷选项从当前北京时间所在月向前推算，截止到所选月份的 1 日 00:00。例如 2026 年 9 月选择保留近 3 个月，清理边界为 2026 年 6 月 1 日；不按过去 90 天滚动计算。" />
        </div>
      </Card>
      {error && <Hint type="error" label="整理操作异常" title={error} />}
      {refreshError && <Hint type="warning" label="状态刷新失败" title={refreshError} />}
      {uncertain && <Hint type="warning" label="提交结果待确认" title="未收到明确的提交结果，整理可能已经开始。请刷新执行记录，核对结果后重新打开整理界面；当前不会再次提交清理。" />}
      {plan && <>
        <div className="archive-cleanup-footer"><Space wrap><Tag color={plan.state==="COMPLETED" ? "success" : state?.active ? "processing" : "default"}>{state?.label}</Tag>
          <span className="muted">预览生成于 {archiveTime(plan.createdAt)}</span></Space>
          <Button danger type="primary" disabled={!canExecute} loading={executing} onClick={()=>confirm(plan)}>
            {plan.state==="COMPLETED" ? "整理已完成" : plan.state==="READY" ? "确认清理范围" : "继续整理"}
          </Button>
        </div>
        <ArchiveCleanupPreview plan={plan} />
        {(plan.runId || plan.state!=="READY") && <Card size="small" title="整理结果">
          <Space wrap><span>已处理 {plan.completedFiles || 0} 项</span><span>已删除 {bytes(plan.deletedBytes)}</span>
            <span>原文件已不存在 {plan.missingFiles || 0} 项</span><span>保护 / 跳过 {plan.skippedFiles || 0} 项</span></Space>
          {plan.error && <Hint type="error" label="整理错误" title={plan.error} />}
        </Card>}
        {activeJob && !state?.active && <Hint label="等待当前任务完成" title="当前任务仍有执行中的备份、合成或整理。完成或暂停后即可提交本次清理；提交前会再次验证预览是否有效。" />}
      </>}
      <Card title="最近整理计划" size="small">
        {historyError && <Hint type="warning" label="计划刷新失败" title={historyError} />}
        <DataTable<ArchivePlan> rowKey="id" size="small" dataSource={plans} tableLayout="fixed" scroll={{x:650}} columns={[
          {title:"清理此月之前",dataIndex:"beforeMonth",width:130},
          {title:"预览时间（北京时间）",dataIndex:"createdAt",width:210,render:archiveTime},
          {title:"状态",width:130,render:(_,row)=>archivePlanStatus(row,knownRuns).label},
          {title:"已删除 / 预计",render:(_,row)=>`${bytes(row.deletedBytes)} / ${bytes(row.estimatedBytes)}`},
          {title:"操作",width:90,render:(_,row)=><Button type="link" disabled={executing} onClick={()=>void selectPlan(row)}>查看计划</Button>},
        ]} />
      </Card>
    </div>
  </ResizableDrawer>;
}
