import { useEffect, useState } from "react";

const STORAGE_KEY="syncsrv:sidebar-collapsed";
export function useSidebarCollapse() {
  const [collapsed,setCollapsed]=useState(()=>{
    try { return localStorage.getItem(STORAGE_KEY)==="true"; }
    catch { return false; }
  });
  useEffect(()=>{
    try { localStorage.setItem(STORAGE_KEY,String(collapsed)); }
    catch { /* Navigation remains usable when local storage is unavailable. */ }
  },[collapsed]);
  return [collapsed,setCollapsed] as const;
}
