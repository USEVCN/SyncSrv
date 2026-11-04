import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { fileType } from "./fileType";
import { Button, Descriptions, Empty, Select, Space, Spin, Tree, type TreeDataNode } from "antd";
import { FileOutlined, FolderOutlined, ReloadOutlined } from "@ant-design/icons";
import { useEffect, useRef, useState, type Key } from "react";
import { api, ApiError } from "../../platform/http";
import { bytes, time } from "../../shared/format";
import { Hint } from "../../shared/Hint";
import { storageRoleNames } from "../../shared/StorageRole";
import type { Job } from "../../shared/types";

export type DirectoryRole = "A" | "B" | "C";
type Entry = { path: string; name: string; directory: boolean; size: number; modified: number; symbolic: boolean };
type Listing = { root: string; mode: string; observedAt: string; entries: Entry[]; total: number; hasMore: boolean };
const roles = storageRoleNames;
const keyFor = (path: string) => `path:${path}`;
export function DirectoryTreeDrawer({ value, onClose }: {
  value?: { job: Job; role: DirectoryRole }; onClose: () => void;
}) {
  return <ResizableDrawer storageKey="directory-tree" title={value ? `${value.job.name} / ${roles[value.role]} / 目录树` : "目录树"}
    initialWidth={960} open={!!value} onClose={onClose} destroyOnHidden>
    {value && <DirectoryTree key={`${value.job.id}:${value.role}`} job={value.job} role={value.role} />}
  </ResizableDrawer>;
}
export function DirectoryTree({ job, role }: { job: Job; role: DirectoryRole }) {
  const [branches, setBranches] = useState<Record<string, Listing>>({});
  const [expanded, setExpanded] = useState<Key[]>([keyFor("")]);
  const [selected, setSelected] = useState<Entry>();
  const [error, setError] = useState("");
  const [sort, setSort] = useState("modified");
  const [busy, setBusy] = useState(0);
  const generation = useRef(0);
  const pending = useRef(new Set<string>());
  const load = async (path: string, offset = 0) => {
    const request = `${path}:${offset}`;
    if (pending.current.has(request)) return;
    pending.current.add(request);
    const version = generation.current;
    setBusy(n => n + 1);
    try {
      const value = await api<Listing>(`/jobs/${encodeURIComponent(job.id)}/tree?role=${role}&path=${encodeURIComponent(path)}&offset=${offset}&sort=${sort}`);
      if (version !== generation.current) return;
      setBranches(previous => ({ ...previous, [path]: { ...value,
        entries: offset ? [...new Map([...(previous[path]?.entries || []), ...value.entries].map(item => [item.path,item])).values()] : value.entries,
      } }));
      setError("");
    } catch (e) {
      if (version === generation.current) setError(e instanceof ApiError && e.status === 404
        ? "当前运行的服务端尚未提供目录树接口，需要加载本次更新后的服务端。" : (e as Error).message);
    } finally {
      if (version === generation.current) { pending.current.delete(request); setBusy(n => n - 1); }
    }
  };
  const refresh = () => {
    generation.current++;
    pending.current.clear();
    setBranches({}); setSelected(undefined); setError(""); setBusy(0);
    setExpanded([keyFor("")]);
    void load("");
  };
  useEffect(() => {
    refresh();
    return () => { generation.current++; };
  }, [job.id, role, sort]);
  const root = branches[""];
  const children = (path: string): TreeDataNode[] | undefined => {
    const branch = branches[path];
    if (!branch) return undefined;
    const nodes: TreeDataNode[] = branch.entries.map(entry => ({
      key: keyFor(entry.path),
      title: <span className="directory-node"><span>{entry.name}</span>
        <small className="muted directory-type">{fileType(entry, role === "C")}</small>
        <small className="muted directory-size">{entry.directory || entry.symbolic ? "—" : bytes(entry.size)}</small>
        <time className="muted directory-date">{entry.modified ? time(new Date(entry.modified).toISOString()) : "—"}</time></span>,
      icon: entry.directory ? <FolderOutlined /> : <FileOutlined />,
      isLeaf: !entry.directory || entry.symbolic || (!!branches[entry.path] && branches[entry.path].total === 0),
      children: entry.directory && !entry.symbolic ? children(entry.path) : undefined,
    }));
    if (branch.hasMore) nodes.push({ key: `more:${path}`, isLeaf: true, selectable: false,
      title: <Button type="link" size="small" loading={pending.current.has(`${path}:${branch.entries.length}`)}
        onClick={event => { event.stopPropagation(); void load(path, branch.entries.length); }}>加载更多（已显示 {branch.entries.length} / {branch.total}）</Button>,
    });
    return nodes;
  };
  const nodes: TreeDataNode[] = root ? [{ key: keyFor(""), title: root.root, icon: <FolderOutlined />,
    isLeaf: root.total === 0, children: children("") }] : [];
  return <section aria-label={`${roles[role]}目录树`}>
    <div className="section-heading space-bottom">
      <Space wrap>
        <Hint label="目录树说明" title={role === "A" && job.sourceMode === "AGENT"
          ? "源目录展示源端最近成功上报的目录清单，不代表源端当前实时状态。源端离线期间可能发生未上报变化。"
          : role === "C" ? "归档库展示此备份任务的版本目录，文件名保留版本号与替换、删除标记。展开时读取该层目录，不扫描文件内容。"
          : "展开时读取存储中该层目录；浏览期间文件可能变化，可刷新重新读取。目录树不会修改或下载文件内容。"} />
        {root && <span className="muted">{root.mode === "SOURCE_MANIFEST" ? "源端上报" : "读取时间"} · {time(root.observedAt)}</span>}
      </Space>
      <Space>
        <Select aria-label="目录排序" value={sort} onChange={setSort} style={{width: 170}}
          options={[{value:"modified",label:"更新时间 · 倒序"},{value:"name",label:"文件名称 · 升序"}]} />
        <Button icon={<ReloadOutlined />} onClick={refresh} loading={busy > 0}>刷新</Button>
      </Space>
    </div>
    {error && <Hint className="space-bottom" type="error" label="目录读取失败" title={error} />}
    <div className="directory-columns muted"><span>文件名称</span><span className="directory-type">文件类型</span><span className="directory-size">大小</span><span>最后更新 <Hint iconOnly label="最后更新说明" title="使用文件或文件夹的最后修改时间。文件夹时间不代表全部子文件的更新时间；源目录的时间来自源端最近上报清单。" /></span></div>
    <Spin spinning={busy > 0}>
      {root ? <Tree.DirectoryTree className="directory-tree" blockNode showLine expandedKeys={expanded} treeData={nodes}
        onExpand={(keys, { expanded: opening, node }) => {
          setExpanded(keys);
          const path = String(node.key).slice(5);
          if (opening && !branches[path]) void load(path);
        }}
        onSelect={keys => {
          const path = String(keys[0] || "").slice(5);
          setSelected(Object.values(branches).flatMap(branch => branch.entries).find(entry => entry.path === path));
        }} /> : <Empty description={error ? "目录暂不可用" : "正在读取目录"} />}
      {root?.total === 0 && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="此目录暂无文件或子目录" />}
    </Spin>
    {selected && <Descriptions column={1} bordered size="small" className="space-top" items={[
      { key: "path", label: "相对路径", children: <span className="mono wrap">{selected.path}</span> },
      { key: "type", label: "文件类型", children: fileType(selected, role === "C") },
      { key: "size", label: "大小", children: selected.directory ? "—" : bytes(selected.size) },
      { key: "modified", label: "最后更新", children: selected.modified ? time(new Date(selected.modified).toISOString()) : "—" },
    ]} />}
  </section>;
}
