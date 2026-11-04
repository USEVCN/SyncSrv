import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { AgentsPage } from "../modules/agents/AgentsPage";
import { backlogState, BacklogDetails } from "../modules/agents/AgentBacklog";
import type { Agent, Job } from "../shared/types";
vi.mock("../app/State", () => ({ useStateData: () => ({ data: { jobs: [{ id: "job", name: "文档备份" }] } }) }));
const agent = () => ({ id: "agent", name: "文件服务器", status: "APPROVED", lastSeen: new Date().toISOString(), pendingEvents: 0, watchingJobs: [],
  backlog: { knownPaths: 0, waiting: 0, running: 0, overdue: 0, oldestWaitingAt: "" } } as unknown as Agent);
const response = (data: unknown) => new Response(JSON.stringify({ success: true, data }));
it("shows required reauthorization rather than an automatic retry for revoked devices",async()=>{
  const a={...agent(),status:"REVOKED",pendingEvents:1};
  vi.spyOn(globalThis,"fetch").mockResolvedValue(response({entries:[{change:{id:"path",jobId:"job",path:"report.docx",status:"WAITING",phase:"AUTHORIZATION_REQUIRED",receivedAt:new Date().toISOString()},jobName:"文档备份",enabled:true,overdue:false}],total:1,page:1,pageSize:50,summary:{knownPaths:1,waiting:1,running:0,overdue:0}}));
  render(<ConfigProvider><App><BacklogDetails agent={a} jobs={[]}/></App></ConfigProvider>);
  expect(await screen.findByText("等待重新授权")).toBeInTheDocument();
  expect(screen.getByText("设备授权已撤销，等待重新授权")).toBeInTheDocument();
});
it("uses red for pending, yellow for overdue, green for empty and never green for stale data", () => {
  const a = agent();
  expect(backlogState(a).color).toBe("success");
  a.pendingEvents = 2; expect(backlogState(a).color).toBe("error");
  a.backlog!.overdue = 1; expect(backlogState(a).color).toBe("gold");
  expect(backlogState(a, true).color).toBe("default");
  a.pendingEvents = 0; a.backlog!.overdue = 0; a.lastSeen = "2020-01-01T00:00:00Z";
  expect(backlogState(a).label).toContain("待确认");
});
it("opens pending paths from the device badge, scoped to that device", async () => {
  const a = agent(); a.pendingEvents = 1;
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    const url = String(input);
    if (url.endsWith("/agents")) return response([a]);
    if (url.includes("/backlog?")) return response({ entries: [{ change: { id: "path", jobId: "job", path: "report.docx", status: "WAITING", phase: "DEBOUNCING", receivedAt: new Date().toISOString() }, jobName: "文档备份", enabled: true, eligibleAt: "", overdue: false }], total: 1, page: 1, pageSize: 50, summary: { knownPaths: 1, waiting: 1, running: 0, overdue: 0 } });
    return response([]);
  });
  render(<ConfigProvider><App><AgentsPage /></App></ConfigProvider>);
  fireEvent.click(await screen.findByRole("button", { name: /文件服务器的源端积压/ }));
  expect(await screen.findByText("report.docx")).toBeInTheDocument();
  expect(screen.getByText("文档备份")).toBeInTheDocument();
  expect(fetcher.mock.calls.some(([url]) => String(url) === "/api/agents/agent/backlog?page=1")).toBe(true);
});
it("keeps unreported pending work visible and does not claim an empty queue on API failure", async () => {
  vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("服务不可用"));
  const a = agent(); a.pendingEvents = 3;
  render(<ConfigProvider><App><BacklogDetails agent={a} jobs={[] as Job[]} /></App></ConfigProvider>);
  expect(await screen.findByText("积压明细暂不可用")).toBeInTheDocument();
  expect(screen.queryByText("暂无源端待执行任务")).not.toBeInTheDocument();
});
