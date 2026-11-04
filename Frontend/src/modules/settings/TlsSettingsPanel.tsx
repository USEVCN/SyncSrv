import { ReloadOutlined, SafetyCertificateOutlined, UploadOutlined } from "@ant-design/icons";
import { App, Button, Descriptions, Form, Input, InputNumber, Select, Space, Spin, Switch, Tag, Typography, Upload } from "antd";
import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../../platform/http";
import { Hint } from "../../shared/Hint";

type Certificate = { subject:string; issuer:string; notAfter:string; daysRemaining:number; sha256:string };
type TlsState = { enabled:boolean; active:boolean; publicUrl:string; port:number; bindAddress:string; certificateInstalled:boolean; certificate?:Certificate; error:string };
type Selection = {name:string;pem:string};
export function TlsSettingsPanel() {
  const { message, modal } = App.useApp();
  const [form] = Form.useForm();
  const [state,setState] = useState<TlsState>(), [certificate,setCertificate] = useState<Selection>(), [privateKey,setPrivateKey] = useState<Selection>();
  const [checked,setChecked] = useState<Certificate>(), [busy,setBusy] = useState("read"), [error,setError] = useState("");
  const generation = useRef(0), working = useRef(false);
  const enabled = Form.useWatch("enabled",form);
  const load = useCallback(async()=>{
    if (working.current) return;
    const attempt=++generation.current;
    setBusy("read");setError("");
    try {
      const next=await api<TlsState>("/system/https");
      if(attempt!==generation.current)return;
      setState(next);form.setFieldsValue(next);setCertificate(undefined);setPrivateKey(undefined);setChecked(undefined);
    }catch(failure){if(attempt===generation.current)setError((failure as Error).message);}
    finally{if(attempt===generation.current)setBusy("");}
  },[form]);
  useEffect(()=>{void load();return()=>{generation.current++;};},[load]);
  const choose=async(file:File,isCertificate:boolean)=>{
    const attempt=generation.current;
    if(file.size>(isCertificate?131072:32768)){void message.error("证书或私钥文件过大");return false;}
    try{
      const pem=await file.text();
      if(attempt!==generation.current)return false;
      const header=isCertificate?/-----BEGIN CERTIFICATE-----/:/-----BEGIN (?:RSA |EC )?PRIVATE KEY-----/;
      if(!header.test(pem))throw new Error("请选择 PEM 证书链或未加密私钥");
      (isCertificate?setCertificate:setPrivateKey)({name:file.name,pem});setChecked(undefined);setError("");
    }catch(failure){void message.error((failure as Error).message);}
    return false;
  };
  const submit=async(save:boolean)=>{
    if(working.current||busy||!state)return;
    let values;
    try{values=await form.validateFields();}catch{return;}
    if(Boolean(certificate)!==Boolean(privateKey)){setError("换证时请同时选择证书链和私钥");return;}
    if((!save||values.enabled)&&!state.certificateInstalled&&(!certificate||!privateKey)){setError("请先导入证书链和私钥");return;}
    if(working.current)return;
    working.current=true;setBusy(save?"save":"check");setError("");
    const attempt=generation.current;
    try{
      const payload={enabled:Boolean(values.enabled),publicUrl:values.publicUrl?.trim()||"",port:values.port,bindAddress:values.bindAddress,
        certificatePem:certificate?.pem??null,privateKeyPem:privateKey?.pem??null};
      if(save){
        const next=await api<TlsState>("/system/https","PUT",payload);
        if(attempt!==generation.current)return;
        setState(next);form.setFieldsValue(next);setCertificate(undefined);setPrivateKey(undefined);setChecked(undefined);
        void message.success(next.active?"HTTPS 配置已生效":"HTTPS 已关闭");
      }else{
        const metadata=await api<Certificate>("/system/https/validate","POST",payload);
        if(attempt!==generation.current)return;
        setChecked(metadata);void message.success("域名、证书和私钥校验通过");
      }
    }catch(failure){if(attempt===generation.current)setError((failure as Error).message);}
    finally{working.current=false;if(attempt===generation.current)setBusy("");}
  };
  const save=()=>{
    const values=form.getFieldsValue();
    if(state?.active&&location.protocol==="https:"&&(!values.enabled||values.port!==state.port||values.bindAddress!==state.bindAddress)){
      modal.confirm({title:"更新当前 HTTPS 入口？",content:"修改端口或关闭 HTTPS 会断开当前入口，请确认原有 HTTP 入口仍可访问。",okText:"确认保存",onOk:()=>submit(true)});
    }else void submit(true);
  };
  const metadata=checked||(!(certificate||privateKey)?state?.certificate:undefined);
  const hasPair=Boolean(state?.certificateInstalled||(certificate&&privateKey));
  const label=(text:string,help:string)=><Space size={6}>{text}<Hint iconOnly label={`${text}说明`} title={help}/></Space>;
  return <section className="settings-tls" aria-label="HTTPS 配置">
    <div className="settings-panel-heading"><Space><Tag color={state?.active?"success":"default"}>{state?.active?"HTTPS 已启用":"HTTPS 尚未启用"}</Tag>
      <Hint iconOnly label="HTTPS 状态说明" title="独立 HTTPS 入口，保存立即生效，重启后恢复。原有 HTTP 入口保持可用；仅启用 TLS 1.2 和 TLS 1.3。"/></Space>
      <Button size="small" icon={<ReloadOutlined/>} disabled={Boolean(busy)} onClick={()=>void load()}>重新读取</Button></div>
    {(error||state?.error)&&<div className="settings-feedback"><Hint type="error" label="查看 HTTPS 问题" title={error||state?.error}/>{!state&&<Button onClick={()=>void load()}>重试</Button>}</div>}
    <Spin spinning={busy==="read"}><Form form={form} layout="vertical" disabled={Boolean(busy)||!state} onValuesChange={()=>{setChecked(undefined);setError("");}}>
      <Form.Item name="enabled" label="启用 HTTPS" valuePropName="checked"><Switch/></Form.Item>
      <Form.Item name="publicUrl" label={label("HTTPS 对外地址","填写证书对应的 HTTPS 域名或 IP；非标准外部端口需加端口号。")}
        rules={[{required:Boolean(enabled||certificate||privateKey||state?.certificateInstalled),message:"请输入 HTTPS 对外地址"},{pattern:/^https:\/\/[^/?#\s]+\/?$/,message:"请输入完整 HTTPS 地址"}]}>
        <Input placeholder="https://sync.example.com" autoComplete="off"/></Form.Item>
      <div className="settings-form-columns"><Form.Item name="port" label={label("HTTPS 本机监听端口","默认 18014，不能与主服务、前端开发或验收端口冲突；外部 443 可转发到此端口。")}
        rules={[{required:true},{type:"integer",min:1024,max:65535}]}><InputNumber min={1024} max={65535} className="full-width"/></Form.Item>
        <Form.Item name="bindAddress" label="HTTPS 监听地址"><Select options={[{value:"127.0.0.1",label:"仅本机"},{value:"0.0.0.0",label:"局域网 / 全部网卡"}]}/></Form.Item></div>
      <Form.Item label={label("站点证书链（PEM）","选择 fullchain.pem，站点证书在前，中间证书在后。") }><Space wrap>
        <Upload accept=".pem,.crt,.cer" showUploadList={false} disabled={Boolean(busy)||!state} beforeUpload={file=>choose(file,true)}><Button icon={<UploadOutlined/>}>选择证书链</Button></Upload>
        <Typography.Text type="secondary">{certificate?.name||(state?.certificateInstalled?"已保存证书":"尚未选择")}</Typography.Text></Space></Form.Item>
      <Form.Item label={label("证书私钥（PEM）","仅保存在中心数据目录的受限 https 子目录，不回显，不写入审计正文；换证需同时选择证书链和私钥。") }><Space wrap>
        <Upload accept=".pem,.key" showUploadList={false} disabled={Boolean(busy)||!state} beforeUpload={file=>choose(file,false)}><Button icon={<UploadOutlined/>}>选择私钥</Button></Upload>
        <Typography.Text type="secondary">{privateKey?.name||(state?.certificateInstalled?"已保存私钥":"尚未选择")}</Typography.Text>
        {(certificate||privateKey)&&<Button type="text" size="small" disabled={Boolean(busy)} onClick={()=>{setCertificate(undefined);setPrivateKey(undefined);setChecked(undefined);}}>取消选择</Button>}</Space></Form.Item>
    </Form></Spin>
    {metadata?.sha256&&<Descriptions size="small" bordered column={1} items={[
      {key:"subject",label:"证书主体",children:metadata.subject},{key:"issuer",label:"签发者",children:metadata.issuer},
      {key:"expiry",label:"证书到期时间",children:<Space wrap>{new Date(metadata.notAfter).toLocaleString()}<Tag color={metadata.daysRemaining<0?"error":metadata.daysRemaining<30?"warning":"success"}>剩余 {metadata.daysRemaining} 天</Tag></Space>},
      {key:"fingerprint",label:"SHA-256",children:<Typography.Text copyable className="settings-fingerprint">{metadata.sha256}</Typography.Text>}]} />}
    <Space wrap className="space-top"><Button icon={<SafetyCertificateOutlined/>} loading={busy==="check"} disabled={Boolean(busy)||!state||!hasPair} onClick={()=>void submit(false)}>校验证书</Button>
      <Button type="primary" loading={busy==="save"} disabled={Boolean(busy)||!state||(enabled&&!hasPair)} onClick={save}>保存并应用 HTTPS</Button>
      <Hint iconOnly label="公网访问说明" title="本机证书与握手校验不代表公网可达，请另行配置域名解析、访问控制和端口转发。"/></Space>
  </section>;
}
