import { InfoCircleFilled } from "@ant-design/icons";
import { Tooltip, theme } from "antd";
import type { ReactNode } from "react";

// Same page-title help control as LE; interactive help continues to use Hint.
export function InfoHint({label,content}:{label:string;content:ReactNode}) {
  const {token}=theme.useToken();
  return <Tooltip title={<span style={{maxWidth:420}}>{content}</span>} placement="top" trigger={["hover","focus"]} mouseEnterDelay={.2}>
    <button type="button" className="info-hint" aria-label={label} onClick={e=>e.stopPropagation()}
      style={{alignItems:"center",appearance:"none",background:"transparent",border:0,color:token.colorInfo,cursor:"pointer",display:"inline-flex",height:18,justifyContent:"center",lineHeight:1,padding:0,width:18,fontSize:"min(.9em, 14px)"}}>
      <InfoCircleFilled />
    </button>
  </Tooltip>;
}
