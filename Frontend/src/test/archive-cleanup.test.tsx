import { App, ConfigProvider } from "antd";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { ArchiveCleanupDrawer } from "../modules/jobs/ArchiveCleanupDrawer";
import { archivePlanStatus, cutoffMonth, type ArchivePlan } from "../modules/jobs/archiveCleanup";
import { ActivityDetail } from "../modules/jobs/ActivityDetail";
import { runKindLabel } from "../shared/format";
import type { AppState, Job, Run, Snapshot } from "../shared/types";

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

vi.mock("../shared/ResizableModal", async () => {
  const { App, Modal } = await import("antd");
  return { ResizableModal: Modal, useResizableApp: App.useApp };
});
// Business state/race tests use the actual AntD table; column editors and geometry
// are exercised separately by table-presentation.test.tsx.
vi.mock("../shared/DataTable", async () => {
  const { Table } = await import("antd");
  return { DataTable: ({ configurable: _configurable, preferenceKey: _preferenceKey, ...props }:
    import("antd").TableProps<object> & { configurable?: boolean; preferenceKey?: string }) => <Table {...props} /> };
});
vi.mock("../shared/ResizableDrawer",()=>({ResizableDrawer:({children,title,open,extra}:React.PropsWithChildren<{title:string;open:boolean;extra:React.ReactNode}>)=>open ? <section aria-label={title}>{extra}{children}</section> : null}));
const job={id:"job",name:"文档备份",archiveId:"C"} as Job;
const snapshot=(id:string,completedAt:string)=>({id,jobId:"job",sequence:1,completedAt,files:1,totalBytes:1024} as Snapshot);
const plan:ArchivePlan={id:"plan-one",jobId:"job",beforeMonth:"2026-06",cutoff:"2026-05-31T16:00:00Z",
  createdAt:"2026-09-19T01:00:00Z",actor:"admin",state:"READY",estimatedBytes:1024,
  expired:[snapshot("old","2026-04-01T01:00:00Z")],retained:[{snapshot:snapshot("current","2026-09-01T01:00:00Z"),reasons:["最新恢复节点"]}],
  items:[{id:"item",kind:"VERSION",endpointId:"C",location:"job/versions/old.txt.__v1.__replaced",size:1024,hash:"hash",versionIds:["v1"]}],
};
const run={id:"run-one",jobId:"job",kind:"ARCHIVE_CLEANUP",target:plan.id,status:"QUEUED",startedAt:"2026-09-19T01:00:00Z"} as Run;
const response=(data:unknown)=>new Response(JSON.stringify({success:true,data}));
const executeCalls=(fetcher:{mock:{calls:[unknown,RequestInit?][]}})=>fetcher.mock.calls.filter(([url])=>String(url).endsWith("/archive/execute"));
const wrap=(element:React.ReactNode)=>render(<ConfigProvider theme={{token:{motion:false}}}><App>{element}</App></ConfigProvider>);
function setup(options:{plans?:ArchivePlan[];runs?:Run[];initialPlanId?:string}={}) {
  const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>{
    const url=String(input);
    return response(url.endsWith("/archive/plans") ? options.plans || [] : url.endsWith("/archive/execute") ? run : plan);
  });
  const queued=vi.fn(async()=>{});
  wrap(<ArchiveCleanupDrawer job={job} runs={options.runs || []} initialPlanId={options.initialPlanId} onClose={()=>{}} onQueued={queued} />);
  return {fetcher,queued};
}
async function preview() {
  fireEvent.change(screen.getByLabelText("清理此月之前"),{target:{value:"2026-06"}});
  fireEvent.click(visibleRole("button",{name:"预览清理"}));
  await findVisibleRole("button",{name:"确认清理范围"});
}
describe("archive cleanup",()=>{
  it("uses calendar month starts in Beijing time, including year and timezone boundaries",()=>{
    expect(cutoffMonth(3,new Date("2026-09-19T00:00:00Z"))).toBe("2026-06");
    expect(cutoffMonth(6,new Date("2026-02-10T00:00:00Z"))).toBe("2025-08");
    expect(cutoffMonth(12,new Date("2026-12-31T16:00:00Z"))).toBe("2026-01");
  });
  it("only reads history on open; preview and cancellation never execute a deletion",async()=>{
    const {fetcher}=setup();
    await waitFor(()=>expect(fetcher).toHaveBeenCalled());
    expect(fetcher.mock.calls.every(([,init])=>init?.method==="GET")).toBe(true);
    await preview();
    const call=fetcher.mock.calls.find(([url])=>String(url).endsWith("/archive/preview"));
    expect(JSON.parse(call![1]!.body as string)).toEqual({beforeMonth:"2026-06",keepSnapshotIds:[]});
    expect(screen.getByText("移除恢复节点")).toBeVisible();
    expect(executeCalls(fetcher)).toHaveLength(0);
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    const dialog=await findVisibleRole("dialog");
    expect(dialog).toHaveTextContent("2026/6/1 00:00:00");
    expect(dialog).toHaveTextContent("这些节点将不能再用于恢复");
    fireEvent.click(within(dialog).getByRole("button",{name:"取 消"}));
    expect(executeCalls(fetcher)).toHaveLength(0);
  });
  it("submits only the reviewed plan after explicit confirmation, then locks while queued",async()=>{
    const {fetcher,queued}=setup();
    await preview();
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    const dialog=await findVisibleRole("dialog");
    fireEvent.click(within(dialog).getByRole("button",{name:"确认清理"}));
    await waitFor(()=>expect(queued).toHaveBeenCalledOnce());
    expect(executeCalls(fetcher)).toHaveLength(1);
    expect(JSON.parse(executeCalls(fetcher)[0][1]!.body as string)).toEqual({planId:"plan-one",confirmed:true});
    expect(visibleRole("button",{name:"确认清理范围"})).toBeDisabled();
  });
  it("invalidates a preview when the month changes and ignores its late response",async()=>{
    let resolve!: (response:Response)=>void;
    const pending=new Promise<Response>(done=>{resolve=done;});
    const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>String(input).endsWith("/archive/preview") ? pending : response([]));
    wrap(<ArchiveCleanupDrawer job={job} runs={[]} onClose={()=>{}} onQueued={async()=>{}} />);
    fireEvent.click(visibleRole("button",{name:"预览清理"}));
    fireEvent.change(screen.getByLabelText("清理此月之前"),{target:{value:"2026-01"}});
    await act(async()=>{resolve(response(plan));await pending;});
    expect(screen.queryByRole("button",{name:"确认清理范围"})).not.toBeInTheDocument();
    expect(screen.getByLabelText("清理此月之前")).toHaveValue("2026-01");
    expect(executeCalls(fetcher)).toHaveLength(0);
  });
  it("blocks future months and never guesses that an unconfirmed cleaning run is stopped",()=>{
    const {fetcher}=setup();
    fireEvent.change(screen.getByLabelText("清理此月之前"),{target:{value:"9999-01"}});
    expect(visibleRole("button",{name:"预览清理"})).toBeDisabled();
    expect(executeCalls(fetcher)).toHaveLength(0);
    expect(archivePlanStatus({...plan,state:"CLEANING"},[]).active).toBe(true);
  });
  it("loads an interrupted plan by identity and confirms only its remaining scope",async()=>{
    const interrupted={...run,status:"INTERRUPTED"};
    const partial={...plan,state:"CLEANING",runId:run.id,completedFiles:1,deletedBytes:100} as ArchivePlan;
    const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>response(String(input).endsWith("/archive/plans") ? [] : partial));
    wrap(<ArchiveCleanupDrawer job={job} runs={[interrupted]} initialPlanId={plan.id} onClose={()=>{}} onQueued={async()=>{}} />);
    fireEvent.click(await findVisibleRole("button",{name:"继续整理"}));
    const dialog=await findVisibleRole("dialog");
    expect(dialog).toHaveTextContent("仅继续此计划剩余项目，不会扩大清理范围");
    expect(fetcher.mock.calls.some(([url])=>String(url).endsWith("/archive/plans/plan-one"))).toBe(true);
    expect(executeCalls(fetcher)).toHaveLength(0);
  });
  it("labels cleanup as archive maintenance and opens its plan instead of calling restore",()=>{
    const failed={...run,status:"FAILED",error:"连接中断",phase:"ARCHIVE_REMOVE"};
    const open=vi.fn();
    const fetcher=vi.spyOn(globalThis,"fetch");
    const data={jobs:[job],endpoints:[],runs:[failed]} as unknown as AppState;
    wrap(<ActivityDetail row={{id:"run:run-one",jobId:job.id,status:"FAILED",phase:failed.phase,at:failed.startedAt,run:failed,changes:[]}}
      data={data} onClose={()=>{}} refresh={async()=>{}} onArchiveCleanup={open} />);
    expect(runKindLabel.ARCHIVE_CLEANUP).toBe("归档整理");
    expect(screen.getByText("清理归档文件")).toBeVisible();
    fireEvent.click(visibleRole("button",{name:"查看整理计划 / 继续整理"}));
    expect(open).toHaveBeenCalledWith(failed);
    expect(screen.queryByRole("button",{name:"重新执行 / 续传"})).not.toBeInTheDocument();
    expect(fetcher).not.toHaveBeenCalled();
  });
});

