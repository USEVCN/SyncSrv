import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { StateProvider } from "../app/State";
import { RestoreModal } from "../modules/restore/RestoreModal";
import type { Snapshot } from "../shared/types";

describe("remote snapshot reconstruction",()=>{
  it("submits an SMB connection and relative directory without requiring a Mac mount",async()=>{
    const posted: unknown[]=[];
    vi.spyOn(globalThis,"fetch").mockImplementation(async(input,init)=>{
      const url=String(input);
      if(init?.method==="POST")posted.push(JSON.parse(String(init.body)));
      const data=url.endsWith("/state")?{endpoints:[{id:"D",name:"群晖恢复",protocol:"SMB",root:"SyncSrvTest/D"}],jobs:[]}:
        url.endsWith("/plan")?{snapshot:{id:"s1"},items:[],excludedCurrentPaths:[]}:{};
      return new Response(JSON.stringify({success:true,data}));
    });
    const restored=vi.fn();
    const snapshot={id:"s1",sequence:3,files:0,totalBytes:0,completedAt:"2026-09-18T00:00:00Z"} as Snapshot;
    render(<ConfigProvider><App><StateProvider><RestoreModal snapshot={snapshot} onClose={()=>{}} onRestored={restored}/></StateProvider></App></ConfigProvider>);
    const select=await screen.findByRole("combobox");
    fireEvent.mouseDown(select);
    fireEvent.click(await screen.findByText("群晖恢复 · SMB · SyncSrvTest/D"));
    fireEvent.change(screen.getByLabelText("恢复子目录（相对于所选连接根目录）"),{target:{value:"snapshot-20260918"}});
    fireEvent.click(screen.getByRole("button",{name:/恢\s*复/}));
    await waitFor(()=>expect(restored).toHaveBeenCalledOnce());
    expect(posted).toEqual([{snapshotId:"s1",endpointId:"D",directory:"snapshot-20260918"}]);
  });
});
