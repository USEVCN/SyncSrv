import { useResizableApp } from "../../shared/ResizableModal";
import { ResizableModal as Modal } from "../../shared/ResizableModal";
import { AgentBacklogDrawer, AgentBacklogTag } from "./AgentBacklog";
import { AgentConfiguration, AgentErrorHint } from "./AgentHealth";
import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Hint } from "../../shared/Hint";
import { AgentUpdates } from "./AgentUpdates";
import { AgentInstall } from "./AgentInstall";
import {
  Button,
  Card,
  Descriptions,
  Empty,
  Input,
  Space,
  Tag,
  Typography,
} from "antd";
import {
  CheckCircleOutlined,
  CopyOutlined,
  DownloadOutlined,
  DesktopOutlined,
  LinkOutlined,
  PlusOutlined,
  ReloadOutlined,
  StopOutlined,
} from "@ant-design/icons";
import { useCallback, useEffect, useRef, useState } from "react";
import { useStateData } from "../../app/State";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import { DataTable, PageHeader, useAction } from "../../shared/ui";
import { bytes, time } from "../../shared/format";
import type { Agent } from "../../shared/types";

const platformNames: Record<string, string> = {
  darwin: "macOS",
  linux: "Linux / NAS",
  windows: "Windows",
};
const online = (agent: Agent) =>
  Boolean(agent.lastSeen && Date.now() - Date.parse(agent.lastSeen) < 150000);
