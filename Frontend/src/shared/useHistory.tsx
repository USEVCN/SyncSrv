import {Button} from "antd";
import {useState} from "react";
import {api} from "../platform/http";
import {Hint} from "./Hint";
export function useHistory<T extends {id:string}>(kind:"runs"|"snapshots",base:T[]){
 const [extra,setExtra]=useState<T[]>([]),[offset,setOffset]=useState(200),[done,setDone]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState("");
 const rows=[...new Map([...extra,...base].map(row=>[row.id,row])).values()];
 const load=async()=>{setBusy(true);try{const next=await api<T[]>(`/history/${kind}?limit=200&offset=${offset}`);setExtra(old=>[...new Map([...old,...next].map(row=>[row.id,row])).values()]);setOffset(old=>old+next.length);setDone(next.length<200);setError("");}catch(e){setError((e as Error).message);}finally{setBusy(false);}};
 return {rows,control:<>{!done&&base.length>=200&&<Button loading={busy} onClick={()=>void load()}>加载更早记录</Button>}{error&&<Hint type="error" title={error}/>}</>};
}
