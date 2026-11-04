import { App, ConfigProvider, Table, type TableProps } from "antd";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { ArchiveCleanupDrawer } from "../modules/jobs/ArchiveCleanupDrawer";
import type { ArchivePlan } from "../modules/jobs/archiveCleanup";
import type { Job } from "../shared/types";

// Keep summary/detail race coverage separate from column geometry tests.
vi.mock("../shared/ResizableDrawer", () => ({ ResizableDrawer:
  ({ children, title, open, extra }: React.PropsWithChildren<{ title: string; open: boolean; extra: React.ReactNode }>) =>
    open ? <section aria-label={title}>{extra}{children}</section> : null }));
vi.mock("../shared/DataTable", () => ({ DataTable:
  ({ configurable: _configurable, preferenceKey: _preferenceKey, ...props }:
    TableProps<object> & { configurable?: boolean; preferenceKey?: string }) => <Table {...props} /> }));
const job = { id: "job", name: "文档备份", archiveId: "C" } as Job;
const plan: ArchivePlan = {
  id: "plan-one", jobId: job.id, beforeMonth: "2026-06", cutoff: "2026-05-31T16:00:00Z",
  createdAt: "2026-09-19T01:00:00Z", actor: "admin", state: "READY", estimatedBytes: 1024,
  expired: [], retained: [], items: [{ id: "item", kind: "VERSION", endpointId: "C",
    location: "job/versions/old.txt.__v1.__replaced", size: 1024, hash: "hash", versionIds: ["v1"] }],
};
const response = (data: unknown) => new Response(JSON.stringify({ success: true, data }));
const executeCalls = (fetcher: { mock: { calls: [unknown, RequestInit?][] } }) =>
  fetcher.mock.calls.filter(([url]) => String(url).endsWith("/archive/execute"));
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>(done => { resolve = done; });
  return { promise, resolve };
}
const visibleRole = (role: Parameters<typeof screen.getByRole>[0], options?: Parameters<typeof screen.getByRole>[1]) => {
  const element = screen.getByRole(role, { ...options, hidden: true });
  expect(element).toBeVisible(); return element;
};
const findVisibleRole = async (role: Parameters<typeof screen.findByRole>[0], options?: Parameters<typeof screen.findByRole>[1]) => {
  const element = await screen.findByRole(role, { ...options, hidden: true });
  await waitFor(() => expect(element).toBeVisible()); return element;
};
const renderDrawer = () => <ConfigProvider theme={{ token: { motion: false } }}><App>
  <ArchiveCleanupDrawer job={job} runs={[]} onClose={() => {}} onQueued={async () => {}} />
</App></ConfigProvider>;

it("keeps full preview details when history polling returns omitted-detail summaries",async()=>{
  const full=deferred();let reads=0;
  const summary={...plan,detailsOmitted:true,items:[],expired:[],retained:[]};
  const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>{
    if(String(input).endsWith("/archive/plans"))return response([summary]);
    reads++;return reads===1 ? full.promise : response(plan);
  });
  render(renderDrawer());
  fireEvent.click(await findVisibleRole("button",{hidden:true,name:"查看计划"}));
  expect(screen.queryByRole("button",{hidden:true,name:"确认清理范围"})).not.toBeInTheDocument();
  expect(executeCalls(fetcher)).toHaveLength(0);
  await act(async()=>{full.resolve(response(plan));await full.promise;});
  fireEvent.click(await findVisibleRole("tab",{hidden:true,name:"候选文件 (1)"}));
  expect(screen.getByText(plan.items[0].location)).toBeVisible();
  fireEvent.click(visibleRole("button",{hidden:true,name:"刷 新"}));
  await waitFor(()=>expect(reads).toBe(2));
  expect(screen.getByText(plan.items[0].location)).toBeVisible();
  expect(visibleRole("button",{hidden:true,name:"确认清理范围"})).toBeEnabled();
  expect(executeCalls(fetcher)).toHaveLength(0);
});
