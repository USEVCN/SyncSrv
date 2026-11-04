import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Descriptions, Input, Select, Space, Tag } from "antd";
import { useState } from "react";
import type { Job } from "../../shared/types";
import { DataTable } from "../../shared/ui";
import { Hint } from "../../shared/Hint";
import { time } from "../../shared/format";
import { changeColors, changeLabels, mergeJobChanges, type ChangeRow } from "./changeRows";
import { useJobChanges } from "./useJobChanges";

export function JobChangesDrawer({ job, onClose }: { job?: Job; onClose: () => void }) {
  return <ResizableDrawer storageKey="changes" title={job ? `${job.name} / 变化任务` : "变化任务"} open={!!job} onClose={onClose} initialWidth={1080} destroyOnHidden>
    {job && <JobChanges key={job.id} job={job} />}
  </ResizableDrawer>;
}
export function JobChanges({ job }: { job: Job }) {
  const data = useJobChanges(job);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("");
  const rows = mergeJobChanges(job, data.changes, data.discovery);
  const state = (row: ChangeRow) => <Tag color={changeColors[row.status]}>{changeLabels[row.status] || row.status}</Tag>;
  const errors = [data.sourceError, data.centerError].filter(Boolean);
  const checkpoint = data.discovery?.checkpoint;
  return <section aria-label={`${job.name}的变化任务`}>
    <div className="section-heading space-bottom">
      <Space wrap>
        <Hint label="变化任务说明" title="本备份任务的变化任务" description="变化先登记，由源目录推送到备份库，再由服务端归档与提交恢复节点。同一路径合并展示当前状态；尚未完成的服务端处理保留在该路径详情中，历史过程保留在审计中心。" />
        <Hint label="校准与合并说明" title="校准与合并" description="合并数表示通知合并，不能反推文件版本数。无已知待办不代表监听永无遗漏；独立校准用于发现监听空窗和静默变化。" />
      </Space>
      <span className="muted">每秒更新</span>
    </div>
    {errors.map(error => <Hint key={error} className="space-bottom" type="warning" label="状态更新异常" title={error} />)}
    <Descriptions size="small" column={2} className="space-bottom" items={[
      { key: "pending", label: "服务端待处理路径", children: data.discovery?.pendingCount ?? "—" },
      { key: "oldest", label: "最早等待", children: time(data.discovery?.oldestPendingAt || "") },
      { key: "scan", label: "最近完整校准", children: time(checkpoint?.lastFullScan || "") },
      { key: "entries", label: "已记录条目", children: checkpoint?.entries ?? "尚未建立基线" },
    ]} />
    <Space wrap className="space-bottom">
      {Object.entries(changeLabels).map(([key, label]) => <Tag key={key} color={changeColors[key]}>
        {label} {data.loaded ? rows.filter(r => r.status === key).length : "—"}
      </Tag>)}
      <Hint iconOnly label="状态计数说明" title="状态计数仅统计当前已加载的路径；接口不可用时保留上次状态。" />
    </Space>
    <div className="table-toolbar">
      <Input aria-label="搜索变化路径" placeholder="搜索本任务的变化路径" value={query} onChange={e => setQuery(e.target.value)} allowClear />
      <Select aria-label="变化任务状态" value={status} onChange={setStatus} options={[{ value: "", label: "全部状态" }, ...Object.entries(changeLabels).map(([value, label]) => ({ value, label }))]} />
    </div>
    <DataTable<ChangeRow> rowKey="id" size="small" loading={!data.loaded}
      locale={{ emptyText: errors.length ? "变化状态暂不完整，请悬停查看异常详情" : job.discoveryMode === "EVENTS" ? "本任务暂无已登记的变化" : "周期扫描任务的处理结果在执行记录中查看" }}
      dataSource={rows.filter(r => (!status || r.status === status) && r.path.toLowerCase().includes(query.toLowerCase()))}
      expandable={{ expandedRowRender: row => <Descriptions size="small" column={2} bordered items={[
        { key: "path", label: "变化路径", children: <span className="mono wrap">{row.path || "整个任务根目录"}</span>, span: 2 },
        { key: "source", label: "源端发现", children: time(row.source?.observedAt || "") },
        { key: "start", label: "源端开始", children: time(row.source?.startedAt || "") },
        { key: "pending", label: "服务端待处理", children: row.pending ? `等待处理 · ${row.pending.reason}` : "无已知待办" },
        { key: "first", label: "服务端首次等待", children: time(row.pending?.firstSeen || "") },
        { key: "centerMerge", label: "中心合并通知", children: row.pending?.merged ?? "—" },
        { key: "sourceMerge", label: "源端合并报告", children: row.source?.coalesced ?? row.pending?.sourceCoalesced ?? "—" },
        { key: "run", label: "关联执行编号", children: row.source?.runId || "尚未关联", span: 2 },
        { key: "current", label: "正在处理", children: row.source?.currentPath || "—", span: 2 },
        { key: "completed", label: "最近确认完成", children: time(row.source?.completedAt || ""), span: 2 },
        { key: "error", label: "异常详情", span: 2, children: [...new Set([row.source?.error, row.pending?.error].filter(Boolean))].map(e => <Hint key={e} type="error" title={e} label="查看处理错误" />) },
      ]} /> }} columns={[
        { title: "变化路径", render: (_, row) => <span className="mono wrap">{row.path || "整个任务根目录"}</span> },
        { title: "状态", render: (_, row) => state(row) },
        { title: "当前阶段", dataIndex: "phase" },
        { title: "服务端收到", render: (_, row) => time(row.receivedAt) },
      ]} />
    {(data.total > 500 || (data.discovery?.pendingCount || 0) > (data.discovery?.pending.length || 0)) &&
      <Hint label="列表范围" title="列表按路径合并最近 500 条源端状态及最早 100 条服务端待办，状态计数以已加载路径为准。完整历史可在审计中心查询。" />}
  </section>;
}
