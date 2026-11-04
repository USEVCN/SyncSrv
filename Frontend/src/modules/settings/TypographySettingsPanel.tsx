import { App, Button, Card, InputNumber, Space, Typography } from "antd";
import { FontSizeOutlined, ReloadOutlined, SaveOutlined } from "@ant-design/icons";
import { useState } from "react";
import { api } from "../../platform/http";
import { useStateData } from "../../app/State";
import { Hint } from "../../shared/Hint";
import { defaultTypography, typographyLevels, validTypography, type UiSettings } from "./settingsModel";
import { SettingsSection } from "./SettingsSection";
export function TypographySettingsPanel({value,ready,onSaved}:{value:UiSettings;ready:boolean;onSaved:(value:UiSettings)=>void}) {
  const [draft,setDraft]=useState({...value.typography}),[saving,setSaving]=useState(false),{message}=App.useApp(),{refresh}=useStateData();
  const valid=validTypography(draft);
  const save=async()=>{
    if(!ready||saving||!valid)return;setSaving(true);
    try{const next=await api<UiSettings>("/system/settings","PUT",{...value,typography:draft});onSaved(next);await refresh();void message.success("界面字号已保存并生效");}
    catch(error){void message.error((error as Error).message);}finally{setSaving(false);}
  };
  return <SettingsSection title={<Space><FontSizeOutlined/>全局字号分级<Hint iconOnly label="全局字号说明" title="只影响系统界面。所有用户共享，保存后当前界面立即应用，其他已登录页面随状态刷新同步。"/></Space>}>
    <div className="settings-type-levels">{typographyLevels.map((level,index)=><div className="settings-type-level" key={level.key}>
      <span className="settings-type-index">{index+1}</span><div><label htmlFor={`ui-font-${level.key}`}>{level.label}</label><Typography.Text type="secondary">{level.usage}</Typography.Text></div>
      <InputNumber id={`ui-font-${level.key}`} aria-label={level.label} value={draft[level.key]} min={level.min} max={level.max} precision={0} suffix="px" disabled={!ready||saving}
        onChange={number=>setDraft(current=>({...current,[level.key]:number??0}))}/></div>)}</div>
    {!valid&&<Hint type="warning" title="各级字号须为范围内的整数，且后一级不能小于前一级。"/>}
    <Card size="small" title="字号预览" className="settings-type-preview">{typographyLevels.map(level=><div key={level.key} style={{fontSize:draft[level.key]||level.value}}><span>{level.label}</span><span>SyncSrv · Aa 123</span></div>)}</Card>
    <div className="settings-actions"><Button icon={<ReloadOutlined/>} disabled={!ready||saving} onClick={()=>setDraft({...defaultTypography})}>恢复默认字号</Button>
      <Button type="primary" icon={<SaveOutlined/>} loading={saving} disabled={!ready||!valid} onClick={()=>void save()}>保存并应用</Button></div>
  </SettingsSection>;
}
