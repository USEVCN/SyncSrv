import { DatabaseOutlined, FolderOpenOutlined, HistoryOutlined, InboxOutlined } from "@ant-design/icons";

export type StorageRole = "A" | "B" | "C" | "D";

export const storageRoleNames: Record<StorageRole, string> = {
  A: "源目录",
  B: "备份库",
  C: "归档库",
  D: "恢复库",
};

export function StorageRoleIcon({ role }: { role: StorageRole }) {
  const Icon = { A: FolderOpenOutlined, B: DatabaseOutlined, C: InboxOutlined, D: HistoryOutlined }[role];
  return <Icon aria-hidden />;
}
