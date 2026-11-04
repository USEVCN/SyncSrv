import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { JobChanges } from "../modules/jobs/JobChangesDrawer";
import { mergeJobChanges } from "../modules/jobs/changeRows";
import type { Discovery, Job, SourceChange } from "../shared/types";

const job = { id: "job", name: "文档备份", enabled: true, sourceMode: "AGENT", discoveryMode: "EVENTS", intervalSeconds: 30 } as Job;
const entry = (path = "report.docx", status = "WAITING", jobId = "job") => ({
  id: `notice:${path}`, jobId, path, status, phase: "DEBOUNCING",
  receivedAt: "2026-09-18T00:00:00Z", updatedAt: "2026-09-18T00:00:00Z",
} as SourceChange);
const discovery = (error = "", jobId = "job"): Discovery => ({ jobId, mode: "EVENTS", pendingCount: 1,
  checkpoint: null, oldestPendingAt: "2026-09-18T00:00:00Z", pending: [{
    id: "pending", path: "report.docx", reason: "CHANGE", firstSeen: "2026-09-18T00:00:00Z",
    updatedAt: "2026-09-18T00:00:00Z", merged: 3, error,
  }] });
const response = (data: unknown) => new Response(JSON.stringify({ success: true, data }));
const renderJob = () => render(<ConfigProvider theme={{token:{motion:false}}}><App><JobChanges job={job} /></App></ConfigProvider>);

it("does not promise automatic execution for a revoked source device",()=>{
  const waiting={...entry(),phase:"AUTHORIZATION_REQUIRED"};
  expect(mergeJobChanges(job,[waiting])[0].phase).toBe("设备授权已撤销，等待重新授权");
  expect(mergeJobChanges({...job,enabled:false},[waiting])[0].phase).toBe("设备授权已撤销，等待重新授权");
});

it("keeps one path row across source and center stages, scoped to its parent job", () => {
  const merged = mergeJobChanges(job, [entry(), entry("secret.docx", "RUNNING", "other")], discovery());
  expect(merged).toHaveLength(1);
  expect(merged[0].source).toBeDefined();
  expect(merged[0].pending?.merged).toBe(3);
  expect(merged[0].status).toBe("WAITING");
  expect(mergeJobChanges(job, [], discovery("", "other"))).toEqual([]);
});
it("never shows completion while a center retry remains, and retains newer source changes", () => {
  expect(mergeJobChanges(job, [entry("report.docx", "COMPLETED")], discovery("error"))[0].status).toBe("FAILED");
  expect(mergeJobChanges(job, [entry("report.docx", "COMPLETED")], discovery())[0].status).toBe("READY");
  const newer = mergeJobChanges(job, [entry()], discovery("older retry"))[0];
  expect(newer.status).toBe("WAITING");
  expect(newer.pending?.error).toBe("older retry");
});
it("does not swallow child paths into a root notification", () => {
  expect(mergeJobChanges(job, [entry(""), entry("folder"), entry("folder/report.docx")])).toHaveLength(3);
});
it("shows waiting before execution exists, then refreshes upload state without duplicating paths", async () => {
  const notice = entry();
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async input =>
    String(input).endsWith("/discovery") ? response([discovery(), discovery("", "other")]) :
      response({ entries: [notice, entry("secret.docx", "RUNNING", "other")], total: 2 }));
  renderJob();
  expect(await screen.findByText("report.docx")).toBeInTheDocument();
  expect(screen.queryByText("secret.docx")).not.toBeInTheDocument();
  expect(screen.getByText("连续 30 秒无新变化后执行")).toBeInTheDocument();
  notice.status = "READY"; notice.phase = "WAITING_COMMIT";
  expect(await screen.findByText("等待服务端归档与提交", {}, { timeout: 2500 })).toBeInTheDocument();
  expect(screen.getAllByText("report.docx")).toHaveLength(1);
  expect(fetcher.mock.calls.some(([url]) => String(url).endsWith("/source-changes?jobId=job"))).toBe(true);
});
it("keeps legacy center changes visible when source status endpoint is unavailable", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input => {
    if (!String(input).endsWith("/discovery")) throw new Error("Unavailable");
    return response([discovery("SMB disconnected")]);
  });
  renderJob();
  expect(await screen.findByText("report.docx")).toBeInTheDocument();
  expect(screen.getByText("服务端处理失败，等待重试")).toBeInTheDocument();
  const hint = screen.getByRole("button", { name: "状态更新异常" });
  expect(screen.queryByText(/源端变化状态暂不可用/)).not.toBeInTheDocument();
  fireEvent.mouseEnter(hint);
  await waitFor(() => expect(screen.getByText(/源端变化状态暂不可用/)).toBeVisible());
});
