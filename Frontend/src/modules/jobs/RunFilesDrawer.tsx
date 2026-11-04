import { DataTable } from "../../shared/ui";
import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Alert, Button, Progress, Space, Spin, Tag } from "antd";
import { useEffect, useState } from "react";
import { api, ApiError } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import { Hint } from "../../shared/Hint";
import { storageText } from "../../shared/storageText";
import { bytes, phaseLabels, statusLabels } from "../../shared/format";
import type { RunFile, RunFiles } from "../../shared/types";
import { ExecutionProgress } from "./ExecutionProgress";
import { sourcePhases } from "./activity";
import { activeRun } from "./useLiveRuns";
export function RunFilesDrawer({runId,source=false,onClose}:{runId:string;source?:boolean;onClose:()=>void}) {
  const [data,setData]=useState<RunFiles>();
  const [page,setPage]=useState(1);
  const [error,setError]=useState("");
  const [revision,setRevision]=useState(0);
  useEffect(()=>{
    let closed=false;
    setData(undefined);setError("");
    const load=async()=>{
      let repeat=true;
      try{
        if(document.hidden)return;
        const next=await api<RunFiles>(`/${source ? "source-transfers" : "runs"}/${encodeURIComponent(runId)}/files?page=${page}`);
        if(!closed){setData(next);setError("");repeat=activeRun(next.run);
          if(page>1 && next.total<=(page-1)*next.pageSize)setPage(Math.max(1,Math.ceil(next.total/next.pageSize)));
        }
      }catch(e){if(!closed)setError(e instanceof ApiError && e.status===404
        ? "当前服务端尚未提供文件清单接口，请在备份结束后重启后端。"
        : `文件进度刷新失败：${(e as Error).message}。当前显示上次采样。`);}
      return repeat;
    };
    const stop=pollWhileVisible(load,1000);return()=>{closed=true;stop();};
  },[runId,source,page,revision]);
  const fileStatus=(f:RunFile)=>data && !activeRun(data.run)
    ? f.status==="RUNNING" ? data.run.status==="COMPLETED" ? "UNCONFIRMED" : data.run.status : f.status==="QUEUED" ? "NOT_RUN" : f.status : f.status;
  return <ResizableDrawer storageKey="run-files" title="文件执行列表" open initialWidth={1100} onClose={onClose}
    extra={<Button onClick={()=>setRevision(n=>n+1)}>刷新</Button>}>
    {error && <Alert type="warning" showIcon title={error} />}
    {!data ? !error && <Spin /> : <>
      <div className="run-files-summary"><ExecutionProgress run={data.run} stale={!!error} /></div>
      <div className="run-files-list-heading"><h3>文件明细</h3><span>{data.total} 项</span><Hint label="统计说明" title="按本次文件处理清单显示；数据量按逻辑大小计算，重复校验不重复累计。预计总时长会随传输、校验和归档进度调整。" /></div>
      {data.coverage==="LEGACY" ? <Hint label="历史执行未采集完整清单" title="此执行发生在文件进度功能启用前，无法补算完整文件列表和总数据量。" /> : <>
        {!data.run.progress?.planComplete && <p role="status">正在整理处理清单，当前显示已发现的文件。</p>}
        <DataTable<RunFile> rowKey="id" size="small" dataSource={data.entries} scroll={{x:850}}
          pagination={{current:page,pageSize:data.pageSize,total:data.total,onChange:setPage,showSizeChanger:false,showTotal:n=>`共 ${n} 个文件 / 目录操作`}}
          columns={[
            {title:"文件 / 路径",dataIndex:"path",render:p=><span className="mono wrap">{p}</span>},
            {title:"操作",dataIndex:"kind",width:80,render:k=>(({WRITE:"备份",DELETE:"归档删除",MKDIR:"建目录",RMDIR:"删目录",RESTORE:"合成恢复",ARCHIVE_CLEANUP:"清理归档"} as Record<string,string>)[k] || k)},
            {title:"状态",width:90,render:(_,f)=><Tag color={fileStatus(f)==="COMPLETED" ? "success" : fileStatus(f)==="FAILED" ? "error" : fileStatus(f)==="UNCONFIRMED" ? "warning" : fileStatus(f)==="RUNNING" ? "processing" : undefined}>{statusLabels[fileStatus(f)] || {SKIPPED:"已跳过",NOT_RUN:"未执行",UNCONFIRMED:"待确认"}[fileStatus(f)] || fileStatus(f)}</Tag>},
            {title:"进度",width:230,render:(_,f)=><Space orientation="vertical" size={0} className="full-width">
              <span>{bytes(f.processedBytes)} / {bytes(f.size)}</span>
              <Progress percent={f.status==="COMPLETED" ? 100 : f.size ? Math.min(99,Math.floor(f.processedBytes/f.size*100)) : 0} size="small" />
              <small className="muted">{fileStatus(f)==="UNCONFIRMED" ? "未收到该文件的最终状态" : fileStatus(f)==="NOT_RUN" ? "本批次未执行此项" : f.status==="COMPLETED" ? (source ? "文件已传输并校验" : "文件处理完成") : phaseLabels[f.phase] || sourcePhases[f.phase] || statusLabels[f.phase] || "等待执行"}{f.phaseTotal>0 ? ` · 校验 ${bytes(f.phaseBytes)} / ${bytes(f.phaseTotal)}` : ""}</small>
            </Space>},
            {title:"说明",dataIndex:"error",render:e=><span className="wrap">{storageText(e || "") || "—"}</span>},
          ]} />
      </>}
    </>}
  </ResizableDrawer>;
}
