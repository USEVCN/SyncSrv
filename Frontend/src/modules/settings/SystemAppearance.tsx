import { ConfigProvider } from "antd";
import { useEffect, useMemo, type ReactNode } from "react";
import { useStateData } from "../../app/State";
import { createSystemTheme } from "../../shared/theme";
import { defaultUiSettings, typographyLevels } from "./settingsModel";
export function SystemAppearance({children}:{children:ReactNode}) {
  const {data}=useStateData();
  const value=data?.uiSettings||defaultUiSettings;
  const signature=JSON.stringify(value.typography);
  const theme=useMemo(()=>createSystemTheme(value.typography),[signature]);
  useEffect(()=>{
    const root=document.documentElement,previous=typographyLevels.map(level=>root.style.getPropertyValue(`--ui-font-${level.key}`));
    typographyLevels.forEach(level=>root.style.setProperty(`--ui-font-${level.key}`,`${value.typography[level.key]}px`));
    return ()=>typographyLevels.forEach((level,i)=>previous[i]?root.style.setProperty(`--ui-font-${level.key}`,previous[i]):root.style.removeProperty(`--ui-font-${level.key}`));
  },[signature]);
  return <ConfigProvider theme={theme}>{children}</ConfigProvider>;
}
