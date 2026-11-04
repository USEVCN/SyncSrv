import { ResizableModal as Modal } from "../../shared/ResizableModal";
import { Hint } from "../../shared/Hint";
import { Form, Input, InputNumber, Select, Switch } from "antd";
import { useEffect, useState } from "react";
import type { Agent, Endpoint, Job } from "../../shared/types";
import { api } from "../../platform/http";
import { useAction } from "../../shared/ui";
import { protocolLabel } from "../../shared/format";

export function JobForm({
  value,
  endpoints,
  onClose,
  onSaved,
}: {
  value: Partial<Job> | null;
  endpoints: Endpoint[];
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [form] = Form.useForm();
  const action = useAction();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentError, setAgentError] = useState("");
  const sourceMode = Form.useWatch("sourceMode", form) || "AGENT";
  const adaptive = Form.useWatch("adaptiveTransfers", form) ?? value?.adaptiveTransfers ?? true;
  const mode = Form.useWatch("discoveryMode", form) || "SCAN";
  useEffect(() => {
    if (!value) return;
    let active=true;
    setAgents([]);setAgentError("");
    void api<Agent[]>("/agents")
      .then(items=>{if(active)setAgents(items);})
      .catch(()=>{if(active)setAgentError("设备列表加载失败，请关闭后重试。");});
    return()=>{active=false;};
  }, [value]);
  useEffect(() => {
    if (value) {
      form.resetFields();
      form.setFieldsValue({
        intervalSeconds: 5,
        sourceMode: value.id ? (value.sourceMode || "CONNECTION") : "AGENT",
        discoveryMode: value.id ? (value.discoveryMode || "SCAN") : "EVENTS",
        reconcileSeconds: 86400,
        bandwidthMiB: 0,
        transferThreads: 4,
        archiveThreads: 4,
        adaptiveTransfers: true,
        maxTransferThreads: 16,
        enabled: true,
        ...value,
      });
    }
  }, [value, form]);
  return (
    <Modal
      title={value?.id ? "编辑备份任务" : "创建备份任务"}
      open={value !== null}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText="保存任务"
      cancelText="取消"
      width={650}
      confirmLoading={action.loading}
      closable={!action.loading}
      maskClosable={!action.loading}
      keyboard={!action.loading}
      cancelButtonProps={{disabled:action.loading}}
      destroyOnHidden
    >
      <Form
        layout="vertical"
        form={form}
        onFinish={(values) =>
          action.run(async () => {
            await api("/jobs", "POST", { ...value, ...values });
            await onSaved();
            onClose();
          }, "备份任务已保存")
        }
      >
        <Form.Item
          name="name"
          label="任务名称"
          rules={[{ required: true, message: "请输入任务名称" }]}
        >
          <Input placeholder="例如：项目文档每日备份" />
        </Form.Item>
        <Form.Item name="sourceMode" label="源目录提供方式">
          <Select disabled={!!value?.id} onChange={(next) => form.setFieldsValue({ discoveryMode: next === "AGENT" ? "EVENTS" : "SCAN", sourceId: undefined,
            intervalSeconds: next === "CONNECTION" ? Math.max(30,form.getFieldValue("intervalSeconds") || 30) : form.getFieldValue("intervalSeconds") })}
            options={[{value:"AGENT",label:"安装源端服务 · 直传备份库"},{value:"CONNECTION",label:"已有协议连接 · 中心读取"}]} />
        </Form.Item>
        {[
          ...(sourceMode === "AGENT" ? [] : [["sourceId", "源目录"]]),
          ["targetId", "备份库"],
          ["archiveId", "归档库"],
        ].map(([field, title]) => (
          <Form.Item
            key={field}
            name={field}
            label={title}
            rules={[{ required: true, message: `请选择${title}的存储连接` }]}
          >
            <Select
              disabled={!!value?.id}
              placeholder="选择已保存的存储连接"
              showSearch
              optionFilterProp="label"
              options={endpoints.map((e) => ({
                value: e.id,
                disabled: sourceMode === "AGENT" && field === "targetId" && e.protocol === "LOCAL",
                label: `${e.name} · ${protocolLabel[e.protocol]} · ${e.root}`,
              }))}
            />
          </Form.Item>
        ))}
        {endpoints.length < (sourceMode === "AGENT" ? 2 : 3) && (
          <Hint
            label="存储连接准备"
            type="info"
            title={sourceMode === "AGENT" ? "先保存备份库、归档库两个独立存储连接；源目录在下方选择设备和本地目录。" : "请先保存源目录、备份库、归档库三个独立存储连接。"}
            className="space-bottom"
          />
        )}
        <Form.Item name="discoveryMode" label="变化发现方式">
          <Select
            disabled={sourceMode === "AGENT"}
            options={[
              { value: "EVENTS", label: "源端触发 · 只处理变化路径" },
              { value: "SCAN", label: "周期扫描 · 兼容现有连接" },
            ]}
          />
        </Form.Item>
        {mode === "EVENTS" && (
          <>
            {agentError && <p role="alert">{agentError}</p>}
            <Form.Item
              name="agentId"
              label="备份设备"
              tooltip="同一台设备可用于多个任务，无需重复安装源端服务。"
              rules={[{ required: true, message: "请选择已授权的备份设备" }]}
            >
              <Select
                placeholder="选择已授权设备"
                disabled={!!value?.agentId}
                options={agents
                  .filter((a) => a.status === "APPROVED")
                  .map((a) => ({
                    value: a.id,
                    label: `${a.name} · ${a.platform}`,
                  }))}
              />
            </Form.Item>
            <Form.Item
              name="agentRoot"
              label="源目录的本地监听路径"
              tooltip="填写本任务待备份的文件夹；其他目录可另建任务并选择同一设备。"
              rules={[
                { required: true, message: "请输入源端实际文件夹的绝对路径" },
              ]}
            >
              <Input
                disabled={!!value?.agentId}
                placeholder="例如 /srv/documents 或 D:\资料"
              />
            </Form.Item>
            <Hint
              type="info"
              showIcon
              title={sourceMode === "AGENT" ? "源目录直传备份库" : "监听路径须对应同一份源目录"}
              description={sourceMode === "AGENT" ? "填写 Windows、Linux 或 macOS 源端上的实际路径，无需为源目录配置共享或远程协议。源端直接通过备份库的协议传输，中心只接收清单和状态。首次建立基线，后续按变化处理。" : "源端通知变化，中心通过源目录的连接读取内容。"}
              className="space-bottom"
            />
            <Form.Item
              name="reconcileSeconds"
              label="独立完整校准间隔（秒）"
              rules={[{ required: true }]}
            >
              <InputNumber min={300} max={31536000} className="full-width" />
            </Form.Item>
          </>
        )}
        <div className="form-grid">
          <Form.Item
            name="intervalSeconds"
            label={sourceMode === "AGENT" ? "连续保存合并等待（秒）" : mode === "EVENTS" ? "变化处理间隔（秒）" : "备份间隔（秒）"}
            tooltip={sourceMode === "AGENT" ? "变化会先登记到服务端。该任务持续此时间无新变化后，源目录才开始传输；再次保存会重新计时。" : undefined}
            rules={[{ required: true }]}
          >
            <InputNumber
              min={mode === "EVENTS" ? 1 : 30}
              max={31536000}
              className="full-width"
            />
          </Form.Item>
          <Form.Item name="bandwidthMiB" label="任务限速（MiB/s，0 不限）">
            <InputNumber
              min={0}
              max={100000}
              precision={2}
              className="full-width"
            />
          </Form.Item>
        </div>
        {sourceMode === "AGENT" && <>
          <Form.Item name="adaptiveTransfers" label="自动调整文件并发" valuePropName="checked"
            tooltip="根据文件大小、完成速度和处理延迟试调并发；无收益时回退，所有并发共享总限速。需要 Agent 0.13 或以上，下一批执行生效。">
            <Switch checkedChildren="自动" unCheckedChildren="手动" />
          </Form.Item>
          <Form.Item name="maxTransferThreads" label="自动并发上限" hidden={!adaptive} rules={[{required:true}]}>
            <InputNumber min={1} max={16} precision={0} className="full-width" />
          </Form.Item>
          <Form.Item name="transferThreads" label="并发文件数（线程数）" hidden={adaptive} rules={[{required:true}]}>
            <InputNumber min={1} max={16} precision={0} className="full-width" />
          </Form.Item>
        </>}
        <Form.Item name="archiveThreads" label="中心文件并发数" rules={[{required:true}]}
          tooltip="中心同时处理的独立文件数，默认 4、最多 16。包括归档、校验和文件落盘；目录操作、断点恢复及整批提交保持顺序。所有线程共享全局和任务限速，下一次执行生效。">
          <InputNumber min={1} max={16} precision={0} className="full-width" />
        </Form.Item>
        <Form.Item
          name="enabled"
          label={mode === "EVENTS" ? "自动处理变化" : "开启定时备份"}
          valuePropName="checked"
        >
          <Switch checkedChildren="开启" unCheckedChildren="关闭" />
        </Form.Item>
        <Hint
          type="info"
          showIcon
          title="首次备份要求备份库为空"
          description="源目录删除的文件会从备份库移至归档库，并添加删除标记；备份库和归档库应由此服务独占管理。"
        />
      </Form>
    </Modal>
  );
}
