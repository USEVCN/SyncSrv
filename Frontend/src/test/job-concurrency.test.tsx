import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { JobForm } from "../modules/jobs/JobForm";
import { api } from "../platform/http";
const visibleRole = (role: Parameters<typeof screen.getByRole>[0], options?: Parameters<typeof screen.getByRole>[1]) => {
  const element = screen.getByRole(role, { ...options, hidden: true });
  expect(element).toBeVisible();
  return element;
};
const findVisibleRole = async (role: Parameters<typeof screen.findByRole>[0], options?: Parameters<typeof screen.findByRole>[1], wait?: Parameters<typeof screen.findByRole>[2]) => {
  const element = await screen.findByRole(role, { ...options, hidden: true }, wait);
  await waitFor(() => expect(element).toBeVisible());
  return element;
};

vi.mock("../platform/http",()=>({api:vi.fn()}));
it("saves per-task source concurrency without changing the task bandwidth",async()=>{
 vi.mocked(api).mockResolvedValue([]);
 const saved=vi.fn(async()=>{});
 render(<ConfigProvider><App><JobForm value={{id:"j",adaptiveTransfers:false,name:"资料",sourceMode:"AGENT",discoveryMode:"EVENTS",agentId:"a",agentRoot:"E:/Source",targetId:"b",archiveId:"c",intervalSeconds:5,reconcileSeconds:86400,bandwidthMiB:20,enabled:true}} endpoints={[]} onClose={()=>{}} onSaved={saved}/></App></ConfigProvider>);
 const input=await findVisibleRole("spinbutton",{name:/并发文件数/},{timeout:4000});
 expect(input).toHaveValue("4");
 fireEvent.change(input,{target:{value:"8"}});
 const center=visibleRole("spinbutton",{name:/中心文件并发数/});
 expect(center).toHaveValue("4");
 fireEvent.change(center,{target:{value:"6"}});
 fireEvent.click(visibleRole("button",{name:"保存任务"}));
 await waitFor(()=>expect(saved).toHaveBeenCalledOnce());
 expect(api).toHaveBeenCalledWith("/jobs","POST",expect.objectContaining({id:"j",transferThreads:8,archiveThreads:6,bandwidthMiB:20}));
});
it("saves automatic concurrency with its own cap and preserves the manual setting",async()=>{
 vi.mocked(api).mockResolvedValue([]);
 const saved=vi.fn(async()=>{});
 render(<ConfigProvider><App><JobForm value={{id:"j",name:"资料",sourceMode:"AGENT",discoveryMode:"EVENTS",agentId:"a",agentRoot:"E:/Source",targetId:"b",archiveId:"c",intervalSeconds:5,reconcileSeconds:86400,bandwidthMiB:20,transferThreads:6,adaptiveTransfers:true,maxTransferThreads:16,enabled:true}} endpoints={[]} onClose={()=>{}} onSaved={saved}/></App></ConfigProvider>);
 const input=await findVisibleRole("spinbutton",{name:"自动并发上限"});
 expect(input).toHaveValue("16");
 expect(screen.queryByRole("spinbutton",{name:/并发文件数/})).not.toBeInTheDocument();
 fireEvent.change(input,{target:{value:"12"}});
 fireEvent.click(visibleRole("button",{name:"保存任务"}));
 await waitFor(()=>expect(saved).toHaveBeenCalledOnce());
 expect(api).toHaveBeenCalledWith("/jobs","POST",expect.objectContaining({adaptiveTransfers:true,maxTransferThreads:12,transferThreads:6,bandwidthMiB:20}));
});