function deferred() {
  let resolve!: (value:Response)=>void;
  const promise=new Promise<Response>(done=>{resolve=done;});
  return {promise,resolve};
}
const renderDrawer=(runs:Run[]=[],value=job,onQueued=async()=>{})=><ConfigProvider theme={{token:{motion:false}}}><App>
  <ArchiveCleanupDrawer job={value} runs={runs} onClose={()=>{}} onQueued={onQueued} />
</App></ConfigProvider>;
describe("archive cleanup race protection",()=>{
  it("ignores an older history response after a manual refresh",async()=>{
    const earlier=deferred();let loads=0;
    vi.spyOn(globalThis,"fetch").mockImplementation(async()=>++loads===1 ? earlier.promise : response([{...plan,state:"COMPLETED"}]));
    render(renderDrawer());
    fireEvent.click(visibleRole("button",{name:"刷 新"}));
    await screen.findByText("已完成");
    await act(async()=>{earlier.resolve(response([plan]));await earlier.promise;});
    expect(screen.getByText("已完成")).toBeVisible();
    expect(screen.queryByText("待确认")).not.toBeInTheDocument();
  });
  it("allows only one confirmation dialog even after repeated opening clicks",async()=>{
    setup();await preview();
    const button=visibleRole("button",{name:"确认清理范围"});
    fireEvent.click(button);fireEvent.click(button);
    expect(await screen.findAllByRole("dialog")).toHaveLength(1);
  });
  it("destroys the old confirmation when its drawer is removed",async()=>{
    vi.spyOn(globalThis,"fetch").mockImplementation(async input=>response(String(input).endsWith("/archive/plans") ? [] : plan));
    const view=render(renderDrawer());await preview();
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    await findVisibleRole("dialog");
    view.rerender(<ConfigProvider theme={{token:{motion:false}}}><App /></ConfigProvider>);
    await waitFor(()=>expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  });
  it("never applies the previous job's history after a task switch",async()=>{
    const earlier=deferred();
    const nextJob={...job,id:"other",name:"另一个任务"};
    vi.spyOn(globalThis,"fetch").mockImplementation(async input=>String(input).includes("/jobs/job/") ? earlier.promise : response([{...plan,id:"other-plan",jobId:"other",beforeMonth:"2026-03"}]));
    const view=render(renderDrawer());
    view.rerender(renderDrawer([],nextJob));
    await screen.findByText("2026-03");
    await act(async()=>{earlier.resolve(response([plan]));await earlier.promise;});
    expect(screen.getByText("2026-03")).toBeVisible();
    expect(screen.queryByText("2026-06")).not.toBeInTheDocument();
  });
  it("rechecks whether the job became busy while the confirmation was open",async()=>{
    const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>response(String(input).endsWith("/archive/plans") ? [] : String(input).endsWith("/archive/execute") ? run : plan));
    const view=render(renderDrawer());await preview();
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    const dialog=await findVisibleRole("dialog");
    view.rerender(renderDrawer([{...run,id:"new-sync",kind:"SYNC",status:"RUNNING"}]));
    await act(async()=>{fireEvent.click(within(dialog).getByRole("button",{name:"确认清理"}));});
    expect(executeCalls(fetcher)).toHaveLength(0);
  });
  it("does not report an accepted submission as failed when refreshing the page fails",async()=>{
    const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>response(String(input).endsWith("/archive/plans") ? [] : String(input).endsWith("/archive/execute") ? run : plan));
    render(renderDrawer([],job,async()=>{throw new Error("状态刷新断线");}));await preview();
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    fireEvent.click(within(await findVisibleRole("dialog")).getByRole("button",{name:"确认清理"}));
    await screen.findByText("归档整理已加入执行队列");
    expect(await findVisibleRole("button",{name:"状态刷新失败"})).toBeVisible();
    expect(screen.queryByRole("button",{name:"整理操作异常"})).not.toBeInTheDocument();
    expect(executeCalls(fetcher)).toHaveLength(1);
  });
  it("prevents blind resubmission when the execute response is lost",async()=>{
    vi.spyOn(globalThis,"fetch").mockImplementation(async input=>{
      if(String(input).endsWith("/archive/execute"))throw new TypeError("connection lost");
      return response(String(input).endsWith("/archive/plans") ? [] : plan);
    });
    render(renderDrawer());await preview();
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    fireEvent.click(within(await findVisibleRole("dialog")).getByRole("button",{name:"确认清理"}));
    await findVisibleRole("button",{name:"提交结果待确认"});
    expect(visibleRole("button",{name:"确认清理范围"})).toBeDisabled();
  });
});

