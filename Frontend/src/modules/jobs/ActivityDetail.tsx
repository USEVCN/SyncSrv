import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Hint } from "../../shared/Hint";
import { storageText } from "../../shared/storageText";
import { Button, Descriptions, Tooltip } from "antd";
import { api } from "../../platform/http";
import { bytes, executionPhase, time } from "../../shared/format";
import { DataTable, Mono, Status, useAction } from "../../shared/ui";
import type { AppState, Run } from "../../shared/types";
import type { SourceChange } from "../../shared/types";
import { sourcePhases, type Activity } from "./activity";

export function ActivityDetail({row,data,onClose,refresh,onArchiveCleanup}:{row?:Activity;data:AppState;onClose:()=>void;refresh:()=>Promise<void>;onArchiveCleanup?:(run:Run)=>void}) {
  const action=useAction();
  const run=row?.run;
  const errors=[...new Set([run?.error,...(row?.changes || []).map(c=>c.error)].filter((error):error is string=>!!error))];
  const name=data.jobs.find(j=>j.id===row?.jobId)?.name || row?.jobId;
  return <ResizableDrawer storageKey="execution" title="执行详情" open={!!row} onClose={onClose} initialWidth={760} rootClassName="activity-detail-drawer">
    {row && <div className="activity-detail-content">
      <Descriptions bordered column={1} items={[
        {key:"task",label:"任务",children:name},
        {key:"id",label:"执行编号",children:run ? <Mono>{run.id}</Mono> : "变化已登记，等待服务端建立执行批次"},
        {key:"status",label:"状态",children:<Status value={row.status} />},
        {key:"phase",label:"阶段",children:(run ? executionPhase(run) : sourcePhases[row.phase]) || row.phase || "等待执行"},
        {key:"queued",label:"登记时间",children:time(row.at)},
        {key:"path",label:"当前路径",children:<span className="mono wrap">{run?.currentPath || row.changes.find(c=>c.currentPath)?.currentPath || "—"}</span>},
        ...(run ? [
          ...(run.sourceTransfer ? [
            {key:"sourceConcurrency",label:"文件并发",children:`${run.sourceTransfer.automatic ? "自动" : "手动"} · 目标 ${run.sourceTransfer.concurrency} / 上限 ${run.sourceTransfer.maxConcurrency}`},
            {key:"sourceReason",label:"并发调整原因",children:run.sourceTransfer.reason || "—"},
            {key:"sourceTiming",label:"源端阶段耗时",children:`初始化 ${(run.sourceTransfer.initializationMillis/1000).toFixed(2)} 秒 · 传输与校验 ${(run.sourceTransfer.transferMillis/1000).toFixed(2)} 秒 · 中心收尾 ${(run.sourceTransfer.finalizationMillis/1000).toFixed(2)} 秒`},
            {key:"sourceRate",label:"平均文件完成速度",children:run.sourceTransfer.transferMillis>0 ? `${((run.progress?.processedFiles || 0)*1000/run.sourceTransfer.transferMillis).toFixed(1)} 个/秒` : "—"},
          ] : []),
          {key:"bytes",label:"本次传输读取量",children:bytes(run.bytes)},
          {key:"verified",label:"本次校验读取量",children:bytes(run.verifiedBytes)},
          ...((run.phaseTotal || 0) > 0 ? [{key:"phaseBytes",label:"当前文件校验进度",children:`${bytes(run.phaseBytes)} / ${bytes(run.phaseTotal)}`} ] : []),
          {key:"count",label:"已完成操作",children:`${run.done} / ${run.total}`},
          {key:"start",label:"服务端执行开始",children:time(run.actualStartedAt || "")},
          {key:"end",label:"结束时间",children:time(run.completedAt)},
          {key:"actor",label:"发起者",children:run.actor},
          {key:"range",label:"处理范围",children:run.kind==="ARCHIVE_CLEANUP" ? "已确认的归档整理计划" : run.discoveryMode==="RECOVERY" ? "恢复上次未完成操作" : run.discoveryMode==="EVENTS" ? "变化路径" : "完整校准 / 扫描"},
          ...(run.kind==="RESTORE" ? [{key:"target",label:"恢复目录",children:run.restoreEndpointId ? `${data.endpoints.find(e=>e.id===run.restoreEndpointId)?.name || run.restoreEndpointId} / ${run.target}` : run.target}] : []),
        ] : []),
      ]} />
      {!!row.changes.length && <section className="activity-detail-changes" aria-label="关联文件变化">
        <div className="activity-detail-section-heading">
          <h3>关联文件变化 · {row.changes.length} 条</h3>
          <Hint iconOnly label="关联路径说明" title="这里显示仍保留在变化索引中的关联路径；完整历史与各代次状态转换可在审计中心查询。" />
        </div>
        <DataTable<SourceChange> key={row.id} rowKey="id" size="small" dataSource={row.changes}
          tableLayout="fixed" scroll={{x:"100%"}} columns={[
          {title:"路径",width:"44%",render:(_,c)=><Tooltip title={<span className="mono wrap">{c.path || "整个监听目录"}</span>}
            styles={{root:{maxWidth:"min(600px, calc(100vw - 32px))"}}}>
            <span className="activity-change-path mono">{c.path || "整个监听目录"}</span>
          </Tooltip>},
          {title:"阶段",width:"20%",render:(_,c)=>sourcePhases[c.phase] || c.phase},
          {title:"源端发现",width:"18%",render:(_,c)=><span className="activity-change-time">{time(c.observedAt)}</span>},
          {title:"服务端收到",width:"18%",render:(_,c)=><span className="activity-change-time">{time(c.receivedAt)}</span>},
        ]} />
      </section>}
      {(errors.length > 0 || (run && ["FAILED","PAUSED","INTERRUPTED"].includes(run.status))) && <div className="activity-detail-actions">
        {errors.length > 0 && <Hint type="error" label={errors.length > 1 ? `执行错误 · ${errors.length} 项` : "执行错误"}
          title="执行异常信息" description={<ul className="activity-detail-errors">{errors.map(error=><li key={error}>{storageText(error)}</li>)}</ul>} />}
      {run?.kind==="ARCHIVE_CLEANUP" && onArchiveCleanup && <Button onClick={()=>onArchiveCleanup(run)}>查看整理计划 / 继续整理</Button>}
      {run && run.kind!=="ARCHIVE_CLEANUP" && ["FAILED","PAUSED","INTERRUPTED"].includes(run.status) && <Button type="primary" loading={action.loading} onClick={()=>action.run(async()=>{
        if(run.kind==="SYNC") await api(`/jobs/${run.jobId}/run`,"POST");
        else if(run.kind==="RESTORE") await api("/restores","POST",{snapshotId:run.snapshotId,directory:run.target,endpointId:run.restoreEndpointId});
        await refresh();
      },"已重新排队，执行时会验证可复用的断点")}>重新执行 / 续传</Button>}
      </div>}
    </div>}
  </ResizableDrawer>;
}
