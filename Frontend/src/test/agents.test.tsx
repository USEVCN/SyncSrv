import { App, ConfigProvider } from "antd";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AgentsPage } from "../modules/agents/AgentsPage";
vi.mock("../app/State", () => ({
  useStateData: () => ({ data: { jobs: [] } }),
}));
describe("source device management", () => {
  it("does not restore stale device state when an older request finishes after a manual refresh", async () => {
    let first!: (value: Response) => void;
    let agentReads=0;
    const device={id:"a",name:"最新设备",platform:"windows",arch:"amd64",status:"APPROVED",lastSeen:new Date().toISOString(),version:"0.14.0-dev",watchingJobs:[],error:"",pendingEvents:0,configVersion:1,appliedVersion:1};
    vi.spyOn(globalThis,"fetch").mockImplementation(async input=>{
      if(String(input).endsWith("/agents")) {
        if(++agentReads===1)return new Promise<Response>(resolve=>{first=resolve;});
        return new Response(JSON.stringify({success:true,data:[device]}));
      }
      return new Response(JSON.stringify({success:true,data:{version:"0.14.0-dev",assets:[]}}));
    });
    render(<ConfigProvider theme={{token:{motion:false}}}><App><AgentsPage/></App></ConfigProvider>);
    fireEvent.click(screen.getByRole("button",{name:/reload 刷新/}));
    await screen.findByText("最新设备");
    await act(async()=>first(new Response(JSON.stringify({success:true,data:[]}))));
    expect(screen.getByText("最新设备")).toBeInTheDocument();
  });
  it("marks connection status unknown when the last successful device sample cannot be refreshed",async()=>{
    let agentReads=0;
    const device={id:"a",name:"在线设备",platform:"windows",arch:"amd64",status:"APPROVED",lastSeen:new Date().toISOString(),version:"0.14.0-dev",watchingJobs:[],error:"",pendingEvents:0,configVersion:1,appliedVersion:1};
    vi.spyOn(globalThis,"fetch").mockImplementation(async input=>{
      if(String(input).endsWith("/agents")) {
        if(++agentReads>1)throw new Error("offline");
        return new Response(JSON.stringify({success:true,data:[device]}));
      }
      return new Response(JSON.stringify({success:true,data:{version:"0.14.0-dev",assets:[]}}));
    });
    render(<ConfigProvider theme={{token:{motion:false}}}><App><AgentsPage/></App></ConfigProvider>);
    await screen.findByText("在线");
    fireEvent.click(screen.getByRole("button",{name:/reload 刷新/}));
    await screen.findByText("连接状态待确认");
    expect(screen.queryByText("在线")).not.toBeInTheDocument();
  });
  it("requires explicit authorization and does not display stale telemetry as live", async () => {
    const pending = {
      id: "a".repeat(32),
      name: "生产文件服务器",
      platform: "linux",
      arch: "amd64",
      version: "test",
      publicKey: "public",
      status: "PENDING",
      createdAt: "2026-09-16T00:00:00Z",
      lastSeen: "2020-01-01T00:00:00Z",
      capability: "inotify",
      error: "",
      configVersion: 0,
      appliedVersion: 0,
      pendingEvents: 3,
      databaseBytes: 0,
      memoryBytes: 0,
      cpuPercent: 0,
      watchingJobs: [],
    };
    const fetcher = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async (input) => {
        const path = String(input);
        if (path.endsWith("/authorization"))
          return new Response(
            JSON.stringify({
              success: true,
              data: { ...pending, status: "APPROVED" },
            }),
          );
        return new Response(
          JSON.stringify({
            success: true,
            data: path.endsWith("/agents") ? [pending] : [],
          }),
        );
      });
    render(
      <ConfigProvider theme={{token:{motion:false}}}>
        <App>
          <AgentsPage />
        </App>
      </ConfigProvider>,
    );
    expect(await screen.findByText("生产文件服务器")).toBeInTheDocument();
    expect(screen.getByText("数据已过期")).toBeInTheDocument();
    expect(screen.queryByText("变化处理")).not.toBeInTheDocument();
    expect(fetcher.mock.calls.some(([url]) => String(url).endsWith("/discovery"))).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: /授\s*权$/ }));
    await waitFor(() =>
      expect(
        fetcher.mock.calls.some(
          ([url, init]) =>
            String(url).endsWith("/authorization") &&
            init?.body === JSON.stringify({ approved: true }),
        ),
      ).toBe(true),
    );
  });
  it("shows a refresh failure instead of declaring zero devices healthy", async () => {
    vi.spyOn(globalThis, "fetch").mockRejectedValue(
      new Error("中心暂时不可达"),
    );
    render(
      <ConfigProvider theme={{token:{motion:false}}}>
        <App>
          <AgentsPage />
        </App>
      </ConfigProvider>,
    );
    expect(await screen.findByText("设备状态刷新失败")).toBeInTheDocument();
    expect(screen.queryByText("中心暂时不可达")).not.toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByRole("button", { name: "设备状态刷新失败" }));
    await waitFor(() => expect(screen.getByText("中心暂时不可达")).toBeVisible());
  });
});
