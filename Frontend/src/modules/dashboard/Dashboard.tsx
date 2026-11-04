import { Button, Card, Col, Empty, Progress, Row, Tag } from "antd";
import {
  ArrowRightOutlined,
  CloudUploadOutlined,
  DatabaseOutlined,
  HistoryOutlined,
  SafetyCertificateOutlined,
  PlusOutlined,
  PlayCircleOutlined,
} from "@ant-design/icons";
import { useNavigate } from "react-router-dom";
import { useStateData } from "../../app/State";
import { PageHeader, Status, useAction } from "../../shared/ui";
import { bytes, executionPhase, runKindLabel, time } from "../../shared/format";
import { runProgress } from "../../shared/runProgress";
import { api } from "../../platform/http";
import { StorageRoleIcon, storageRoleNames, type StorageRole } from "../../shared/StorageRole";

export function Dashboard() {
  const { data, refresh } = useStateData();
  const navigate = useNavigate();
  const action = useAction();
  if (!data) return null;
  const active = data.runs.filter((r) =>
    ["RUNNING", "QUEUED", "PAUSING"].includes(r.status),
  );
  const latest = data.snapshots[0];
  const failures = data.runs.filter((r) => r.status === "FAILED").length;
  const metrics = [
    {
      label: "备份任务",
      value: data.jobs.length,
      suffix: "个",
      note: `${data.jobs.filter((j) => j.enabled).length} 个已开启自动备份`,
      icon: <CloudUploadOutlined />,
      color: "violet",
    },
    {
      label: "存储连接",
      value: data.endpoints.length,
      suffix: "个",
      note: "管理源目录与各类备份连接",
      icon: <DatabaseOutlined />,
      color: "cyan",
    },
    {
      label: "完整恢复节点",
      value: data.totals?.["snapshot-summary"] ?? data.snapshots.length,
      suffix: "个",
      note: latest
        ? `最近节点 ${bytes(latest.totalBytes)}`
        : "每次成功备份生成完整清单",
      icon: <HistoryOutlined />,
      color: "blue",
    },
    {
      label: "审计事件",
      value: data.audit.sequence,
      suffix: "条",
      note: failures
        ? `${failures} 条执行失败记录待检查`
        : "每一步操作均可追溯",
      icon: <SafetyCertificateOutlined />,
      color: "green",
    },
  ];
  return (
    <>
      <PageHeader
        title="服务总览"
        description="管理源目录、备份库、归档库与恢复。"
        eyebrow="OVERVIEW / 总览"
        extra={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate("/jobs?new=1")}
          >
            创建备份任务
          </Button>
        }
      />
      <Row gutter={[16, 16]}>
        {metrics.map((m) => (
          <Col xs={24} sm={12} xl={6} key={m.label}>
            <Card className={`metric-card ${m.color}`}>
              <div className="metric-label">
                {m.label}
                <span className="metric-icon">{m.icon}</span>
              </div>
              <div className="metric-value">
                {m.value.toLocaleString()}
                <small>{m.suffix}</small>
              </div>
              <div className="muted">{m.note}</div>
            </Card>
          </Col>
        ))}
      </Row>
      <Card className="flow-panel space-top">
        <div className="section-heading">
          <div>
            <div className="eyebrow">BACKUP. ARCHIVE. RESTORE.</div>
            <h2>文件备份与恢复流程</h2>
          </div>
          <Tag bordered={false} color="purple">
            备份 · 归档 · 恢复
          </Tag>
        </div>
        <div className="pipeline">
          {[
            ["A", "读取原始文件", "cyan", "/storages"],
            ["B", "保存最新副本", "violet", "/jobs"],
            ["C", "保留历史版本", "blue", "/snapshots"],
            ["D", "按节点还原文件", "green", "/snapshots"],
          ].map(([role, detail, color, route], i) => (
            <div className="pipeline-group" key={role}>
              <button
                className={`pipeline-node ${color}`}
                onClick={() => navigate(route)}
              >
                <span className="node-letter"><StorageRoleIcon role={role as StorageRole} /></span>
                <strong>{storageRoleNames[role as StorageRole]}</strong>
                <small>{detail}</small>
              </button>
              {i < 3 && <ArrowRightOutlined className="pipeline-arrow" />}
            </div>
          ))}
        </div>
        <div className="flow-foot">
          <SafetyCertificateOutlined />
          <span>恢复库从备份库和归档库提取文件，以已完成的恢复节点为依据。</span>
          <Button type="link" onClick={() => navigate("/audit")}>
            查看审计证据 <ArrowRightOutlined />
          </Button>
        </div>
      </Card>
      <Row gutter={[16, 16]} className="space-top">
        <Col xs={24} xl={15}>
          <Card
            title="最近执行"
            extra={
              <Button type="link" onClick={() => navigate("/runs")}>
                全部记录 <ArrowRightOutlined />
              </Button>
            }
          >
            {!data.runs.length ? (
              <div className="dashboard-empty">
                <Empty
                  description="还没有执行记录"
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                />
                <p>连接存储后，创建第一个备份任务。</p>
                <Button
                  onClick={() => navigate("/storages?new=1")}
                  icon={<PlusOutlined />}
                >
                  添加存储连接
                </Button>
              </div>
            ) : (
              <div className="recent-runs">
                {data.runs.slice(0, 4).map((r) => (
                  <div className="recent-run" key={r.id}>
                    <span
                      className={`run-orb ${r.status === "FAILED" ? "failed" : ""}`}
                    >
                      <CloudUploadOutlined />
                    </span>
                    <div>
                      <strong>
                        {data.jobs.find((j) => j.id === r.jobId)?.name ||
                          "备份任务"}
                      </strong>
                      <p>
                        {runKindLabel[r.kind] || r.kind} ·{" "}
                        {time(r.startedAt)}
                      </p>
                      {r.status === "RUNNING" && (
                        <Progress
                          percent={
                            runProgress(r)
                          }
                          size="small"
                          format={() => executionPhase(r) || "准备中"}
                        />
                      )}
                    </div>
                    <Status value={r.status} />
                  </div>
                ))}
              </div>
            )}
          </Card>
        </Col>
        <Col xs={24} xl={9}>
          <Card className="insight-card" title="服务状态">
            <div className="status-line">
              <span className="live-dot" />
              <span>
                {active.length
                  ? `${active.length} 个执行正在运行或排队`
                  : "当前没有执行中的任务"}
              </span>
            </div>
            <div className="detail-row">
              <span>全局限速</span>
              <b>
                {data.settings.bandwidthMiB
                  ? `${data.settings.bandwidthMiB} MiB/s`
                  : "不限速"}
              </b>
            </div>
            <div className="detail-row">
              <span>最近恢复节点</span>
              <b>
                {latest
                  ? `#${latest.sequence.toString().padStart(4, "0")}`
                  : "等待首次备份"}
              </b>
            </div>
            <div className="detail-row">
              <span>主服务端口</span>
              <b className="mono">{data.system.port}</b>
            </div>
            <div className="insight-bottom">
              {data.jobs[0] ? (
                <Button
                  block
                  icon={<PlayCircleOutlined />}
                  loading={action.loading}
                  onClick={() =>
                    action.run(async () => {
                      await api(`/jobs/${data.jobs[0].id}/run`, "POST");
                      await refresh();
                    }, "任务已加入队列")
                  }
                >
                  执行「{data.jobs[0].name}」
                </Button>
              ) : (
                <Button block onClick={() => navigate("/storages")}>
                  开始配置工作空间 <ArrowRightOutlined />
                </Button>
              )}
            </div>
          </Card>
        </Col>
      </Row>
    </>
  );
}
