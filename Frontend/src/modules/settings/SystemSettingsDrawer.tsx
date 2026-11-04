import { Button, Flex, Skeleton, Space, Tabs, Tag, Typography } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { useStateData } from "../../app/State";
import { api } from "../../platform/http";
import { Hint } from "../../shared/Hint";
import { ResizableDrawer } from "../../shared/ResizableDrawer";
import { BasicSettingsPanel } from "./BasicSettingsPanel";
import { TypographySettingsPanel } from "./TypographySettingsPanel";
import { TlsSettingsPanel } from "./TlsSettingsPanel";
import { OperationsPanel } from "./OperationsPanel";
import { CenterUpdatePanel } from "./CenterUpdatePanel";
import { ProtocolCapabilities } from "./ProtocolCapabilities";
import { SettingsSection } from "./SettingsSection";
import { defaultUiSettings, type UiSettings } from "./settingsModel";
import "./SystemSettingsDrawer.css";

export function SystemSettingsDrawer({onClose}:{onClose:()=>void}) {
  const {data}=useStateData();
  const [value,setValue]=useState<UiSettings>(data?.uiSettings||defaultUiSettings),[loading,setLoading]=useState(true),[ready,setReady]=useState(false),[error,setError]=useState("");
  const bind=data?.system.bindAddress||"127.0.0.1";
  const healthHost=["0.0.0.0","::"].includes(bind)?"127.0.0.1":bind.includes(":")?`[${bind}]`:bind;
  const healthUrl=`http://${healthHost}:${data?.system.port||18010}/api/health`;
  const generation=useRef(0);
  const load=useCallback(async()=>{
    const attempt=++generation.current;setLoading(true);setReady(false);setError("");
    try{const result=await api<UiSettings>("/system/settings");if(attempt!==generation.current)return;setValue(result);setReady(true);}
    catch(failure){if(attempt===generation.current)setError((failure as Error).message);}
    finally{if(attempt===generation.current)setLoading(false);}
  },[]);
  useEffect(()=>{void load();return()=>{generation.current++;};},[load]);
  return <ResizableDrawer storageKey="system-settings" initialWidth={760} title="系统设置" open onClose={onClose} destroyOnHidden rootClassName="system-settings-drawer">
    <div className="system-settings">
    {error&&<div className="settings-feedback"><Hint type="error" label="设置读取失败" title={error}/><Button size="small" onClick={()=>void load()}>重新读取</Button></div>}
    {loading?<Skeleton active/>:<Tabs className="system-settings-tabs" destroyOnHidden items={[
      {key:"basic",label:"基础设置",children:<BasicSettingsPanel value={value} ready={ready} onSaved={setValue}/>},
      {key:"runtime",label:"运行环境",children:<SettingsSection title="系统关于" role="region" aria-label="系统关于"><Flex vertical gap={16}>
        <Flex align="center" justify="space-between" gap={12} wrap><Space size={10}><img src="/system-logo.svg" width={32} height={32} alt="SyncSrv"/><Typography.Text strong>SyncSrv</Typography.Text></Space><Tag>当前版本 · {data?.system.version||"暂不可用"}</Tag></Flex>
        <dl className="settings-facts">
          <div><dt>Java</dt><dd>{data?.system.java||"暂不可用"}</dd></div>
          <div><dt>数据库</dt><dd>{data?.system.database||"暂不可用"}</dd></div>
          <div><dt>主服务端口</dt><dd>{data?.system.port||"暂不可用"}</dd></div>
          <div><dt>监听地址</dt><dd>{data?.system.bindAddress||"暂不可用"}</dd></div>
        </dl>
      </Flex></SettingsSection>},
      {key:"appearance",label:"界面字号",children:<TypographySettingsPanel value={value} ready={ready} onSaved={setValue}/>},
      {key:"tls",label:"HTTPS",children:<SettingsSection title="HTTPS 配置"><TlsSettingsPanel/></SettingsSection>},
      {key:"update",label:"在线升级",children:<div className="settings-stack"><SettingsSection title="版本与发行"><CenterUpdatePanel healthUrl={healthUrl}/></SettingsSection><OperationsPanel/></div>},
      {key:"protocols",label:"协议能力",children:<SettingsSection title="协议与恢复能力"><ProtocolCapabilities/></SettingsSection>},
    ]}/>}
    </div>
  </ResizableDrawer>;
}
