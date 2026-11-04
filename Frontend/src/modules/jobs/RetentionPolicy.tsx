import {Button,Collapse,Form,Input,InputNumber,Space,Switch} from "antd";
import {useEffect,useState} from "react";
import {api} from "../../platform/http";
import {Hint} from "../../shared/Hint";
import {useAction} from "../../shared/ui";
type Policy={jobId:string;months:number;enabled:boolean;autoExecute:boolean;confirmation:string;lastMonth:string;planId:string;lastRun:string;error:string;revision:number};
export function RetentionPolicy({jobId}:{jobId:string}){
 return <Collapse className="space-bottom" items={[{key:"policy",label:"历史版本保留策略",children:<RetentionEditor jobId={jobId}/>} ]}/>;
}
function RetentionEditor({jobId}:{jobId:string}){
 const [form]=Form.useForm();const action=useAction();const [policy,setPolicy]=useState<Policy>(),[error,setError]=useState("");const auto=Form.useWatch("autoExecute",form);
 useEffect(()=>{let alive=true;api<Policy>(`/jobs/${jobId}/archive/retention`).then(v=>{if(alive){setPolicy(v);form.setFieldsValue(v);}}).catch(e=>{if(alive)setError((e as Error).message);});return()=>{alive=false};},[jobId,form]);
 return <>
 {error&&<Hint type="error" title={error}/>}{policy?.error&&<Hint type="warning" title={policy.error}/>}
 <Form form={form} layout="vertical" onFinish={v=>action.run(async()=>{const saved=await api<Policy>(`/jobs/${jobId}/archive/retention`,"PUT",{...v,revision:policy?.revision||0});setPolicy(saved);form.setFieldsValue(saved);},"保留策略已保存")}>
 <Space wrap align="start"><Form.Item name="months" label="保留月数" rules={[{required:true}]}><InputNumber min={1} max={1200}/></Form.Item>
 <Form.Item name="enabled" label="启用月度策略" valuePropName="checked"><Switch/></Form.Item>
 <Form.Item name="autoExecute" label="自动执行清理" valuePropName="checked"><Switch/></Form.Item></Space>
 {auto&&<Form.Item name="confirmation" label="确认自动清理" rules={[{required:true,pattern:/^自动清理C的过期历史版本$/,message:"请输入完整确认语句"}]}><Input placeholder="自动清理C的过期历史版本"/></Form.Item>}
 <Space><Button type="primary" htmlType="submit" loading={action.loading} disabled={!policy}>保存策略</Button><Hint label="策略说明" title="默认仅生成月度预览。自动执行需额外确认；仍保留当前版本、最新完整恢复点和未完成恢复的引用。过期版本删除后无法恢复。"/></Space>
 </Form></>;
}
