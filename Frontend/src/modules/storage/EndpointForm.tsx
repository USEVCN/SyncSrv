import { ResizableModal as Modal } from "../../shared/ResizableModal";
import { Hint } from "../../shared/Hint";
import { Form, Input, InputNumber, Select, Switch } from "antd";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { protocols, protocolLabel } from "../../shared/format";
import { useAction } from "../../shared/ui";
import type { Agent, Endpoint } from "../../shared/types";

export function EndpointForm({
  value,
  onClose,
  onSaved,
}: {
  value: Partial<Endpoint> | null;
  onClose: () => void;
  onSaved: () => Promise<void>;
}) {
  const [form] = Form.useForm();
  const action = useAction();
  const [agents, setAgents] = useState<Agent[]>([]);
  const [agentError, setAgentError] = useState("");
  const agentId = Form.useWatch("agentId", form) || "";
  useEffect(() => {
    if (!value) return;
    let active = true;
    void api<Agent[]>("/agents").then(items => { if (active) { setAgents(items); setAgentError(""); } })
      .catch(() => { if (active) setAgentError("执行设备列表加载失败，请关闭后重试。"); });
    return () => { active = false; };
  }, [value]);
  const protocol = Form.useWatch("protocol", form) || "LOCAL";
  const port = Form.useWatch("port", form) || 0;
  useEffect(() => {
    if (value) {
      form.resetFields();
      form.setFieldsValue({
        agentId: "", agentRoot: "", allowRelay: true,
        protocol: "LOCAL",
        port: 0,
        ...value,
        password: "",
      });
    }
  }, [value, form]);
  const remote = protocol !== "LOCAL";
  return (
    <Modal
      title={value?.id ? "编辑存储连接" : "添加存储连接"}
      open={value !== null}
      onCancel={onClose}
      onOk={() => form.submit()}
      okText="保存连接"
      cancelText="取消"
      confirmLoading={action.loading}
      closable={!action.loading}
      maskClosable={!action.loading}
      keyboard={!action.loading}
      cancelButtonProps={{disabled:action.loading}}
      width={620}
      destroyOnHidden
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={(values) =>
          action.run(async () => {
            await api("/endpoints", "POST", { ...value, ...values, agentRoot: values.agentId ? values.agentRoot : "" });
            await onSaved();
            onClose();
          }, "存储连接已保存")
        }
      >
        <Form.Item
          name="name"
          label="连接名称"
          rules={[{ required: true, message: "请输入连接名称" }]}
        >
          <Input placeholder="例如：设计资料 · 源目录" maxLength={100} />
        </Form.Item>
        <Form.Item name="protocol" label="存储协议">
          <Select
            options={protocols.map((p) => ({
              value: p,
              label: protocolLabel[p],
            }))}
            disabled={!!value?.id}
          />
        </Form.Item>
        {remote && (
          <div className="form-grid">
            <Form.Item
              name="host"
              label={protocol === "WEBDAV" ? "WebDAV 地址" : "主机地址"}
              rules={[{ required: true, message: "请输入主机地址" }]}
            >
              <Input
                placeholder={
                  protocol === "WEBDAV"
                    ? "https://dav.example.com"
                    : "192.168.1.10"
                }
              />
            </Form.Item>
            <Form.Item
              name="port"
              label="端口（0 使用默认）"
              tooltip={protocol === "SMB" ? "SMB 默认端口为 445；0 也表示使用 445。" : undefined}
            >
              <InputNumber min={0} max={65535} className="full-width" />
            </Form.Item>
          </div>
        )}
        {protocol === "SMB" && [5000, 5001].includes(port) && (
          <Hint
            type="warning"
            showIcon
            title="此端口通常用于 NAS 网页管理"
            description="群晖的 5000/5001 是管理页面端口。SMB 通常使用 445，请确认填写的是文件共享服务端口。"
            style={{ marginBottom: 16 }}
          />
        )}
        <Form.Item
          name="root"
          label={protocol === "SMB" ? "共享名称 / 子目录" : "根目录"}
          rules={[{ required: true, message: "请输入根目录" }]}
          tooltip={
            protocol === "LOCAL"
              ? "填写服务所在机器上已存在的绝对目录。源目录、备份库、归档库必须互相独立。"
              : protocol === "SMB"
                ? "例如 downloads/Backup：downloads 是共享名，Backup 是共享内的子目录。"
                : "连接根目录内的文件和子目录将参与备份。"
          }
        >
          <Input
            placeholder={
              protocol === "LOCAL"
                ? "/Users/yourname/Documents/source"
                : protocol === "SMB"
                  ? "share/documents"
                  : "/documents"
            }
          />
        </Form.Item>
        {remote && (
          <div className="form-grid">
            <Form.Item name="username" label="账号">
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item
              name="password"
              label={value?.id ? "新密码（留空保持原密码）" : "密码"}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          </div>
        )}
        {protocol === "SMB" && (
          <Form.Item name="domain" label="域（可选）">
            <Input />
          </Form.Item>
        )}
        {protocol === "SFTP" && (
          <Form.Item
            name="hostKey"
            label="服务器主机密钥指纹"
            rules={[
              { required: true, message: "请填写 SSH SHA256 主机密钥指纹" },
            ]}
            tooltip="请向服务器管理员核对指纹，首次连接也会严格验证。"
          >
            <Input placeholder="SHA256:..." className="mono" />
          </Form.Item>
        )}
        <Form.Item name="agentId" label="优先执行设备" tooltip="同一设备只安装一个 Agent。备份库、归档库、恢复库位于同一台 NAS 时，选择同一设备并分别填写本地目录。">
          <Select options={[{ value: "", label: "未绑定 · 使用存储协议" }, ...agents.filter(a => a.status === "APPROVED").map(a => ({value: a.id,label: `${a.name} · ${a.platform}`}))]} />
        </Form.Item>
        {agentError && <p role="alert">{agentError}</p>}
        {agentId && <>
          <Form.Item name="agentRoot" label="该设备上的本地目录" rules={[{required:true,message:"请输入执行设备上的绝对目录"}]}
            tooltip="须与上方存储连接指向同一目录，保存后点击测试核对映射。">
            <Input placeholder="例如 /volume1/backup/current 或 E:\Backups" />
          </Form.Item>
          <p className="muted">复制与校验优先在设备执行。已领取但结果未确认的任务会等待回执，避免重复写入。</p>
        </>}
        <Form.Item name="allowRelay" label="允许中心中转" valuePropName="checked" tooltip="优先设备执行和存储端复制。关闭后，不支持直接复制的网络路径会明确失败，避免流量经过中心。">
          <Switch />
        </Form.Item>
        {protocol === "WEBDAV" && (
          <Hint
            type="info"
            showIcon
            title="普通 WebDAV 上传中断后整文件重试"
            description="下载续传需要服务器支持 Range。上传完成后会校验完整内容。"
          />
        )}
        {protocol === "FTP" && (
          <Hint
            type="warning"
            showIcon
            title="FTP 以明文传输凭据与内容"
            description="跨不可信网络时建议选用 SFTP 或 HTTPS WebDAV。"
          />
        )}
      </Form>
    </Modal>
  );
}
