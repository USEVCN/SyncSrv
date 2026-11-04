import { Space, Tag, Tooltip } from "antd";
import type { Agent } from "../../shared/types";
import { Hint } from "../../shared/Hint";
import { storageText } from "../../shared/storageText";

export function agentHealth(error: string) {
  const split = error.match(/^(.*?)(?:Notification delivery:|Source delivery:)\s*([\s\S]*)$/);
  const configuration = split ? split[1].trim() : error;
  const delivery = split?.[2] || "";
  const busy = /SOURCE_BUSY:|being used by another process|used by another process has locked|sharing violation/i.test(delivery);
  return { configuration, delivery, busy };
}
export function AgentErrorHint({ error }: { error: string }) {
  const health = agentHealth(error);
  if (!error) return null;
  const title = health.configuration ? "配置或监听异常" : health.busy ? "文件占用，等待重试" : "备份待重试";
  return <Hint type={health.configuration ? "error" : "warning"} label={title} title={title}
    description={<Space orientation="vertical">
      <span>{health.busy ? "源文件暂时被其他程序占用，变化仍保留在队列中。等待写入结束或关闭占用程序后，源端会自动重试；当前文件尚未完成备份。" : health.delivery ? "源端读取或传输尚未完成，队列保留并自动重试。请检查文件访问权限和 源目录到备份库的连接。" : "请检查源端目录、配置和监听日志。"}</span>
      <span className="mono wrap">{storageText(error)}</span>
    </Space>} />;
}
export function AgentConfiguration({ agent: a }: { agent: Agent }) {
  const health = agentHealth(a.error || "");
  const label = a.status !== "APPROVED" ? "未授权" : !a.lastSeen ? "尚未连接" : health.configuration ? "配置异常" : a.appliedVersion === a.configVersion ? "已生效" : "待下发";
  const color = a.status !== "APPROVED" || !a.lastSeen ? "default" : health.configuration ? "error" : a.appliedVersion === a.configVersion ? "success" : "processing";
  return <Space orientation="vertical" size={2}>
    <Tooltip title={storageText(health.configuration) || `期望版本 ${a.configVersion}，已应用版本 ${a.appliedVersion}`}>
      <Tag color={color} style={{ cursor: "pointer" }}>{label}</Tag>
    </Tooltip>
    {health.delivery && <AgentErrorHint error={a.error} />}
  </Space>;
}
