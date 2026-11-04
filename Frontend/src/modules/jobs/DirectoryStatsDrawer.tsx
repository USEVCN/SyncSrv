import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { Hint } from "../../shared/Hint";
import { Button, Input, Space } from "antd";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { bytes, time } from "../../shared/format";
import { DataTable } from "../../shared/ui";
import type { Job } from "../../shared/types";

type DirectoryStats = {
  path: string;
  files: number;
  directories: number;
  logicalBytes: number;
  updatedAt: string;
};
export function DirectoryStatsDrawer({
  job,
  onClose,
}: {
  job: Job | undefined;
  onClose: () => void;
}) {
  const [rows, setRows] = useState<DirectoryStats[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [filter, setFilter] = useState("");
  const [reload, setReload] = useState(0);
  useEffect(() => {
    let current = true;
    if (job) {
      setLoading(true);
      setError("");
      setRows([]);
      setFilter("");
      void api<DirectoryStats[]>(`/jobs/${job.id}/directories`)
        .then((result) => {
          if (current) setRows(result);
        })
        .catch((e) => {
          if (current) setError((e as Error).message);
        })
        .finally(() => {
          if (current) setLoading(false);
        });
    }
    return () => {
      current = false;
    };
  }, [job, reload]);
  return (
    <ResizableDrawer storageKey="directory-stats"
      title={`${job?.name || ""} · 目录统计`}
      open={!!job}
      onClose={onClose}
      initialWidth={736}
    >
      <Hint
        type="info"
        showIcon
        title="已提交到备份库的目录状态"
        description="递归文件数、目录数与逻辑大小随备份提交更新。更新时间表示数据库统计提交时间；不能用它证明源目录没有变化。"
        className="space-bottom"
      />
      {error && <Hint type="error" title={error} className="space-bottom" />}
      <Space className="space-bottom">
        <Input
          placeholder="筛选目录路径"
          value={filter}
          onChange={(e) => setFilter(e.target.value)}
        />
        <Button onClick={() => setReload((n) => n + 1)}>刷新</Button>
      </Space>
      <DataTable<DirectoryStats>
        rowKey="path"
        loading={loading}
        dataSource={rows.filter((row) => row.path.includes(filter))}
        columns={[
          {
            title: "目录",
            dataIndex: "path",
            render: (p: string) => p || "任务根目录",
          },
          { title: "文件数", dataIndex: "files" },
          { title: "子目录数", dataIndex: "directories" },
          { title: "逻辑大小", dataIndex: "logicalBytes", render: bytes },
          { title: "统计更新时间", dataIndex: "updatedAt", render: time },
        ]}
      />
    </ResizableDrawer>
  );
}
