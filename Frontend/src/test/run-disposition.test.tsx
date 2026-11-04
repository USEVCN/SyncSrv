import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { RunDispositionModal, type Disposition } from "../modules/jobs/RunDispositionModal";
import type { Run } from "../shared/types";

const run={id:"failed",status:"FAILED",error:"SMB connection reset"} as Run;
const entry:Disposition={runId:"failed",revision:1,reason:"NAS 连接中断",plan:"恢复网络",method:"",actor:"admin",savedAt:"2026-09-18T12:00:00Z"};
const response=(data:unknown,status=200)=>new Response(JSON.stringify({success:status===200,data,message:status===409 ? "处置记录已被更新" : undefined}),{status});
const wrap=()=>render(<ConfigProvider><App><RunDispositionModal run={run} onClose={vi.fn()} /></App></ConfigProvider>);

it("loads previous notes, saves all three fields and records the new revision",async()=>{
  const fetcher=vi.spyOn(globalThis,"fetch").mockImplementation(async(_url,init)=>init?.method==="PUT"
    ? response({...entry,revision:2,method:"修复交换机端口"})
    : response({originalError:run.error,latest:entry,history:[entry],nextBefore:0}));
  wrap();
  const method=await screen.findByRole("textbox",{name:"处置方法"});
  expect(screen.getByRole("textbox",{name:"失败原因"})).toHaveValue(entry.reason);
  expect(screen.getByText(run.error)).toBeInTheDocument();
  fireEvent.change(method,{target:{value:"修复交换机端口"}});
  fireEvent.click(screen.getByRole("button",{name:"保存处置"}));
  expect(await screen.findByRole("status")).toHaveTextContent("已保存");
  expect(fetcher).toHaveBeenCalledWith("/api/runs/failed/disposition",expect.objectContaining({method:"PUT",
    body:JSON.stringify({reason:entry.reason,plan:entry.plan,method:"修复交换机端口",revision:1})}));
  expect(screen.getByText("修改历史 · 2 个版本")).toBeInTheDocument();
});

it("preserves unsaved text when another editor has saved a newer version",async()=>{
  vi.spyOn(globalThis,"fetch").mockImplementation(async(_url,init)=>init?.method==="PUT" ? response(null,409)
    : response({originalError:run.error,latest:entry,history:[entry],nextBefore:0}));
  wrap();
  const reason=await screen.findByRole("textbox",{name:"失败原因"});
  fireEvent.change(reason,{target:{value:"尚未保存的调查结论"}});
  fireEvent.click(screen.getByRole("button",{name:"保存处置"}));
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("处置记录已被更新"));
  expect(reason).toHaveValue("尚未保存的调查结论");
  expect(screen.queryByRole("status")).not.toBeInTheDocument();
});
