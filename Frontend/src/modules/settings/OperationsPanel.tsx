import {Button,Card,Descriptions,Space,Tag} from "antd";
import {useEffect,useState} from "react";
import {api,ApiError} from "../../platform/http";
import {Hint} from "../../shared/Hint";
import {bytes,time} from "../../shared/format";
type Operations={recoveryPending:boolean;auditAnchor:string;anchorError:string;lastAnchor:string;freeBytes:number;minimumFreeBytes:number;alertConfigured:boolean;monitorError:string;upgradeMode:string};
export function OperationsPanel(){
 const [data,setData]=useState<Operations>(),[error,setError]=useState("");
 const [unsupported,setUnsupported]=useState(false);
 const load=()=>api<Operations>("/operations").then(v=>{setData(v);setError("");setUnsupported(false);}).catch(e=>{
  const missing=e instanceof ApiError&&e.status===404;setUnsupported(missing);setError(missing?"":(e as Error).message);
 });
 useEffect(()=>{void load();},[]);
 return <Card className="space-top" title="上线与运维检查" extra={<Button onClick={()=>void load()}>刷新</Button>}>
 {error&&<Hint title={error} type="error"/>}
 {unsupported&&<Hint title="当前服务端不支持运维检查" description="升级服务端后可查看此面板的运维信息。" type="info"/>}
 {data&&<Descriptions column={{xs:1,md:2}} items={[
  {key:"anchor",label:"独立审计锚点",children:<Space><Tag color={data.auditAnchor==="VERIFIED"?"success":"warning"}>{({VERIFIED:"已验证",FAILED:"异常",NOT_CONFIGURED:"未配置"}as Record<string,string>)[data.auditAnchor]}</Tag><Hint iconOnly label="审计锚点说明" title={data.anchorError||"收据由独立服务器签名；生产启动要求成功核对外部链头，检测到回退或分叉后停止写入。"}/></Space>},
  {key:"at",label:"最近锚定",children:time(data.lastAnchor)},
  {key:"disk",label:"中心可用空间",children:<Space>{bytes(data.freeBytes)}{data.freeBytes<data.minimumFreeBytes&&<Tag color="error">空间不足</Tag>}</Space>},
  {key:"alert",label:"Webhook 告警",children:data.alertConfigured?"已配置":"未配置"},
  {key:"upgrade",label:"中心在线升级",children:<Hint label="签名下载 · 手动触发" title="在 Linux 中手动运行安装目录的 update.sh check 或 update，使用已配置并验签的 RelsHub 更新源。保留旧版本，启动与数据库健康检查失败时回滚；recover 用于断电后恢复。"/>},
  {key:"recovery",label:"灾备状态",children:data.recoveryPending?<Hint type="warning" label="等待备份库与归档库完整校验" title="任务已暂停。停止中心后运行 current/bin/verify-state-files.sh，完整校验通过后再启用任务；处置和校验性能报告会保留。"/>:"正常"},
  {key:"error",label:"监控状态",children:data.monitorError?<Hint type="error" title={data.monitorError}/>:"正常"}
 ]}/>}</Card>;
}
