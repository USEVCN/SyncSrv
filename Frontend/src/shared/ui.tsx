import { App, ConfigProvider, Tag, Typography } from "antd";
import { useRef, useState, type ReactNode } from "react";
import { InfoHint } from "./InfoHint";
import { statusLabels } from "./format";

export function PageHeader({
  title,
  description,
  extra,
}: {
  title: string;
  description: string;
  extra?: ReactNode;
  eyebrow?: string;
}) {
  return (
    <div className="page-heading list-page-header">
      <div className="list-page-heading">
        <Typography.Title level={3} style={{margin:0}}>{title}</Typography.Title>
        <InfoHint label={`${title}说明`} content={description} />
      </div>
      {extra && <ConfigProvider componentSize="small"><div className="heading-actions list-page-actions">{extra}</div></ConfigProvider>}
    </div>
  );
}
export function Status({ value }: { value: string }) {
  const color =
    value === "COMPLETED"
      ? "success"
      : value === "FAILED"
        ? "error"
        : ["RUNNING", "QUEUED", "PAUSING"].includes(value)
          ? "processing"
          : "default";
  return (
    <Tag color={color} bordered={false}>
      {statusLabels[value] || value}
    </Tag>
  );
}
export { DataTable } from "./DataTable";
export function Mono({ children }: { children: ReactNode }) {
  return (
    <Typography.Text className="mono" copyable>
      {children}
    </Typography.Text>
  );
}
export function useAction() {
  const { message } = App.useApp();
  const [loading, setLoading] = useState(false);
  const pending = useRef(false);
  async function run<T>(
    action: () => Promise<T>,
    success?: string,
  ): Promise<T | undefined> {
    // React state updates on the next render; guard rapid clicks synchronously.
    if (pending.current) return undefined;
    pending.current = true;
    setLoading(true);
    try {
      const value = await action();
      if (success) void message.success(success);
      return value;
    } catch (e) {
      void message.error(e instanceof Error ? e.message : String(e));
      return undefined;
    } finally {
      pending.current = false;
      setLoading(false);
    }
  }
  return { run, loading };
}
export function Brand({ compact = false }: { compact?: boolean }) {
  return (
    <div className="brand">
      <img className="brand-mark" src="/system-logo.svg"
        alt={compact ? "SyncSrv" : ""} width={36} height={36} />
      {!compact && (
        <span>
          SyncSrv<small>备份 · 归档 · 恢复</small>
        </span>
      )}
    </div>
  );
}
