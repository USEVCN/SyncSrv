import { useResizableApp } from "../../shared/ResizableModal";
import { Button, Card, Input, Popconfirm, Select, Space, Tag } from "antd";
import {
  PlusOutlined,
  SearchOutlined,
  DatabaseOutlined,
  LinkOutlined,
  EditOutlined,
  DeleteOutlined,
} from "@ant-design/icons";
import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { useStateData } from "../../app/State";
import { DataTable, PageHeader, useAction } from "../../shared/ui";
import { protocols, protocolLabel } from "../../shared/format";
import { api } from "../../platform/http";
import type { Endpoint } from "../../shared/types";
import { EndpointForm } from "./EndpointForm";

export function StoragePage() {
  const { data, refresh } = useStateData();
  const [params, setParams] = useSearchParams();
  const { modal } = useResizableApp();
  const [editing, setEditing] = useState<Partial<Endpoint> | null>(
    params.has("new") ? {} : null,
  );
  const [search, setSearch] = useState("");
  const [protocol, setProtocol] = useState("");
  const action = useAction();
  if (!data) return null;
  const rows = data.endpoints.filter(
    (e) =>
      (!protocol || e.protocol === protocol) &&
      `${e.name} ${e.host} ${e.root}`
        .toLowerCase()
        .includes(search.toLowerCase()),
  );
  function close() {
    setEditing(null);
    setParams({});
  }
  async function probe(e: Endpoint) {
    const result = await api<{
      entries: number;
      deviceMappingVerified?: boolean;
      capabilities: { note: string };
    }>(`/endpoints/${e.id}/probe`, "POST");
    modal.success({
      title: `${e.name}：读取测试通过`,
      content: (
        <>
          <p>根目录可读取，共 {result.entries} 个直接子条目。</p>
          <p>{result.capabilities.note}</p>
          {result.deviceMappingVerified ? <p>设备本地目录映射已验证，可在设备内复制和校验。核对标识保存在 .syncsrv 内。</p> : <p>此测试不写入文件，写入权限在执行时验证。</p>}
        </>
      ),
    });
  }
  return (
    <>
      <PageHeader
        title="存储连接"
        description="统一管理源目录、备份库、归档库、恢复库的存储连接。"
        eyebrow="STORAGE / 连接"
        extra={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setEditing({})}
          >
            添加存储连接
          </Button>
        }
      />
      <div className="protocol-strip">
        {protocols.map((p) => (
          <span key={p}>
            <span className="tiny-dot" />
            {protocolLabel[p]}
          </span>
        ))}
        <small>凭据加密保存</small>
      </div>
      <Card className="list-table-card table-card">
        <div className="table-toolbar">
          <Input
            prefix={<SearchOutlined />}
            placeholder="搜索名称、主机或目录"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            allowClear
          />
          <Select
            value={protocol}
            onChange={setProtocol}
            options={[
              { value: "", label: "全部协议" },
              ...protocols.map((p) => ({ value: p, label: protocolLabel[p] })),
            ]}
          />
        </div>
        <DataTable<Endpoint>
          rowKey="id"
          dataSource={rows}
          columns={[
            {
              title: "存储名称",
              dataIndex: "name",
              render: (name, e) => (
                <div className="table-name">
                  <span className="table-icon">
                    <DatabaseOutlined />
                  </span>
                  <div>
                    <strong>{name}</strong>
                    <small>
                      {e.protocol === "LOCAL"
                        ? "服务本地磁盘"
                        : e.protocol === "SMB"
                          ? `${e.host}:${e.port || 445}`
                          : e.host}
                    </small>
                  </div>
                </div>
              ),
            },
            {
              title: "协议",
              dataIndex: "protocol",
              render: (value) => (
                <Tag bordered={false} color="purple">
                  {protocolLabel[value]}
                </Tag>
              ),
            },
            {
              title: "根目录",
              dataIndex: "root",
              render: (value) => (
                <span className="mono path-cell" title={value}>
                  {value}
                </span>
              ),
            },
            {
              title: "执行位置",
              render: (_, e) => e.agentId ? <div><Tag color="cyan">设备优先</Tag><small className="mono">{e.agentRoot}</small><br/><small className="muted">{e.allowRelay === false ? "中转已关闭" : "中心中转兜底"}</small></div> : <span className="muted">{e.allowRelay===false?"仅直接复制 / 设备执行":"存储端优先 · 允许中心中转"}</span>,
            },
            {
              title: "关联任务",
              render: (_, e) =>
                `${data.jobs.filter((j) => [j.sourceId, j.targetId, j.archiveId].includes(e.id)).length} 个`,
            },
            {
              title: "操作",
              width: 240,
              render: (_, e) => (
                <Space size={0}>
                  <Button
                    type="text"
                    icon={<LinkOutlined />}
                    loading={action.loading}
                    onClick={() => action.run(() => probe(e))}
                  >
                    测试
                  </Button>
                  <Button
                    type="text"
                    aria-label={`编辑 ${e.name}`}
                    icon={<EditOutlined />}
                    onClick={() => setEditing(e)}
                  />
                  <Popconfirm
                    title="移除这个存储连接？"
                    description="已有任务引用时无法移除。"
                    onConfirm={() =>
                      action.run(async () => {
                        await api(`/endpoints/${e.id}`, "DELETE");
                        await refresh();
                      }, "连接已移除")
                    }
                    okText="移除"
                    cancelText="取消"
                  >
                    <Button
                      type="text"
                      danger
                      aria-label={`移除 ${e.name}`}
                      icon={<DeleteOutlined />}
                    />
                  </Popconfirm>
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <EndpointForm value={editing} onClose={close} onSaved={refresh} />
    </>
  );
}
