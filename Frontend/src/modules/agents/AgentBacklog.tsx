import { CheckCircleOutlined, ClockCircleOutlined, WarningOutlined } from "@ant-design/icons";
import { Button, Descriptions, Space, Tag, Tooltip } from "antd";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import type { Agent, AgentBacklogRow, AgentBacklogView, Job } from "../../shared/types";
import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { DataTable } from "../../shared/ui";
import { Hint } from "../../shared/Hint";
import { time } from "../../shared/format";
import { JobChangesDrawer } from "../jobs/JobChangesDrawer";
import { sourcePhases } from "../jobs/activity";

export function backlogState(agent: Agent, stale = false, now = Date.now()) {
  const count = Math.max(agent.pendingEvents || 0, agent.backlog?.knownPaths || 0);
  const current = !stale && agent.status === "APPROVED" && !!agent.lastSeen && now - Date.parse(agent.lastSeen) < 150000;
  if (!current) return { color: "default", count, label: `${count || "—"} · 待确认`, description: "设备离线、未授权或状态刷新失败，积压数据可能过期；点击查看已登记明细。" };
  if (agent.backlog?.overdue) return { color: "gold", count, label: `${count} · 超时未处理`, description: `有 ${agent.backlog.overdue} 条变化在合并等待结束后超过 5 分钟仍未开始处理，点击查看。` };
  if (count > 0) return { color: "error", count, label: `${count} · 待处理`, description: "源端仍有变化等待处理或正在上传，点击查看路径、阶段及失败原因。" };
  return { color: "success", count, label: "0 · 健康", description: "当前无源端积压；服务端归档与提交进度可在备份任务中查看。" };
}
export function AgentBacklogTag({ agent, stale, onClick }: { agent: Agent; stale?: boolean; onClick: () => void }) {
  const state = backlogState(agent, stale);
  return <Tooltip title={state.description}>
    <button type="button" aria-label={`${agent.name}的源端积压：${state.label}，查看待执行任务`} onClick={onClick}
      style={{ background: "transparent", border: 0, padding: "4px 0", cursor: "pointer", font: "inherit" }}>
      <Tag style={{ margin: 0 }} color={state.color} icon={state.color === "success" ? <CheckCircleOutlined /> : state.color === "gold" ? <ClockCircleOutlined /> : <WarningOutlined />}>
        {state.label}
      </Tag>
    </button>
  </Tooltip>;
}
export function AgentBacklogDrawer({ agent, jobs, onClose }: { agent?: Agent; jobs: Job[]; onClose: () => void }) {
  return <ResizableDrawer storageKey="agent-backlog" title={agent ? `${agent.name} / 源端待执行任务` : "源端待执行任务"}
    open={!!agent} onClose={onClose} initialWidth={1120} destroyOnHidden>
    {agent && <BacklogDetails key={agent.id} agent={agent} jobs={jobs} />}
  </ResizableDrawer>;
}
export function BacklogDetails({ agent, jobs }: { agent: Agent; jobs: Job[] }) {
  const [page, setPage] = useState(1);
  const [view, setView] = useState<AgentBacklogView>();
  const [error, setError] = useState("");
  const [selectedJob, setSelectedJob] = useState<string>();
  useEffect(() => {
    let active = true;
    setView(undefined);
    setError("");
    const refresh = async () => {
      try {
        const result = await api<AgentBacklogView>(`/agents/${encodeURIComponent(agent.id)}/backlog?page=${page}`);
        if (!active) return;
        setView(result);
        setError("");
        if (page > 1 && result.total <= (page - 1) * result.pageSize) setPage(Math.max(1, Math.ceil(result.total / result.pageSize)));
      } catch (e) {
        if (active) setError(`积压明细刷新失败：${(e as Error).message}。已显示的数据可能过期。`);
      }
    };
    const stop = pollWhileVisible(refresh, 3000);
    return () => { active = false; stop(); };
  }, [agent.id, page]);
  const rowState = (row: AgentBacklogRow) => row.change.phase === "AUTHORIZATION_REQUIRED" ? "等待重新授权" : !row.enabled ? "任务已暂停" : row.overdue ? "超时未处理" : row.change.status === "RUNNING" ? "执行中" : row.change.status === "FAILED" ? "失败待重试" : "待执行";
  const waiting = (row: AgentBacklogRow) => {
    const minutes = Math.max(0, Math.floor((Date.now() - Date.parse(row.change.receivedAt)) / 60000));
    return minutes ? `${minutes} 分钟` : "不足 1 分钟";
  };
  return <section aria-label={`${agent.name}的源端待执行任务`}>
    <div className="section-heading space-bottom">
      <Space wrap>
        <AgentBacklogTag agent={{ ...agent, backlog: view?.summary ?? agent.backlog }} stale={!!error} onClick={() => setPage(1)} />
        <Hint label="积压与超时规则" title="源端待处理变化" description="合并等待结束后，超过 5 分钟仍未开始处理显示黄色。正在上传或主动暂停的任务不计入该超时。心跳数量与按路径合并的明细可能短暂不同；源端尚未上报的路径会在收到通知后展示。" />
      </Space>
      <span className="muted">每 3 秒更新</span>
    </div>
    {error && <Hint className="space-bottom" type="error" title={error} label="积压明细刷新失败" />}
    <Descriptions size="small" column={3} className="space-bottom" items={[
      { key: "reported", label: "最近心跳积压", children: agent.pendingEvents },
      { key: "known", label: "已登记待处理路径", children: view?.total ?? "—" },
      { key: "overdue", label: "超时未处理", children: view?.summary.overdue ?? "—" },
      { key: "waiting", label: "等待执行 / 重试", children: view?.summary.waiting ?? "—" },
      { key: "running", label: "源端执行中", children: view?.summary.running ?? "—" },
      { key: "heartbeat", label: "最近心跳", children: time(agent.lastSeen) },
    ]} />
    {view && agent.pendingEvents > view.total && <div className="muted space-bottom">心跳仍报告 {agent.pendingEvents} 条积压，已收到 {view.total} 条路径明细；其余路径尚未上报或正在等待心跳刷新。</div>}
    <DataTable<AgentBacklogRow> tableLayout="fixed" scroll={{ x: 1000 }} rowKey={row => row.change.id} loading={!view && !error} dataSource={view?.entries ?? []}
      pagination={{ current: page, pageSize: 50, total: view?.total ?? 0, showSizeChanger: false, onChange: setPage }}
      locale={{ emptyText: error ? "积压明细暂不可用" : agent.pendingEvents > 0 ? "源端有积压，等待路径明细上报" : "暂无源端待执行任务" }}
      columns={[
        { title: "备份任务", width: 180, render: (_, row) => <Button type="link" style={{ padding: 0 }} disabled={!jobs.some(j => j.id === row.change.jobId)} onClick={() => setSelectedJob(row.change.jobId)}>{row.jobName}</Button> },
        { title: "待处理路径", width: 300, render: (_, row) => <span className="mono wrap">{row.change.path || "整个任务根目录"}</span> },
        { title: "状态", width: 140, render: (_, row) => <Tag color={row.change.phase === "AUTHORIZATION_REQUIRED" ? "gold" : !row.enabled ? "default" : row.overdue ? "gold" : row.change.status === "RUNNING" ? "processing" : "error"}>{rowState(row)}</Tag> },
        { title: "当前阶段", width: 200, render: (_, row) => sourcePhases[row.change.phase] || (row.change.phase === "DEBOUNCING" ? "等待连续保存合并" : row.change.phase) },
        { title: "已等待", width: 110, render: (_, row) => row.change.status === "RUNNING" ? "已开始处理" : waiting(row) },
      ]}
      expandable={{ expandedRowRender: row => <Descriptions size="small" column={2} items={[
        { key: "noticed", label: "源端发现", children: time(row.change.observedAt) },
        { key: "received", label: "服务端收到", children: time(row.change.receivedAt) },
        { key: "due", label: "合并等待结束", children: time(row.eligibleAt) },
        { key: "start", label: "源端开始", children: time(row.change.startedAt) },
        { key: "path", label: "正在处理", children: row.change.currentPath || "—", span: 2 },
        { key: "error", label: "失败原因", children: row.change.error || "无已报告错误", span: 2 },
      ]} /> }} />
    <JobChangesDrawer job={jobs.find(j => j.id === selectedJob)} onClose={() => setSelectedJob(undefined)} />
  </section>;
}
