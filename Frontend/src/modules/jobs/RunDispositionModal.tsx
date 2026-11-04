import { ResizableModal as Modal } from "../../shared/ResizableModal";
import { Button, Collapse, Descriptions, Form, Input, Space, Spin } from "antd";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { Hint } from "../../shared/Hint";
import { time } from "../../shared/format";
import type { Run } from "../../shared/types";

type Fields = { reason:string; plan:string; method:string };
export type Disposition = Fields & {runId:string; revision:number; actor:string; savedAt:string};
export type DispositionView = {originalError:string; latest:Disposition|null; history:Disposition[]; nextBefore:number};
const empty:Fields = {reason:"",plan:"",method:""};
const fields = [{key:"reason",label:"失败原因",hint:"填写排查后的原因，可后续补充；系统原始报错另行保留。"},
  {key:"plan",label:"处置方案",hint:"填写计划采用的解决方案和执行安排。"},
  {key:"method",label:"处置方法",hint:"填写实际采用的操作步骤、命令或处理方式。"}] as const;

export function RunDispositionModal({run,onClose}:{run:Run;onClose:()=>void}) {
  const [form]=Form.useForm<Fields>();
  const [view,setView]=useState<DispositionView>();
  const [loading,setLoading]=useState(true);
  const [saving,setSaving]=useState(false);
  const [loadingHistory,setLoadingHistory]=useState(false);
  const [error,setError]=useState("");
  const [saved,setSaved]=useState(false);
  const [reload,setReload]=useState(0);
  useEffect(()=>{
    let alive=true;
    setLoading(true);
    api<DispositionView>(`/runs/${run.id}/disposition`).then(next=>{
      if(alive){setView(next);form.setFieldsValue(next.latest || empty);setError("");setSaved(false);}
    }).catch(e=>{if(alive)setError(e.message);}).finally(()=>{if(alive)setLoading(false);});
    return ()=>{alive=false;};
  },[run.id,form,reload]);
  const save=async(values:Fields)=>{
    if(!view || saving)return;
    setSaving(true);setError("");setSaved(false);
    try {
      const next=await api<Disposition>(`/runs/${run.id}/disposition`,"PUT",{...values,revision:view.latest?.revision || 0});
      setView({...view,latest:next,history:[next,...view.history.filter(v=>v.revision!==next.revision)]});
      form.setFieldsValue(next);setSaved(true);
    } catch(e) {setError((e as Error).message);}
    finally {setSaving(false);}
  };
  const more=async()=>{
    if(!view)return;
    setLoadingHistory(true);
    try {
      const next=await api<DispositionView>(`/runs/${run.id}/disposition?before=${view.nextBefore}`);
      setView(current=>current ? {...current,history:[...current.history,...next.history],nextBefore:next.nextBefore} : current);
    } catch(e){setError((e as Error).message);}
    finally{setLoadingHistory(false);}
  };
  return <Modal title="失败处置" open width={760} onCancel={onClose} maskClosable={false}
    cancelButtonProps={{disabled:saving}} closable={!saving}
    footer={<Space><Button onClick={onClose} disabled={saving}>关闭</Button>
      <Button type="primary" loading={saving} disabled={loading || !view} onClick={()=>form.submit()}>保存处置</Button></Space>}>
    <Descriptions size="small" column={1} items={[
      {key:"id",label:"执行编号",children:<span className="mono">{run.id}</span>},
      {key:"error",label:"系统原始报错",children:<span style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{view?.originalError || run.error || "未记录错误详情"}</span>},
    ]} />
    {loading ? <Spin /> : <Form form={form} layout="vertical" onFinish={save} onValuesChange={()=>setSaved(false)} className="space-top" disabled={saving || !view}>
      {fields.map(({key,label,hint})=><Form.Item key={key} name={key}
        label={<Space>{label}<Hint iconOnly label={`${label}说明`} title={hint} /></Space>}>
        <Input.TextArea aria-label={label} maxLength={4000} showCount autoSize={{minRows:3,maxRows:8}} />
      </Form.Item>)}
    </Form>}
    {error && <Space wrap className="space-top"><span role="alert">{error}</span>
      <Button disabled={saving || loading} onClick={()=>setReload(v=>v+1)}>重新加载已保存内容</Button></Space>}
    {saved && <div role="status" className="space-top">已保存，处置内容及修改历史已记录到审计。</div>}
    {view?.latest && <>
      <p className="muted">最近填写：{view.latest.actor} · {time(view.latest.savedAt)} · 第 {view.latest.revision} 版</p>
      <Collapse items={[{key:"history",label:`修改历史 · ${view.latest.revision} 个版本`,children:<>
        <Collapse items={view.history.map(entry=>({key:entry.revision,label:`第 ${entry.revision} 版 · ${entry.actor} · ${time(entry.savedAt)}`,
          children:<Descriptions size="small" column={1} items={fields.map(({key,label})=>({key,label,
            children:<span style={{whiteSpace:"pre-wrap",overflowWrap:"anywhere"}}>{entry[key] || "未填写"}</span>}))} />}))} />
        {!!view.nextBefore && <Button className="space-top" loading={loadingHistory} onClick={more}>加载更早版本</Button>}
      </>}]} />
    </>}
  </Modal>;
}