describe("archive cleanup authoritative state",()=>{
  it("uses the server run status and blocks an unknown newer run instead of reusing an older failure",()=>{
    expect(archivePlanStatus({...plan,runId:"new-run",runStatus:"QUEUED"},[])).toMatchObject({label:"待执行",active:true});
    expect(archivePlanStatus({...plan,runId:"new-run"},[{...run,status:"FAILED"}])).toMatchObject({label:"执行状态待确认",active:true});
    expect(archivePlanStatus({...plan,runId:run.id,runStatus:"FAILED"},[{...run,status:"RUNNING"}])).toMatchObject({label:"失败",active:false});
  });
  it("refetches the selected historical plan so a stale ready row cannot restart a completed cleanup",async()=>{
    const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async input=>response(String(input).endsWith("/archive/plans") ? [plan] : {...plan,state:"COMPLETED"}));
    render(renderDrawer());
    fireEvent.click(await findVisibleRole("button",{name:"查看计划"}));
    expect(await findVisibleRole("button",{name:"整理已完成"})).toBeDisabled();
    expect(fetcher.mock.calls.some(([url])=>String(url).endsWith("/archive/plans/plan-one"))).toBe(true);
    expect(executeCalls(fetcher)).toHaveLength(0);
  });
  it("permits inspecting a failed plan after the server confirms the formerly running attempt stopped",async()=>{
    const partial={...plan,state:"FAILED",runId:run.id,runStatus:"FAILED"};
    vi.spyOn(globalThis,"fetch").mockImplementation(async input=>response(String(input).endsWith("/archive/plans") ? [partial] : partial));
    render(renderDrawer([{...run,status:"RUNNING"}]));
    fireEvent.click(await findVisibleRole("button",{name:"查看计划"}));
    expect(await findVisibleRole("button",{name:"继续整理"})).toBeEnabled();
  });
  it("closes the old confirmation immediately when the selected month changes",async()=>{
    const {fetcher}=setup();await preview();
    fireEvent.click(visibleRole("button",{name:"确认清理范围"}));
    await findVisibleRole("dialog");
    fireEvent.change(screen.getByLabelText("清理此月之前"),{target:{value:"2026-01"}});
    await waitFor(()=>expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    expect(executeCalls(fetcher)).toHaveLength(0);
  });
});

it("does not roll back a freshly loaded completed plan when older history finally arrives",async()=>{
  const oldHistory=deferred();
  vi.spyOn(globalThis,"fetch").mockImplementation(async input=>String(input).endsWith("/archive/plans") ? oldHistory.promise : response({...plan,state:"COMPLETED"}));
  wrap(<ArchiveCleanupDrawer job={job} runs={[]} initialPlanId={plan.id} onClose={()=>{}} onQueued={async()=>{}} />);
  expect(await findVisibleRole("button",{name:"整理已完成"})).toBeDisabled();
  await act(async()=>{oldHistory.resolve(response([plan]));await oldHistory.promise;});
  expect(visibleRole("button",{name:"整理已完成"})).toBeDisabled();
});

