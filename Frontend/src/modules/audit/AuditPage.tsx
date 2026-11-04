import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Hint } from "../../shared/Hint";
import {
  Button,
  Card,
  Descriptions,
  Input,
  Space,
  Tag,
} from "antd";
import {
  DownloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  KeyOutlined,
} from "@ant-design/icons";
import { useEffect, useState } from "react";
import { useStateData } from "../../app/State";
import { api, download } from "../../platform/http";
import type { AuditEvent, AuditHead } from "../../shared/types";
import { DataTable, Mono, PageHeader, useAction } from "../../shared/ui";
import { short, time } from "../../shared/format";

const labels: Record<string, string> = {
  "auth.login": "管理员登录",
  "auth.login_failed": "登录失败",
  "endpoint.saved": "保存存储连接",
  "endpoint.probed": "连接读取测试",
  "job.saved": "保存备份任务",
  "run.progress_saved": "执行性能报告已保存",
  "staging.remove_intent": "准备清理无主暂存文件",
  "staging.removed": "无主暂存文件已清理",
  "staging.cleanup_completed": "暂存清理完成",
  "staging.cleanup_failed": "暂存清理失败",
  "run.queued": "执行已排队",
  "run.started": "开始执行",
  "run.completed": "执行完成",
  "run.failed": "执行失败",
  "run.disposition_saved": "保存失败处置",
  "run.paused": "执行暂停",
  "sync.plan": "建立备份计划",
  "operation.planned": "记录操作意图",
  "operation.staged": "临时文件已校验",
  "operation.committed": "文件操作已提交",
  "operation.superseded_source_changed": "源文件再次变化，放弃未提交的中间版本",
  "changes.received": "变化通知已持久接收",
  "changes.backed_up": "变化批次备份完成",
  "changes.retry_pending": "变化批次等待重试",
  "agent.registered": "备份设备已登记",
  "agent.approved": "备份设备已授权",
  "agent.revoked": "备份设备已撤销",
  "agent.heartbeat": "源端状态上报",
  "agent.version_changed": "源端程序版本变更",
  "agent.invitation_created": "创建源端配对凭据",
  "index.migrated": "当前文件索引迁移完成",
  "directories.indexed": "目录统计索引已建立",
  "directory.browsed": "浏览任务目录",
  "directory.browse_failed": "任务目录读取失败",
  "version.archived": "旧版本归档完成",
  "file.remove_intent": "记录备份库文件移除意图",
  "transfer.server_copy_requested": "下发存储端复制命令",
  "transfer.mode": "选择数据传输方式",
  "transfer.checkpoint": "保存传输断点",
  "transfer.resumed": "复用已验证断点",
  "transfer.verified": "传输内容验证通过",
  "snapshot.completed": "完整恢复节点提交",
  "archive.previewed": "生成归档整理预览",
  "archive.confirmed": "确认归档清理范围",
  "archive.snapshots_retired": "移除过期恢复节点",
  "archive.resumed": "继续归档整理计划",
  "archive.remove_intent": "记录归档文件删除意图",
  "archive.file_removed": "归档文件删除已确认",
  "archive.file_absent_after_intent": "确认归档文件已不存在",
  "archive.file_protected": "归档文件受保护，跳过清理",
  "archive.completed": "归档整理完成",
  "archive.failed": "归档整理失败",
  "archive.paused": "归档整理暂停",
  "restore.plan": "恢复选取及排除清单",
  "restore.file_verified": "恢复文件校验完成",
  "restore.completed": "恢复完成",
  "audit.verified": "本地审计链校验",
  "audit.exported": "导出审计证据",
  "settings.saved": "保存全局设置",
};
export function AuditPage() {
  const { data, refresh } = useStateData();
  const [events, setEvents] = useState<AuditEvent[]>([]);
  const [search, setSearch] = useState("");
  const [selected, setSelected] = useState<AuditEvent>();
  const [verified, setVerified] = useState<AuditHead>();
  const [error, setError] = useState("");
  const action = useAction();
  const [after, setAfter] = useState(-1);
  useEffect(() => {
    let alive = true;
    api<{ events: AuditEvent[] }>(`/audit?after=${after}&limit=100`)
      .then((v) => {
        if (alive) {
          setEvents(v.events.reverse());
          setError("");
        }
      })
      .catch((e) => {
        if (alive) setError(e.message);
      });
    return () => {
      alive = false;
    };
  }, [after, data?.audit.sequence]);
  if (!data) return null;
  const rows = events.filter((e) =>
    `${labels[e.action] || ""} ${e.action} ${e.actor} ${e.subject} ${e.detail}`
      .toLowerCase()
      .includes(search.toLowerCase()),
  );
  return (
    <>
      <PageHeader
        title="审计中心"
        description="从操作意图到校验结果，让每一次备份、恢复与归档整理都有可核验的证据。"
        eyebrow="AUDIT / 审计"
        extra={
          <Space wrap>
            <Button
              icon={<KeyOutlined />}
              onClick={() =>
                action.run(() =>
                  download("/audit/public-key", "audit-public.txt"),
                )
              }
            >
              下载公钥
            </Button>
            <Button
              icon={<DownloadOutlined />}
              onClick={() =>
                action.run(() =>
                  download("/audit/export", "syncsrv-audit.jsonl"),
                )
              }
            >
              导出证据
            </Button>
            <Button
              type="primary"
              icon={<SafetyCertificateOutlined />}
              loading={action.loading}
              onClick={() =>
                action.run(async () => {
                  setVerified(await api<AuditHead>("/audit/verify", "POST"));
                  await refresh();
                }, "本地审计链校验通过")
              }
            >
              校验完整性
            </Button>
          </Space>
        }
      />
      <div className="audit-banner">
        <span className="audit-shield">
          <SafetyCertificateOutlined />
        </span>
        <div>
          <h3>
            {verified
              ? `已验证 ${verified.sequence.toLocaleString()} 条审计事件`
              : "签名审计链已启用"}
          </h3>
          <p>连续序号 · 前序哈希 · SHA-256 · Ed25519 签名</p>
        </div>
        <div className="audit-chain">
          <small>当前链头 #{data.audit.sequence}</small>
          <span className="mono">{short(data.audit.head)}</span>
        </div>
      </div>
      <Hint
        className="space-top space-bottom"
        type="info"
        showIcon
        title="请将公钥与审计导出独立留存"
        description="本地签名可检测记录变更；拥有服务器与密钥权限的人仍可能整体回滚数据。对强审计场景，应将证据定期送到独立、不可改写的存储。"
      />
      {error && (
        <Hint type="error" showIcon title={error} className="space-bottom" />
      )}
      <Card className="list-table-card table-card">
        <div className="table-toolbar">
          <Input
            prefix={<SearchOutlined />}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="搜索本批次事件、编号、路径或操作者"
            allowClear
          />
          <Space>
            <Button
              disabled={
                !events.length ||
                Math.min(...events.map((e) => e.sequence)) <= 1
              }
              onClick={() =>
                setAfter(
                  Math.max(0, Math.min(...events.map((e) => e.sequence)) - 101),
                )
              }
            >
              较早 100 条
            </Button>
            <Button onClick={() => setAfter(-1)}>最新记录</Button>
          </Space>
        </div>
        <DataTable<AuditEvent>
          rowKey="sequence"
          dataSource={rows}
          columns={[
            {
              title: "序号",
              dataIndex: "sequence",
              render: (v) => <span className="mono muted">#{v}</span>,
            },
            {
              title: "审计事件",
              render: (_, e) => (
                <div className="table-name">
                  <div>
                    <strong>{labels[e.action] || e.action}</strong>
                    <small className="mono">{e.action}</small>
                  </div>
                </div>
              ),
            },
            { title: "发生时间", dataIndex: "time", render: time },
            {
              title: "操作者",
              dataIndex: "actor",
              render: (v) => <Tag bordered={false}>{v}</Tag>,
            },
            {
              title: "关联对象",
              dataIndex: "subject",
              render: (v) => <span className="mono">{short(v)}</span>,
            },
            {
              title: "证据",
              render: (_, e) => (
                <Button type="link" onClick={() => setSelected(e)}>
                  查看详情
                </Button>
              ),
            },
          ]}
        />
      </Card>
      <ResizableDrawer storageKey="audit"
        title={`审计事件 #${selected?.sequence || ""}`}
        open={!!selected}
        onClose={() => setSelected(undefined)}
        initialWidth={720}
      >
        {selected && (
          <>
            <Descriptions
              column={1}
              bordered
              items={[
                { key: "action", label: "事件", children: selected.action },
                { key: "actor", label: "操作者", children: selected.actor },
                { key: "time", label: "时间", children: time(selected.time) },
                {
                  key: "subject",
                  label: "关联对象",
                  children: <Mono>{selected.subject}</Mono>,
                },
                {
                  key: "hash",
                  label: "事件 SHA-256",
                  children: <Mono>{selected.hash}</Mono>,
                },
                {
                  key: "previous",
                  label: "前序 SHA-256",
                  children: <Mono>{selected.previous || "创世记录"}</Mono>,
                },
              ]}
            />
            <h3 className="space-top">操作证据</h3>
            <pre className="json-detail">
              {JSON.stringify(JSON.parse(selected.detail), null, 2)}
            </pre>
          </>
        )}
      </ResizableDrawer>
    </>
  );
}
