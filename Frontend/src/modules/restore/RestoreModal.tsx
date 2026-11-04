import { ResizableModal as Modal } from "../../shared/ResizableModal";
import { Hint } from "../../shared/Hint";
import { Form, Input, Segmented, Select, Tag } from "antd";
import { useEffect, useState } from "react";
import type { RestoreItem, RestorePlan, Snapshot } from "../../shared/types";
import { api } from "../../platform/http";
import { DataTable, useAction } from "../../shared/ui";
import { useStateData } from "../../app/State";
import { bytes, time } from "../../shared/format";

export function RestoreModal({
  snapshot,
  onClose,
  onRestored,
}: {
  snapshot?: Snapshot;
  onClose: () => void;
  onRestored: () => Promise<void>;
}) {
  const [form] = Form.useForm();
  const { data } = useStateData();
  const endpointId = Form.useWatch("endpointId", form);
  const [plan, setPlan] = useState<RestorePlan>();
  const [error, setError] = useState("");
  const [tab, setTab] = useState("选取文件");
  const action = useAction();
  useEffect(() => {
    setPlan(undefined);
    setError("");
    form.resetFields();
    form.setFieldsValue({
      directory: snapshot ? `snapshot-${snapshot.sequence}-${snapshot.id.slice(0, 8)}` : "",
    });
    let alive = true;
    if (snapshot)
      api<RestorePlan>(`/snapshots/${snapshot.id}/plan`)
        .then((p) => {
          if (alive) setPlan(p);
        })
        .catch((e) => {
          if (alive) setError(e.message);
        });
    return () => {
      alive = false;
    };
  }, [snapshot, form]);
  return (
    <Modal
      open={!!snapshot}
      title="恢复"
      width={940}
      okText="恢复"
      cancelText="取消"
      onCancel={onClose}
      onOk={() => form.submit()}
      confirmLoading={action.loading}
      closable={!action.loading}
      maskClosable={!action.loading}
      keyboard={!action.loading}
      cancelButtonProps={{disabled:action.loading}}
      okButtonProps={{ disabled: !plan, "aria-label": "恢复" }}
      destroyOnHidden
    >
      {error && <Hint type="error" title={error} showIcon />}
      <div className="restore-summary">
        <div>
          <small>恢复节点</small>
          <strong>#{snapshot?.sequence.toString().padStart(4, "0")}</strong>
        </div>
        <div>
          <small>备份完成时间</small>
          <strong>{time(snapshot?.completedAt)}</strong>
        </div>
        <div>
          <small>文件 / 大小</small>
          <strong>
            {snapshot?.files} 个 · {bytes(snapshot?.totalBytes)}
          </strong>
        </div>
      </div>
      <Hint
        type="info"
        showIcon
        title="备份库与归档库按清单共同组成这个节点"
        description="恢复库的目录必须独立且为空；之前中断的同一恢复可继续。节点之后新增的文件不会被提取。"
        className="space-bottom"
      />
      <Segmented
        value={tab}
        onChange={setTab}
        options={["选取文件", "排除的当前路径"]}
        className="space-bottom"
      />
      {tab === "选取文件" ? (
        <DataTable<RestoreItem>
          size="small"
          loading={!plan && !error}
          rowKey="path"
          dataSource={plan?.items || []}
          pagination={{ pageSize: 5, showSizeChanger: false }}
          columns={[
            {
              title: "原始路径",
              dataIndex: "path",
              render: (value) => <span className="mono">{value}</span>,
            },
            {
              title: "提取来源",
              dataIndex: "decision",
              render: (value) => (
                <Tag
                  color={value === "CURRENT_B" ? "purple" : "cyan"}
                  bordered={false}
                >
                  {value === "CURRENT_B" ? "备份库" : "归档库"}
                </Tag>
              ),
            },
            { title: "大小", dataIndex: "size", render: bytes },
          ]}
        />
      ) : (
        <div className="excluded-list">
          {plan?.excludedCurrentPaths.length ? (
            plan.excludedCurrentPaths.map((path) => (
              <div className="mono" key={path}>
                {path}
                <Tag bordered={false}>不提取</Tag>
              </div>
            ))
          ) : (
            <p className="muted">没有需要排除的当前路径。</p>
          )}
        </div>
      )}
      <Form
        form={form}
        layout="vertical"
        className="space-top"
        onFinish={(values) =>
          action.run(async () => {
            await api("/restores", "POST", {
              snapshotId: snapshot?.id,
              directory: values.directory || "",
              endpointId: values.endpointId,
            });
            await onRestored();
            onClose();
          }, "恢复已加入队列，可在执行记录查看进度")
        }
      >
        <Form.Item
          name="endpointId"
          label="恢复库的存储连接"
          rules={[{ required: true, message: "请选择独立的恢复库连接" }]}
        >
          <Select
            showSearch
            optionFilterProp="label"
            placeholder="选择群晖 SMB、SFTP 等连接"
            options={(data?.endpoints || []).map((e) => ({
              value: e.id,
              label: `${e.name} · ${e.protocol} · ${e.root}`,
            }))}
          />
        </Form.Item>
        <Form.Item name="directory" label="恢复子目录（相对于所选连接根目录）">
          <Input placeholder="例如 project/snapshot-2026-09-18" />
        </Form.Item>
        {endpointId && (
          <Hint type="info" title="点击恢复，将从备份库和归档库提取该时间节点的完整文件到恢复库，无需挂载到 Mac。目标目录必须为空，或属于同一恢复节点。" />
        )}
      </Form>
    </Modal>
  );
}
