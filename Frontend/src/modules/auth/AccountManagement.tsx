import { DataTable } from "../../shared/ui";
import {Button,Form,Input,Select,Switch} from "antd";
import {useEffect,useState} from "react";
import {api} from "../../platform/http";
import {useAction} from "../../shared/ui";
import {Hint} from "../../shared/Hint";
type Account={username:string;role:string;disabled:boolean};
export function AccountManagement(){
 const [accounts,setAccounts]=useState<Account[]>([]);const [form]=Form.useForm();const action=useAction();
 const [error,setError]=useState("");
 const load=()=>api<Account[]>("/accounts").then(v=>{setAccounts(v);setError("");}).catch(e=>setError((e as Error).message));
 useEffect(()=>{void load();},[]);
 return <>{error&&<Hint type="error" title={error}/>}<DataTable rowKey="username" size="small" pagination={false} dataSource={accounts} columns={[
  {title:"账号",dataIndex:"username"},{title:"角色",dataIndex:"role",render:(r:string)=>({ADMIN:"管理员",OPERATOR:"操作员",AUDITOR:"审计员"}[r]||r)},
  {title:"状态",dataIndex:"disabled",render:(v:boolean)=>v?"已停用":"启用"},
  {title:"操作",render:(_,a)=><Button type="link" onClick={()=>form.setFieldsValue({...a,password:""})}>编辑</Button>}
 ]}/><Form form={form} layout="vertical" initialValues={{role:"OPERATOR",disabled:false}} onFinish={v=>action.run(async()=>{await api("/accounts","PUT",v);form.resetFields();await load();},"账号已保存")}>
 <Form.Item name="username" label="账号" rules={[{required:true,pattern:/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/}]}><Input autoComplete="off"/></Form.Item>
 <Form.Item name="password" label="设置密码（编辑时留空则保留）"><Input.Password autoComplete="new-password"/></Form.Item>
 <Form.Item name="role" label="角色"><Select options={[{value:"ADMIN",label:"管理员"},{value:"OPERATOR",label:"操作员"},{value:"AUDITOR",label:"审计员"}]}/></Form.Item>
 <Form.Item name="disabled" label="停用" valuePropName="checked"><Switch/></Form.Item>
 <Button htmlType="submit" type="primary" loading={action.loading}>保存账号</Button></Form></>;
}
