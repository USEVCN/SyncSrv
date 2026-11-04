import { App, ConfigProvider } from "antd";
import { render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { RunReportDrawer, type RunReport } from "../modules/jobs/RunReportDrawer";

const run={id:"run",jobId:"job",kind:"SYNC",snapshotId:"",target:"/backup",actor:"test",status:"PAUSED",phase:"ARCHIVING",bytes:1024,
  verifiedBytes:2048,done:0,total:1,currentPath:"file.iso",startedAt:"2026-09-18T12:00:00Z",
  actualStartedAt:"2026-09-18T12:00:01Z",completedAt:"2026-09-18T12:00:02Z",error:"paused"};
const wrap=()=>render(<ConfigProvider><App><RunReportDrawer runId="run" onClose={vi.fn()} /></App></ConfigProvider>);
it("marks old records as unmeasured instead of fabricating performance statistics",async()=>{
  const report:RunReport={run,generatedAt:run.completedAt,coverage:"LEGACY",wallMillis:1000,queueMillis:1000};
  vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({success:true,data:report})));
  wrap();
  expect(await screen.findByText("旧执行未采集性能指标")).toBeInTheDocument();
  expect(screen.getAllByText("未采集").length).toBeGreaterThan(5);
  expect(screen.queryByText("阶段耗时与 I/O")).not.toBeInTheDocument();
});
it("shows saved stage timings, resumed data and slow file operations",async()=>{
  const report:RunReport={run,generatedAt:run.completedAt,coverage:"MEASURED",wallMillis:1000,queueMillis:1000,metrics:{
    serverCopyBytes:8*1024**2,serverCopyMillis:100,elapsedMillis:1000,workerCpuMillis:15,peakHeapBytes:1024,lastProgressAt:run.completedAt,
    scannedEntries:5,resumedBytes:8*1024**2,resumeCount:1,checkpointRestarts:0,checksumCount:2,
    recoveredOperations:1,cleanupFiles:0,cleanupBytes:0,notes:[],
    stages:[{phase:"ARCHIVING",millis:1000,readMillis:700,writeMillis:200,throttleMillis:0,
      transferBytes:1024,verificationBytes:2048,writtenBytes:1024,readCalls:32,writeCalls:1}],
    slowestOperations:[{path:"file.iso",kind:"DELETE",status:"PAUSED",millis:900,
      transferBytes:1024,verificationBytes:2048,writtenBytes:1024,error:"paused"}]
  }};
  const fetcher=vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({success:true,data:report})));
  wrap();
  expect(await screen.findByText("阶段耗时与 I/O")).toBeInTheDocument();
  expect(screen.getByText(/8.0 MiB · 100 ms · SMB 存储端执行/)).toBeInTheDocument();
  expect(screen.getByText("700 ms")).toBeInTheDocument();
  expect(screen.getByText("1 次 · 8.0 MiB")).toBeInTheDocument();
  expect(screen.getByText("最耗时的文件操作 · 最多 20 项")).toBeInTheDocument();
  await waitFor(()=>expect(screen.getByRole("button",{name:/导出 JSON/})).toBeEnabled());
  expect(fetcher).toHaveBeenCalledWith("/api/runs/run/report",expect.any(Object));
});
