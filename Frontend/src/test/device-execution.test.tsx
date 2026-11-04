import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { EndpointForm } from "../modules/storage/EndpointForm";
import { AgentInstall } from "../modules/agents/AgentInstall";
import { ExecutionProgress } from "../modules/jobs/ExecutionProgress";
import type { Run } from "../shared/types";

describe("device storage execution",()=>{
 it("binds an existing storage connection without duplicating the device installation",async()=>{
  const calls:unknown[]=[];
  vi.spyOn(globalThis,"fetch").mockImplementation(async(_url,options)=>{
   if(options?.method==="POST"){calls.push(JSON.parse(options.body as string));return new Response(JSON.stringify({success:true,data:{}}));}
   return new Response(JSON.stringify({success:true,data:[{id:"nas",name:"Synology",platform:"linux",status:"APPROVED"}]}));
  });
  render(<ConfigProvider><App><EndpointForm value={{id:"B",name:"Backup B",protocol:"SMB",root:"downloads/Backup/B",host:"nas",agentId:"nas",agentRoot:"/volume1/downloads/Backup/B",allowRelay:true}} onClose={()=>{}} onSaved={async()=>{}}/></App></ConfigProvider>);
  expect(await screen.findByText("该设备上的本地目录")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("switch"));
  fireEvent.click(screen.getByRole("button",{name:"保存连接"}));
  await waitFor(()=>expect(calls).toHaveLength(1));
  expect(calls[0]).toMatchObject({agentId:"nas",agentRoot:"/volume1/downloads/Backup/B",allowRelay:false,root:"downloads/Backup/B"});
 });
 it("offers the Synology SPK with native installation instructions",async()=>{
  vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({success:true,data:{version:"0.11.0-dev",assets:[{platform:"synology",arch:"amd64",packageName:"syncsrv-agent-synology-amd64.spk",packageSize:1024,packageSha256:"a".repeat(64)}]}})));
  render(<ConfigProvider><App><AgentInstall/></App></ConfigProvider>);
  fireEvent.mouseDown(screen.getByRole("combobox",{name:"目标设备系统"}));
  fireEvent.click(await screen.findByText("群晖 DSM"));
  expect(await screen.findByRole("link",{name:"下载完整安装包"})).toHaveAttribute("href","/agent/releases/0.11.0-dev/syncsrv-agent-synology-amd64.spk");
  expect(screen.getByText("套件中心手动安装")).toBeInTheDocument();
 });
 it("distinguishes local processing from network transmission",()=>{
  render(<ExecutionProgress run={{status:"RUNNING",phase:"ARCHIVING",executionMode:"AGENT_LOCAL",executionNote:"同设备复制与校验",progress:{planComplete:true,processedBytes:10,totalBytes:20,processedFiles:0,totalFiles:1,entries:1,elapsedMillis:1000,transferBytesPerSecond:1048576,verificationBytesPerSecond:0,sampledAt:new Date().toISOString()}} as Run}/>);
  expect(screen.getByRole("img",{name:"设备处理 1.0 MiB/s"})).toBeInTheDocument();
  expect(screen.getByText(/设备本地执行 · 同设备复制与校验/)).toBeInTheDocument();
 });
});
