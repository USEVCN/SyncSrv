import { Card, Space, Tabs, Tooltip } from "antd";
import { Hint } from "../../shared/Hint";
import { bytes } from "../../shared/format";
import { DataTable } from "../../shared/ui";
import { archiveTime, type ArchivePlan } from "./archiveCleanup";

export function ArchiveCleanupPreview({plan}:{plan:ArchivePlan}) {
  const versions=plan.items.filter(item=>item.kind==="VERSION");
  const summaries=[
    ["移除恢复节点",plan.expired.length], ["保留恢复节点",plan.retained.length],
    ["候选归档文件",versions.length], ["预计释放空间",bytes(plan.estimatedBytes)],
  ];
  return <>
    <div className="archive-cleanup-stats">{summaries.map(([label,value])=><Card key={label} size="small">
      <span className="muted">{label}</span><strong>{value}</strong>
    </Card>)}</div>
    <Space wrap>
      <span>清理边界：{archiveTime(plan.cutoff)} 之前（北京时间）</span>
      <Hint label="保留规则" title="源目录和备份库保持不变。归档库中仍被保留恢复节点、当前文件或未完成任务引用的文件会保留；无法确认归属的文件也会保留。截止日前最近一个基准节点会自动保留，以支持截止时间附近的完整合成。" />
      <Hint label="空间统计" title={`预计释放空间为候选文件的逻辑大小，包含 ${plan.items.length-versions.length} 个过期恢复节点清单；实际释放空间受去重、压缩、回收站和 NAS 快照影响。`} />
    </Space>
    <Tabs items={[
      {key:"expired",label:`将移除的节点 (${plan.expired.length})`,children:<DataTable configurable={false} rowKey="id" dataSource={plan.expired}
        tableLayout="fixed" scroll={{x:600}} columns={[
          {title:"恢复节点",dataIndex:"sequence",width:110,render:n=>`#${n}`},
          {title:"完成时间（北京时间）",dataIndex:"completedAt",render:archiveTime},
          {title:"文件数",dataIndex:"files",width:90},
          {title:"数据量",dataIndex:"totalBytes",width:110,render:bytes},
        ]} />},
      {key:"retained",label:`自动保留 (${plan.retained.length})`,children:<DataTable configurable={false} rowKey={row=>row.snapshot.id} dataSource={plan.retained}
        tableLayout="fixed" scroll={{x:650}} columns={[
          {title:"恢复节点",width:110,render:(_,row)=>`#${row.snapshot.sequence}`},
          {title:"完成时间（北京时间）",width:220,render:(_,row)=>archiveTime(row.snapshot.completedAt)},
          {title:"保留原因",render:(_,row)=>row.reasons.join("；")},
        ]} />},
      {key:"files",label:`候选文件 (${plan.items.length})`,children:<DataTable configurable={false} rowKey="id" dataSource={plan.items}
        tableLayout="fixed" scroll={{x:650}} columns={[
          {title:"归档库位置",dataIndex:"location",render:path=><Tooltip title={<span className="mono wrap">{path}</span>}>
            <span className="archive-cleanup-path mono">{path}</span></Tooltip>},
          {title:"类型",dataIndex:"kind",width:110,render:kind=>kind==="VERSION" ? "历史文件" : "节点清单"},
          {title:"大小",dataIndex:"size",width:110,render:bytes},
        ]} />},
    ]} />
  </>;
}
