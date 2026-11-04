import { Button, Card, Space, Tag, Tooltip } from "antd";
import { EditOutlined, PlayCircleOutlined } from "@ant-design/icons";
import { DataTable, Status } from "../../shared/ui";
import { time } from "../../shared/format";
import { StorageRoleIcon } from "../../shared/StorageRole";
import type { AppState, Job, Run } from "../../shared/types";
import type { DirectoryRole } from "./DirectoryTreeDrawer";

export function jobRunState(jobId: string, runs: Run[]) {
  return {
    latest: runs.find((r) => r.jobId === jobId && r.kind === "SYNC"),
    busy: runs.some((r) => r.jobId === jobId && ["RUNNING", "QUEUED", "PAUSING"].includes(r.status)),
  };
}

export function JobList({ data, loading, onEdit, onTree, onChanges, onStats, onRun, onArchive }: {
  data: Pick<AppState, "jobs" | "endpoints" | "runs">;
  loading: boolean;
  onEdit: (job: Job) => void;
  onTree: (job: Job, role: DirectoryRole) => void;
  onChanges: (job: Job) => void;
  onStats: (job: Job) => void;
  onRun: (job: Job) => void;
  onArchive?: (job: Job) => void;
}) {
  const rows = data.jobs.map((job) => ({ ...job, ...jobRunState(job.id, data.runs) }));
  return <Card className="list-table-card table-card job-list">
    <DataTable<(typeof rows)[number]>
      rowKey="id"
      tableLayout="fixed"
      dataSource={rows}
      scroll={{ x: 1120 }}
      pagination={{ defaultPageSize: 20, showSizeChanger: true, pageSizeOptions: [10, 20, 50, 100], showTotal: (total) => `共 ${total} 个任务` }}
      columns={[
        { title: "任务", key: "name", width: 200, render: (_, job) => <div className="job-list-name">
          <strong>{job.name}</strong>
          <Tag bordered={false} color={job.enabled ? "success" : "default"}>
            {job.enabled ? job.discoveryMode === "EVENTS" ? "源端触发已开启" : "定时备份已开启" : "自动处理已暂停"}
          </Tag>
        </div> },
        { title: "备份路径", key: "paths", width: 360, render: (_, job) => <div className="job-list-paths">
          {([
            ["A", "源目录", job.sourceId],
            ["B", "备份库", job.targetId],
            ["C", "归档库", job.archiveId],
          ] as const).map(([role, title, id]) => {
            const endpoint = data.endpoints.find((e) => e.id === id);
            const source = role === "A" && job.sourceMode === "AGENT";
            const name = source ? "源端直传" : endpoint?.name || "连接不可用";
            const path = (source ? job.agentRoot : endpoint?.root) || "未配置目录";
            return <Tooltip key={role} title={<>{title}{name !== title ? ` · ${name}` : ""}<br />{path}</>}>
              <Button type="text" className="job-list-path" aria-label={`查看 ${job.name} 的${title}目录树`}
                onClick={() => onTree(job, role)}>
                <span className={`role-badge role-${role}`}><StorageRoleIcon role={role} /></span>
                <span className="job-list-path-role">{title}</span>
                <span className="mono job-list-path-text">{path}</span>
              </Button>
            </Tooltip>;
          })}
        </div> },
        { title: "备份策略", key: "schedule", width: 175, render: (_, job) => <div className="job-list-detail">
          <span>{job.sourceMode === "AGENT" ? "合并等待" : job.discoveryMode === "EVENTS" ? "处理变化间隔" : "扫描间隔"} · {job.intervalSeconds} 秒</span>
          <small className="muted">{job.bandwidthMiB ? `${job.bandwidthMiB} MiB/s` : "不限速"} · 完整内容校验</small>
          {job.sourceMode === "AGENT" && <small className="muted">源端并发 {job.transferThreads ?? 4} 文件</small>}
        </div> },
        { title: "最近执行", key: "latest", width: 180, render: (_, job) => job.latest
          ? <div className="job-list-detail"><span><Status value={job.latest.status} /></span><small className="muted">{time(job.latest.startedAt)}</small></div>
          : <span className="muted">尚未执行</span> },
        { title: "操作", key: "actions", width: 205, render: (_, job) => <div className="job-list-actions">
          <Space size={0} wrap>
            <Button size="small" type="text" icon={<EditOutlined />} aria-label={`编辑 ${job.name}`} onClick={() => onEdit(job)}>编辑</Button>
            <Button size="small" type="link" aria-label={`查看 ${job.name} 的变化任务`} onClick={() => onChanges(job)}>变化任务</Button>
            <Button size="small" type="link" aria-label={`查看 ${job.name} 的目录统计`} onClick={() => onStats(job)}>目录统计</Button>
            {onArchive && <Button size="small" type="link" aria-label={`整理 ${job.name} 的归档`} onClick={() => onArchive(job)}>整理归档</Button>}
          </Space>
          <Button size="small" type="primary" ghost icon={<PlayCircleOutlined />} disabled={job.busy} loading={loading}
            aria-label={`${job.busy ? "执行中" : "立即执行"} ${job.name}`} onClick={() => onRun(job)}>
            {job.busy ? "执行中" : "立即执行"}
          </Button>
        </div> },
      ]}
    />
  </Card>;
}
