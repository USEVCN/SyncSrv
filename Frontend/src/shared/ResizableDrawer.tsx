import { Drawer, type DrawerProps } from "antd";
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode } from "react";

type Props = Omit<DrawerProps, "size" | "width" | "placement" | "resizable" | "maxSize"> & {
  storageKey: string;
  initialWidth?: number;
};
const keyFor = (key: string) => `syncsrv.drawer.width.${key}`;

function ResizeAccess({size,minimum,maximum,onResize,children}:{size:number;minimum:number;maximum:number;onResize:(value:number)=>void;children:ReactNode}) {
  const anchor=useRef<HTMLSpanElement>(null);
  useLayoutEffect(()=>{
    const handle=anchor.current?.closest(".ant-drawer-content-wrapper")?.querySelector<HTMLElement>(".ant-drawer-resizable-dragger");
    if(!handle)return;
    for(const [name,value]of Object.entries({role:"separator",tabindex:"0","aria-label":"调整侧栏尺寸","aria-orientation":"vertical","aria-valuemin":minimum,"aria-valuemax":maximum,"aria-valuenow":Math.round(size)}))handle.setAttribute(name,String(value));
    const key=(event:KeyboardEvent)=>{
      if(!["ArrowLeft","ArrowRight","Home","End"].includes(event.key))return;
      event.preventDefault();event.stopPropagation();
      const delta=(event.key==="ArrowLeft"?1:-1)*(event.shiftKey?64:16);
      onResize(event.key==="Home"?minimum:event.key==="End"?maximum:size+delta);
    };
    handle.addEventListener("keydown",key);
    return()=>handle.removeEventListener("keydown",key);
  },[size,minimum,maximum,onResize]);
  return <><span ref={anchor} hidden/>{children}</>;
}

export function ResizableDrawer({ storageKey, initialWidth = 900, rootClassName = "", drawerRender, open, ...props }: Props) {
  const [preferred, setPreferred] = useState(() => {
    try {
      const saved = Number(localStorage.getItem(keyFor(storageKey)));
      return Number.isFinite(saved) && saved >= 320 ? saved : initialWidth;
    } catch { return initialWidth; }
  });
  const [viewport, setViewport] = useState(window.innerWidth);
  const latest = useRef(preferred), dragging=useRef(false);
  const finish=useCallback(()=>{
    if(dragging.current)document.dispatchEvent(new MouseEvent("mouseup",{bubbles:true}));
  },[]);
  useEffect(() => {
    const resize = () => setViewport(window.innerWidth);
    window.addEventListener("resize", resize);window.addEventListener("blur",finish);
    return () => { finish();window.removeEventListener("resize", resize);window.removeEventListener("blur",finish); };
  }, [finish]);
  useEffect(()=>{if(!open)finish();},[open,finish]);
  const max = Math.max(1, viewport - (viewport > 600 ? 24 : 0));
  const min=Math.min(320,max);
  const clamp=useCallback((value:number)=>Math.min(max,Math.max(min,value)),[min,max]);
  const persist=useCallback(()=>{try { localStorage.setItem(keyFor(storageKey), String(latest.current)); } catch { /* Storage may be disabled. */ }},[storageKey]);
  const resize=useCallback((value:number)=>{latest.current=clamp(value);setPreferred(latest.current);},[clamp]);
  const keyboardResize=useCallback((value:number)=>{resize(value);persist();},[resize,persist]);
  return <Drawer {...props} open={open} placement="right" size={clamp(preferred)} maxSize={max}
    rootClassName={`syncsrv-drawer ${rootClassName}`} resizable={viewport > 600 && {
      onResize:resize,onResizeStart:()=>{dragging.current=true;},
      onResizeEnd:()=>{dragging.current=false;persist();},
    }} drawerRender={node=><ResizeAccess size={clamp(preferred)} minimum={min} maximum={max} onResize={keyboardResize}>
      {drawerRender?drawerRender(node):node}
    </ResizeAccess>}/>;
}