export function AgentsPage() {
  const { modal } = useResizableApp();
  const { data } = useStateData();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [error, setError] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [selected, setSelected] = useState<string>();
  const [backlogId, setBacklogId] = useState<string>();
  const [invitation, setInvitation] = useState<{
    token: string;
    expiresAt: string;
  }>();
  const [search, setSearch] = useState("");
  const [downloadsOpen, setDownloadsOpen] = useState(false);
  const action = useAction();
  const requestVersion = useRef(0);
  const refresh = useCallback(async () => {
    const version = ++requestVersion.current;
    try {
      const devices = await api<Agent[]>("/agents");
      if (version !== requestVersion.current) return;
      setAgents(devices);
      setError("");
      setLoaded(true);
    } catch (e) {
      if (version === requestVersion.current) setError((e as Error).message);
    }
  }, []);
  useEffect(() => {
    const stop = pollWhileVisible(refresh, 10000);
    return () => { requestVersion.current++; stop(); };
  }, [refresh]);
  const current = agents.find((a) => a.id === selected);
  const deviceJobs = data?.jobs.filter((j) => j.agentId === current?.id) ?? [];
  const authorize = (agent: Agent, approved: boolean) =>
    action.run(
      async () => {
        await api(`/agents/${agent.id}/authorization`, "POST", { approved });
        await refresh();
      },
      approved ? "设备已授权，等待配置下发" : "设备授权已撤销",
    );
  return (
    <>
      <PageHeader
        title="备份设备"
        eyebrow="BACKUP DEVICES / 设备"
        description="一台设备安装一个 Agent，可管理源目录、备份库、归档库和恢复库。任务由中心管理。"
        extra={
          <Space wrap>
            <AgentUpdates agents={agents} />
            <Button icon={<DownloadOutlined />} onClick={() => setDownloadsOpen(true)}>下载安装包</Button>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              loading={action.loading}
              onClick={() =>
                action.run(async () =>
                  setInvitation(await api("/agents/invitations", "POST")),
                )
              }
            >
              添加设备
            </Button>
          </Space>
        }
      />
      {error && (
        <Hint
          type="error"
          showIcon
          title="设备状态刷新失败"
          description={error}
          className="space-bottom"
        />
      )}
      <div className="discovery-grid">
        {[
          ["已授权设备", agents.filter((a) => a.status === "APPROVED").length],
          ["当前在线", agents.filter(online).length],
          ["等待授权", agents.filter((a) => a.status === "PENDING").length],
          ["设备总数", agents.length],
        ].map(([label, value]) => (
          <Card key={label}>
            <span className="muted">{label}</span>
            <div className="metric-value">{loaded && (label !== "当前在线" || !error) ? value : "—"}</div>
          </Card>
        ))}
      </div>
      <Card
        className="list-table-card agent-list-card"
        title="设备列表"
        extra={
          <Input.Search
            aria-label="搜索备份设备"
            placeholder="搜索设备名称或系统"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            allowClear
            style={{ width: 240 }}
          />
        }
      >
        <DataTable<Agent>
          rowKey="id"
          loading={!loaded && !error}
          dataSource={agents.filter((a) =>
            `${a.name} ${a.platform}`
              .toLowerCase()
              .includes(search.toLowerCase()),
          )}
          locale={{
            emptyText: (
              <Empty
                image={Empty.PRESENTED_IMAGE_SIMPLE}
                description="安装源端服务并配对后，设备会显示在这里。"
              />
            ),
          }}
          columns={[
            {
              title: "设备",
              dataIndex: "name",
              render: (_, a) => (
                <Space>
                  <DesktopOutlined />
                  <div>
                    <Button
                      type="link"
                      onClick={() => setSelected(a.id)}
                      style={{ padding: 0 }}
                    >
                      {a.name}
                    </Button>
                    <div className="muted">
                      {platformNames[a.platform] || a.platform} · {a.arch}
                    </div>
                  </div>
                </Space>
              ),
            },
            {
              title: "连接 / 授权",
              render: (_, a) => (
                <Space orientation="vertical" size={2}>
                  <Tag color={!error && online(a) ? "success" : "default"}>
                    {error ? "连接状态待确认" : online(a) ? "在线" : "离线 / 尚未连接"}
                  </Tag>
                  <span className="muted">
                    {{
                      APPROVED: "已授权",
                      PENDING: "等待授权",
                      REVOKED: "已撤销",
                    }[a.status] || a.status}
                  </span>
                </Space>
              ),
            },
            {
              title: "配置 / 备份状态",
              render: (_, a) => <AgentConfiguration agent={a} />,
            },
            {
              title: "源端积压",
              render: (_, a) => <AgentBacklogTag agent={a} stale={!!error} onClick={() => setBacklogId(a.id)} />,
            },
            {
              title: "已上传（本次服务启动）",
              render: (_, a) => !error && online(a) ? `${bytes(a.uploadedBytes || 0)} / ${a.uploadedFiles || 0} 个文件` : "—",
            },
            {
              title: "CPU / 内存峰值",
              render: (_, a) =>
                !error && online(a)
                  ? `${a.cpuPercent == null ? "—" : a.cpuPercent.toFixed(2) + "%"} / ${a.memoryBytes ? bytes(a.memoryBytes) : "—"}`
                  : "数据已过期",
            },
            { title: "最近心跳", render: (_, a) => time(a.lastSeen) },
            {
              title: "操作",
              render: (_, a) => (
                <Space>
                  <Button onClick={() => setSelected(a.id)}>详情</Button>
                  {a.status === "PENDING" && (
                    <Button
                      type="primary"
                      icon={<CheckCircleOutlined />}
                      loading={action.loading}
                      onClick={() => authorize(a, true)}
                    >
                      授权
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <AgentBacklogDrawer agent={agents.find(a => a.id === backlogId)} jobs={data?.jobs ?? []} onClose={() => setBacklogId(undefined)} />
      <Modal
        title="添加备份设备"
        open={!!invitation}
        onCancel={() => setInvitation(undefined)}
        footer={<Button onClick={() => setInvitation(undefined)}>完成</Button>}
        width={650}
      >
        <Hint
          type="info"
          showIcon
          title="一次性配对凭据"
          description="源端生成自己的密钥。注册后仍需在此页面授权设备；配对凭据不会成为长期密钥。"
        />
        <Descriptions
          column={1}
          className="space-top"
          items={[
            {
              key: "token",
              label: "配对凭据",
              children: (
                <Typography.Text
                  copyable={{ text: invitation?.token, icon: <CopyOutlined /> }}
                  code
                >
                  {invitation?.token}
                </Typography.Text>
              ),
            },
            {
              key: "expires",
              label: "有效期至",
              children: time(invitation?.expiresAt || ""),
            },
          ]}
        />
        <AgentInstall />
      </Modal>
      <Modal title="下载源端安装包" open={downloadsOpen} onCancel={() => setDownloadsOpen(false)}
        footer={<Button onClick={() => setDownloadsOpen(false)}>关闭</Button>} width={680} destroyOnHidden>
        <AgentInstall />
      </Modal>
      <ResizableDrawer storageKey="agent"
        title={current?.name || "设备详情"}
        open={!!current}
        onClose={() => setSelected(undefined)}
        initialWidth={736}
      >
        {current && (
          <div className="discovery-detail">
            <AgentErrorHint error={current.error || ""} />
            <Descriptions
              bordered
              column={1}
              items={[
                {
                  key: "id",
                  label: "设备标识",
                  children: (
                    <Typography.Text copyable>{current.id}</Typography.Text>
                  ),
                },
                {
                  key: "platform",
                  label: "系统 / 版本",
                  children: `${platformNames[current.platform] || current.platform} / ${current.version}`,
                },
                {
                  key: "capability",
                  label: "发现接口",
                  children: current.capability || "尚未上报",
                },
                {
                  key: "config",
                  label: "配置版本",
                  children: `期望 ${current.configVersion} / 已应用 ${current.appliedVersion}`,
                },
                {
                  key: "db",
                  label: "本地队列数据库",
                  children: current.databaseBytes
                    ? bytes(current.databaseBytes)
                    : "尚未上报",
                },
                {
                  key: "key",
                  label: "设备公钥",
                  children: (
                    <Typography.Text className="wrap mono" copyable>
                      {current.publicKey}
                    </Typography.Text>
                  ),
                },
              ]}
            />
            <Card title="设备上的存储目录">
              {data?.endpoints.filter(e => e.agentId === current.id).length ? data.endpoints.filter(e => e.agentId === current.id).map(e => <div key={e.id} className="space-bottom"><strong>{e.name}</strong><p className="mono wrap">{e.agentRoot}</p><Tag color="cyan">本地复制 / 校验</Tag></div>) : <Empty description="可在存储连接中将 备份库、归档库、恢复库绑定到此设备" />}
            </Card>
            <Card title="关联监听任务">
              {deviceJobs.length ? (
                deviceJobs.map((j) => (
                  <div key={j.id} className="space-bottom">
                    <strong>{j.name}</strong>
                    <p className="mono wrap">{j.agentRoot}</p>
                    <Tag
                      color={
                        !error && online(current) && current.watchingJobs.includes(j.id)
                          ? "success"
                          : "warning"
                      }
                    >
                      {error || !online(current) ? "监听状态待确认" : current.watchingJobs.includes(j.id)
                        ? "监听已建立"
                        : "等待监听"}
                    </Tag>
                    <span className="muted">
                      {j.enabled ? "自动处理已开启" : "传输已暂停，继续监听"}
                    </span>
                  </div>
                ))
              ) : (
                <Empty description="在备份任务中选择此设备并设置源端路径" />
              )}
            </Card>
            {current.status !== "REVOKED" && (
              <Button
                danger
                icon={<StopOutlined />}
                onClick={() =>
                  modal.confirm({
                    title: "撤销此设备的授权？",
                    content:
                      "已有备份与历史版本保留，设备将不能继续发送变化通知。",
                    okText: "撤销授权",
                    cancelText: "取消",
                    onOk: () => authorize(current, false),
                  })
                }
              >
                撤销授权
              </Button>
            )}
            {current.status === "REVOKED" && (
              <Button
                icon={<LinkOutlined />}
                onClick={() => authorize(current, true)}
              >
                重新授权
              </Button>
            )}
          </div>
        )}
      </ResizableDrawer>
    </>
  );
}
