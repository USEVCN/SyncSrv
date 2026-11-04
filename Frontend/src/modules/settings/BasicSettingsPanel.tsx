import { App, Button, Form, Input, InputNumber, Space } from "antd";
import { useState } from "react";
import { api } from "../../platform/http";
import { useStateData } from "../../app/State";
import { Hint } from "../../shared/Hint";
import type { UiSettings } from "./settingsModel";
import { SettingsSection } from "./SettingsSection";
export function BasicSettingsPanel({value,ready,onSaved}:{value:UiSettings;ready:boolean;onSaved:(value:UiSettings)=>void}) {
  const {data,refresh}=useStateData(),{message}=App.useApp();
  const [form]=Form.useForm(),[transfer]=Form.useForm();
  const [saving,setSaving]=useState("");
  return <div className="settings-stack"><Form layout="vertical" form={form} initialValues={value} disabled={!ready||Boolean(saving)} onFinish={async values=>{
    if(saving)return;setSaving("basic");
    try{const next=await api<UiSettings>("/system/settings","PUT",{...value,...values});onSaved(next);await refresh();void message.success("基础设置已保存");}
    catch(error){void message.error((error as Error).message);}finally{setSaving("");}
  }}>
    <SettingsSection title="系统信息"><Form.Item name="systemName" label="系统名称" rules={[{required:true,whitespace:true,max:60}]}><Input maxLength={60}/></Form.Item>
    <Form.Item name="description" label="系统说明" rules={[{max:240}]}><Input.TextArea rows={3} maxLength={240}/></Form.Item>
    <div className="settings-actions"><Button type="primary" htmlType="submit" disabled={!ready||Boolean(saving)} loading={saving==="basic"}>保存基础设置</Button></div></SettingsSection>
  </Form>
  <Form layout="vertical" form={transfer} disabled={!ready||Boolean(saving)} initialValues={{bandwidthMiB:data?.settings.bandwidthMiB??0}} onFinish={async values=>{
    if(saving)return;setSaving("transfer");try{await api("/settings","PUT",values);await refresh();void message.success("全局限速已更新");}
    catch(error){void message.error((error as Error).message);}finally{setSaving("");}
  }}>
    <SettingsSection title={<Space size={6}>传输控制<Hint iconOnly label="传输控制说明" title="0 为不限速。校验读取也计入限速；任务限速与全局限速取更严格的设置。"/></Space>}>
    <Form.Item name="bandwidthMiB" label="全局限速（MiB/s）" rules={[{required:true},{type:"number",min:0,max:100000}]}><InputNumber min={0} max={100000} precision={2} className="full-width"/></Form.Item>
    <div className="settings-actions"><Button htmlType="submit" loading={saving==="transfer"} disabled={!ready||Boolean(saving)}>保存传输设置</Button></div></SettingsSection>
  </Form></div>;
}
