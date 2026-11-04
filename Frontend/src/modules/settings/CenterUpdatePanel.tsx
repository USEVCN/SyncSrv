import { ReloadOutlined } from "@ant-design/icons";
import { Button, Descriptions, Input, Space, Tag, Typography } from "antd";
import { useEffect, useRef, useState } from "react";
import { api } from "../../platform/http";
import { Hint } from "../../shared/Hint";

type Check = {
  status: string; message: string; currentVersion: string; latestVersion: string;
  project: string; channel: string; os: string; arch: string; releaseId: string;
  signature: string; sourceMin: string; sourceMax: string; checkedAt: string;
  platformSupported: boolean; manifestUrl: string;
};
const labels: Record<string, string> = { UPDATE_AVAILABLE: "发现兼容新版本", UP_TO_DATE: "已是推荐版本", INCOMPATIBLE: "版本不兼容",
  SIGNATURE_REJECTED: "签名验证失败", CONTRACT_REJECTED: "发布身份不匹配", CHECK_FAILED: "检查失败", CHECKING: "检查进行中" };
export function CenterUpdatePanel({ healthUrl }: { healthUrl: string }) {
  const [result, setResult] = useState<Check>(), [busy, setBusy] = useState(false), [error, setError] = useState("");
  const generation = useRef(0), working = useRef(false);
  const [source,setSource]=useState<{serverUrl:string;revision:string}>(),[server,setServer]=useState("");
  useEffect(()=>{let active=true;api<{serverUrl:string;revision:string}>("/system/updates/source").then(value=>{if(active){setSource(value);setServer(value.serverUrl);}}).catch(e=>{if(active)setError((e as Error).message);});return()=>{active=false;};},[]);
  const saveSource=async()=>{if(!source)return;setBusy(true);setError("");try{const value=await api<{serverUrl:string;revision:string}>("/system/updates/source","PUT",{serverUrl:server.trim(),expectedRevision:source.revision});setSource(value);setServer(value.serverUrl);setResult(undefined);}catch(e){setError((e as Error).message);}finally{setBusy(false);}};

  useEffect(() => () => { generation.current++; }, []);
  const check = async () => {
    if (working.current) return;
    working.current = true; const attempt = ++generation.current;
    setBusy(true); setError(""); setResult(undefined);
    try {
      const next = await api<Check>("/system/updates/check");
      if (attempt === generation.current) setResult(next);
    } catch (failure) { if (attempt === generation.current) setError((failure as Error).message); }
    finally { working.current = false; if (attempt === generation.current) setBusy(false); }
  };
  const success = result && ["UPDATE_AVAILABLE", "UP_TO_DATE"].includes(result.status);
  return <section aria-label="中心版本检查">
    <div className="settings-panel-heading"><Space wrap><Typography.Text strong>中心在线升级</Typography.Text><Tag>Linux x64 · 手动安装</Tag></Space>
      <Button icon={<ReloadOutlined />} loading={busy} disabled={busy} onClick={() => void check()}>检查版本</Button></div>
    <Space orientation="vertical" className="full-width" size="middle">
      <Input aria-label="更新服务器地址" placeholder="https://update.devgo.io:18761" value={server} disabled={!source||busy} onChange={e=>setServer(e.target.value)}/>
      <Typography.Text type="secondary">只填服务器根地址；自动选择 SyncSrv、当前渠道和 Linux x64 发行，并继续验证签名与升级范围。</Typography.Text>
      <Button onClick={()=>void saveSource()} disabled={!source||busy}>保存更新源</Button>

      {!result && !error && <Typography.Text type="secondary">{busy ? "正在验证发布清单与签名…" : "尚未检查更新"}</Typography.Text>}
      {error && <Hint type="error" label="版本检查请求失败" title={error} />}
      {result && <><Space wrap><Tag color={success ? "success" : "warning"}>{labels[result.status] || "检查未完成"}</Tag>
        <Hint label="检查结果说明" title={result.message} /></Space>
        <Descriptions size="small" bordered column={1} items={[
          {key: "current", label: "当前中心版本", children: result.currentVersion},
          {key: "latest", label: "渠道推荐版本", children: result.latestVersion || "未取得可信版本"},
          {key: "target", label: "发布身份", children: `${result.project} / ${result.channel || "未确认"} / ${result.os} / ${result.arch}`},
          {key: "signature", label: "签名校验", children: result.signature},
          ...(result.sourceMin && result.sourceMax ? [{key: "range", label: "允许升级的来源版本", children: `${result.sourceMin} — ${result.sourceMax}`}] : []),
          ...(result.releaseId ? [{key: "release", label: "发布 ID", children: <Typography.Text copyable>{result.releaseId}</Typography.Text>}] : []),
          {key: "time", label: "检查时间", children: new Date(result.checkedAt).toLocaleString()},
        ]} />
        {!result.platformSupported && <Hint label="当前平台仅供核验" title="中心安装包面向 Linux x64。Mac、Windows 和其他架构可以查看签名发布信息，但本页面不会在当前机器安装。" />}
        {result.manifestUrl && <Hint label="查看更新来源" title={result.manifestUrl} />}
      </>}
      <Typography.Paragraph copyable={{text: `sh /opt/syncsrv/current/update.sh check --health-url ${healthUrl}`}} code>update.sh check</Typography.Paragraph>
      <Typography.Paragraph copyable={{text: `sh /opt/syncsrv/current/update.sh update --health-url ${healthUrl}`}} code>update.sh update</Typography.Paragraph>
      <Hint label="升级流程说明" title="先在 Mac 修改并确认，再发布到 RelsHub；由管理员在 Linux 服务器手动执行脚本。页面仅下载小型发布元数据并验签，不下载安装包、不安装、不重启。安装时仍会重新验签、核对包摘要并执行健康检查和失败回滚。" />
    </Space>
  </section>;
}
