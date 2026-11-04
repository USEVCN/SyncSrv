import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RunFilesDrawer } from "../modules/jobs/RunFilesDrawer";
import { api } from "../platform/http";
vi.mock("../platform/http",async()=>({...await vi.importActual("../platform/http"),api:vi.fn()}));
const base={run:{id:"run1",status:"COMPLETED",phase:"COMPLETE",progress:{planComplete:true,processedBytes:10,totalBytes:10,processedFiles:1,totalFiles:1,entries:101,elapsedMillis:2000,estimatedTotalMillis:2000,sampledAt:new Date().toISOString()}},coverage:"SAVED",total:101,page:1,pageSize:100,entries:[{id:0,path:"photos/one.jpg",kind:"WRITE",status:"COMPLETED",processedBytes:10,size:10}]};
const mount=()=>render(<ConfigProvider><App><RunFilesDrawer runId="run1" onClose={()=>{}}/></App></ConfigProvider>);
afterEach(()=>vi.clearAllMocks());
describe("file execution list",()=>{
  it("does not infer file success from a completed batch when the file never reported completion",async()=>{
    vi.mocked(api).mockResolvedValue({...base,total:1,entries:[{...base.entries[0],status:"RUNNING",processedBytes:5,phase:"TRANSFERRING"}]});
    mount();await screen.findByText("photos/one.jpg");
    expect(within(screen.getByRole("table")).getByText("待确认")).toBeInTheDocument();
    expect(screen.getByText("未收到该文件的最终状态")).toBeInTheDocument();
    expect(within(screen.getByRole("table")).queryByText("已完成")).not.toBeInTheDocument();
  });
  it("shows simultaneous source upload and checksum rows",async()=>{
    vi.mocked(api).mockResolvedValue({...base,coverage:"SOURCE",total:3,run:{...base.run,status:"RUNNING",phase:"UPLOADING_B"},entries:[
      {id:0,path:"upload.bin",kind:"WRITE",status:"RUNNING",processedBytes:40,size:100,phase:"UPLOADING_B",phaseBytes:0,phaseTotal:0},
      {id:1,path:"hash.bin",kind:"WRITE",status:"RUNNING",processedBytes:0,size:200,phase:"HASHING_SOURCE",phaseBytes:80,phaseTotal:200},
      {id:2,path:"later.bin",kind:"WRITE",status:"QUEUED",processedBytes:0,size:300,phase:""},
    ]});
    render(<ConfigProvider><App><RunFilesDrawer runId="source1" source onClose={()=>{}}/></App></ConfigProvider>);
    await screen.findByText("upload.bin");
    expect(within(screen.getByRole("table")).getAllByText("执行中")).toHaveLength(2);
    expect(screen.getByText(/计算源文件校验值/)).toHaveTextContent("校验 80 B / 200 B");
    expect(screen.getByText("待执行")).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith("/source-transfers/source1/files?page=1");
  });
  it("loads the selected execution and requests pages from the server",async()=>{
    vi.mocked(api).mockResolvedValueOnce(base).mockResolvedValueOnce({...base,page:2,entries:[{...base.entries[0],id:100,path:"photos/last.jpg"}]});
    mount();expect(await screen.findByText("photos/one.jpg")).toBeInTheDocument();
    expect(screen.getByText("文件处理完成")).toBeInTheDocument();
    expect(screen.queryByText("备份与恢复节点已提交")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle("2"));
    expect(await screen.findByText("photos/last.jpg")).toBeInTheDocument();
    expect(api).toHaveBeenLastCalledWith("/runs/run1/files?page=2");
    expect(screen.queryByText("photos/one.jpg")).not.toBeInTheDocument();
  });
  it("polls an active execution and stops after it finishes",async()=>{
    vi.mocked(api).mockResolvedValueOnce({...base,run:{...base.run,status:"RUNNING"}}).mockResolvedValue(base);
    mount();await screen.findByText("photos/one.jpg");
    await waitFor(()=>expect(api).toHaveBeenCalledTimes(2),{timeout:2500});
    await new Promise(resolve=>setTimeout(resolve,1200));
    expect(api).toHaveBeenCalledTimes(2);
  });
  it("does not fabricate a file list for legacy executions",async()=>{
    vi.mocked(api).mockResolvedValue({...base,run:{id:"old",status:"COMPLETED",phase:"COMPLETE"},coverage:"LEGACY",entries:[],total:0});
    mount();expect(await screen.findByRole("button",{name:"历史执行未采集完整清单"})).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
  });
});
