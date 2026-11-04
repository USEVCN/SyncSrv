import { DirectoryTreeDrawer, type DirectoryRole } from "./DirectoryTreeDrawer";
import { Button, Card, Empty, Space, Tag, Tooltip } from "antd";
import {
  ClockCircleOutlined,
  EditOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  AppstoreFilled,
  UnorderedListOutlined,
} from "@ant-design/icons";
import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { useStateData } from "../../app/State";
import { PageHeader, Status, useAction } from "../../shared/ui";
import { time } from "../../shared/format";
import { StorageRoleIcon } from "../../shared/StorageRole";
import type { Job } from "../../shared/types";
import { api } from "../../platform/http";
import { JobForm } from "./JobForm";
import { ArchiveCleanupDrawer } from "./ArchiveCleanupDrawer";
import { DirectoryStatsDrawer } from "./DirectoryStatsDrawer";

import { JobChangesDrawer } from "./JobChangesDrawer";
import { JobList, jobRunState } from "./JobList";

type JobView = "card" | "list";
const viewKey = "syncsrv.jobs.view";

export function JobsPage() {
  const { data, refresh } = useStateData();
  const [params, setParams] = useSearchParams();
  const [editing, setEditing] = useState<Partial<Job> | null>(
    params.has("new") ? {} : null,
  );
  const action = useAction();
  const [statsJob, setStatsJob] = useState<Job>();
  const [archiveJob, setArchiveJob] = useState<Job>();
  const [changesId, setChangesId] = useState<string>();
  const [tree, setTree] = useState<{ job: Job; role: DirectoryRole }>();
  const [view, setView] = useState<JobView>(() => {
    try { return localStorage.getItem(viewKey) === "list" ? "list" : "card"; }
    catch { return "card"; }
  });
  const navigate = useNavigate();
  function changeView(value: JobView) {
    setView(value);
    try { localStorage.setItem(viewKey, value); } catch { /* The view still works when storage is unavailable. */ }
  }
  function runJob(job: Job) {
    void action.run(async () => {
      await api(`/jobs/${job.id}/run`, "POST");
      await refresh();
    }, "任务已加入执行队列");
  }
  if (!data) return null;
  return (
    <>
      <PageHeader
        title="备份任务"
        description="源目录提供原始内容，备份库保存最新文件，归档库保留替换与删除的版本。"
        eyebrow="BACKUP JOBS / 备份任务"
        extra={
          <Space size={8} wrap>
          <div className="job-view-toggle" role="group" aria-label="任务显示方式">
            <Tooltip title="卡片视图"><button type="button" aria-label="卡片视图" aria-pressed={view === "card"}
              onClick={() => changeView("card")}><AppstoreFilled /></button></Tooltip>
            <Tooltip title="列表视图"><button type="button" aria-label="列表视图" aria-pressed={view === "list"}
              onClick={() => changeView("list")}><UnorderedListOutlined /></button></Tooltip>
          </div>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setEditing({})}
          >
            创建备份任务
          </Button>
          </Space>
        }
      />
      {!data.jobs.length ? (
        <Card className="large-empty">
          <Empty description="创建你的第一个备份任务" />
          <p className="muted">
            选择待备份目录和独立的备份存储，设置备份频率与传输速度。
          </p>
          <Space>
            <Button onClick={() => navigate("/storages?new=1")}>
              添加存储连接
            </Button>
            <Button type="primary" onClick={() => setEditing({})}>
              创建任务
            </Button>
          </Space>
        </Card>
      ) : view === "list" ? (
        <JobList data={data} loading={action.loading} onEdit={setEditing}
          onTree={(job, role) => setTree({ job, role })} onChanges={(job) => setChangesId(job.id)}
          onStats={setStatsJob} onRun={runJob} onArchive={setArchiveJob} />
      ) : (
        <div className="job-grid">
          {data.jobs.map((j) => {
            const { latest, busy } = jobRunState(j.id, data.runs);
            return (
              <Card className="job-card" key={j.id}>
                <div className="section-heading">
                  <div>
                    <Tag
                      bordered={false}
                      color={j.enabled ? "success" : "default"}
                    >
                      {j.enabled
                        ? j.discoveryMode === "EVENTS"
                          ? "源端触发已开启"
                          : "定时备份已开启"
                        : "自动处理已暂停"}
                    </Tag>
                    <h2>{j.name}</h2>
                  </div>
                  <Button
                    type="text"
                    icon={<EditOutlined />}
                    aria-label={`编辑 ${j.name}`}
                    onClick={() => setEditing(j)}
                  />
                </div>
                <div className="job-paths">
                  {[
                    ["A", "源目录", j.sourceId],
                    ["B", "备份库", j.targetId],
                    ["C", "归档库", j.archiveId],
                  ].map(([role, title, id]) => {
                    const connectionName = role === "A" && j.sourceMode === "AGENT"
                      ? "源端直传" : data.endpoints.find((e) => e.id === id)?.name;
                    return (
                    <div key={role}>
                      <span className={`role-badge role-${role}`}><StorageRoleIcon role={role as DirectoryRole} /></span>
                      <div>
                        <strong>
                          {title}{connectionName && connectionName !== title ? ` · ${connectionName}` : ""}
                        </strong>
                        <small className="mono">
                          {role === "A" && j.sourceMode === "AGENT" ? j.agentRoot : data.endpoints.find((e) => e.id === id)?.root}
                        </small>
                      </div>
                      <Button size="small" type="link" aria-label={`查看 ${j.name} 的${title}目录树`}
                        onClick={() => setTree({job: j, role: role as DirectoryRole})}>目录树</Button>
                    </div>
                    );
                  })}
                </div>
                <div className="job-meta">
                  <Button type="link" aria-label={`查看 ${j.name} 的变化任务`} onClick={() => setChangesId(j.id)}>
                    变化任务
                  </Button>
                  <Button type="link" onClick={() => setStatsJob(j)}>
                    目录统计
                  </Button>
                  <Button type="link" onClick={() => setArchiveJob(j)}>整理归档</Button>
                  <span>
                    <ClockCircleOutlined />{" "}
                    {j.sourceMode === "AGENT" ? "连续保存合并等待" : j.discoveryMode === "EVENTS" ? "处理变化间隔" : "扫描间隔"} · {j.intervalSeconds} 秒
                  </span>
                  <span>
                    {j.bandwidthMiB ? `${j.bandwidthMiB} MiB/s` : "不限速"}
                  </span>
                  <span>完整内容校验</span>
                  {j.sourceMode === "AGENT" && <span>源端并发 {j.transferThreads ?? 4} 文件</span>}
                </div>
                <div className="job-bottom">
                  <div>
                    {latest ? (
                      <>
                        <Status value={latest.status} />
                        <small className="muted">
                          {time(latest.startedAt)}
                        </small>
                      </>
                    ) : (
                      <span className="muted">尚未执行</span>
                    )}
                  </div>
                  <Button
                    type="primary"
                    ghost
                    icon={<PlayCircleOutlined />}
                    disabled={busy}
                    loading={action.loading}
                    onClick={() => runJob(j)}
                  >
                    {busy ? "执行中" : "立即执行"}
                  </Button>
                </div>
              </Card>
            );
          })}
        </div>
      )}
      {archiveJob && <ArchiveCleanupDrawer key={archiveJob.id} job={archiveJob} runs={data.runs} onClose={() => setArchiveJob(undefined)} onQueued={refresh} />}
      <JobChangesDrawer job={data.jobs.find(j => j.id === changesId)} onClose={() => setChangesId(undefined)} />
      <DirectoryTreeDrawer value={tree} onClose={() => setTree(undefined)} />
      <DirectoryStatsDrawer
        job={statsJob}
        onClose={() => setStatsJob(undefined)}
      />
      <JobForm
        value={editing}
        endpoints={data.endpoints}
        onClose={() => {
          setEditing(null);
          setParams({});
        }}
        onSaved={refresh}
      />
    </>
  );
}
