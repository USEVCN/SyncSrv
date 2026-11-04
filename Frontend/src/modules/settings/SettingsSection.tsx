import { Card, type CardProps } from "antd";

/** LE settings groups, with SyncSrv's existing theme and business contents. */
export function SettingsSection({ className = "", ...props }: CardProps) {
  return <Card size="small" {...props} className={`settings-section ${className}`} />;
}
