import { Hint } from "../../shared/Hint";
import { Button, Card, DatePicker, Select, Tag } from "antd";
import { HistoryOutlined } from "@ant-design/icons";
import { useEffect,useState } from "react";
import type { Dayjs } from "dayjs";
import { useStateData } from "../../app/State";
import { DataTable, PageHeader, Mono } from "../../shared/ui";
import { bytes, short, time } from "../../shared/format";
import type { Snapshot } from "../../shared/types";
import { RestoreModal } from "./RestoreModal";

import {api} from "../../platform/http";
import {useHistory} from "../../shared/useHistory";

export function SnapshotsPage() {
  const { data, refresh } = useStateData();
  const [jobId, setJobId] = useState("");
  const [point, setPoint] = useState<Dayjs | null>(null);
  const [selected, setSelected] = useState<Snapshot>();
  const history=useHistory<Snapshot>("snapshots",data?.snapshots||[]);
  const [nearestSnapshot,setNearestSnapshot]=useState<Snapshot|null>(null),[nearestLoading,setNearestLoading]=useState(false),[nearestError,setNearestError]=useState("");
  const targetTime=point?.valueOf();
  useEffect(()=>{let alive=true;setNearestSnapshot(null);setNearestError("");
    if(!jobId||targetTime===undefined){setNearestLoading(false);return;}
    setNearestLoading(true);api<Snapshot|null>(`/history/nearest?jobId=${encodeURIComponent(jobId)}&at=${encodeURIComponent(new Date(targetTime).toISOString())}`).then(value=>{if(alive)setNearestSnapshot(value);}).catch(e=>{if(alive)setNearestError((e as Error).message);}).finally(()=>{if(alive)setNearestLoading(false);});
    return()=>{alive=false;};
  },[jobId,targetTime]);
  if (!data) return null;
  const rows = history.rows.sort((a,b)=>b.completedAt.localeCompare(a.completedAt)).filter(
    (s) =>
      (!jobId || s.jobId === jobId) &&
      (!point || new Date(s.completedAt).getTime() <= point.valueOf()),
  );
  const nearest = jobId && point && !nearestLoading ? nearestSnapshot : undefined;
  return (
    <>
      <div className="recovery-heading">
        <PageHeader
          title="恢复"
          description="选择任务和目标时间，定位该时间之前最近一次成功备份，以完整恢复清单为准，从备份库和归档库合成文件集合。"
          extra={<div className="recovery-controls">
          <Select
            placeholder="选择备份任务"
            value={jobId || undefined}
            allowClear
            onChange={(v) => setJobId(v || "")}
            options={data.jobs.map((j) => ({ value: j.id, label: j.name }))}
          />
          <DatePicker
            showTime
            format="YYYY-MM-DD HH:mm:ss"
            value={point}
            onChange={setPoint}
            placeholder="目标时间"
          />
          <Button
            type="primary"
            disabled={!nearest}
            onClick={() => {if(nearest)setSelected(nearest);}}
          >
            恢复
          </Button>
          </div>}
        />
      </div>
      {nearestError&&<Hint title={nearestError} type="error"/>}
      {point && jobId && (
        <Hint
          className="space-top"
          type={nearest ? "info" : "warning"}
          showIcon
          title={
            nearest
              ? `选中节点 #${nearest.sequence}，实际备份完成时间：${time(nearest.completedAt)}`
              : "目标时间之前没有完整恢复节点"
          }
          description="恢复节点记录已完成备份的文件状态；不代表应用程序在任意瞬间的一致性快照。"
        />
      )}
      <Card className="list-table-card table-card space-top" extra={history.control}>
        <DataTable<Snapshot>
          rowKey="id"
          dataSource={rows}
          columns={[
            {
              title: "恢复节点",
              render: (_, s) => (
                <div className="table-name">
                  <span className="table-icon">
                    <HistoryOutlined />
                  </span>
                  <div>
                    <strong>
                      #{s.sequence.toString().padStart(4, "0")} ·{" "}
                      {data.jobs.find((j) => j.id === s.jobId)?.name}
                    </strong>
                    <small>{short(s.id)}</small>
                  </div>
                </div>
              ),
            },
            { title: "完成时间", dataIndex: "completedAt", render: time },
            { title: "文件", dataIndex: "files", render: (v) => `${v} 个` },
            { title: "逻辑大小", dataIndex: "totalBytes", render: bytes },
            {
              title: "清单",
              dataIndex: "manifestHash",
              render: (value) => (
                <Tag bordered={false} color="success" title={value}>
                  完整清单
                </Tag>
              ),
            },
            {
              title: "操作",
              render: (_, s) => (
                <Button
                  type="link"
                  icon={<HistoryOutlined />}
                  onClick={() => setSelected(s)}
                >
                  预览
                </Button>
              ),
            },
          ]}
        />
      </Card>
      {nearest && (
        <div className="manifest-foot">
          清单 SHA-256 <Mono>{nearest.manifestHash}</Mono>
        </div>
      )}
      <RestoreModal
        snapshot={selected}
        onClose={() => setSelected(undefined)}
        onRestored={refresh}
      />
    </>
  );
}
