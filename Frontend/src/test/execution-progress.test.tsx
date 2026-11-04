import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ExecutionProgress } from "../modules/jobs/ExecutionProgress";
import { runProgress } from "../shared/runProgress";
import type { Run } from "../shared/types";
const run={status:"RUNNING",phase:"TRANSFERRING",phaseBytes:100,phaseTotal:100,progress:{
  planComplete:true,processedBytes:1024,totalBytes:4096,processedFiles:1,totalFiles:4,entries:4,
  elapsedMillis:4000,estimatedTotalMillis:16000,transferBytesPerSecond:1024,verificationBytesPerSecond:0,sampledAt:new Date().toISOString(),
}} as Run;
describe("whole execution progress",()=>{
  beforeEach(()=>{
    vi.useFakeTimers({toFake:["Date"]});
    vi.setSystemTime(new Date("2026-11-04T00:00:00Z"));
    run.progress!.sampledAt=new Date().toISOString();
  });
  afterEach(()=>vi.useRealTimers());
  it("shows actual center workers separately from the configured cap and marks stale samples",()=>{
    const progress={...run.progress!,activeFiles:3,peakActiveFiles:4,concurrencyLimit:4,sampledAt:new Date().toISOString()};
    const {rerender}=render(<ExecutionProgress run={{...run,progress}}/>);
    expect(screen.getByText("并行文件 3 / 4")).toBeInTheDocument();
    rerender(<ExecutionProgress run={{...run,progress}} stale/>);
    expect(screen.getByText("并行文件 — / 4")).toBeInTheDocument();
    rerender(<ExecutionProgress run={{...run,status:"COMPLETED",progress}}/>);
    expect(screen.getByText("并行峰值 4 / 4")).toBeInTheDocument();
  });

  it("distinguishes parallel checks from file transfers without inflating file totals",()=>{
    const progress={...run.progress!,activeFiles:0,concurrencyLimit:4,activeChecks:3,peakChecks:4,sampledAt:new Date().toISOString()};
    const {rerender}=render(<ExecutionProgress run={{...run,phase:"HASHING",progress}}/>);
    expect(screen.getByText("并行文件 0 / 4")).toBeInTheDocument();
    expect(screen.getByText("并行校验 3 / 4")).toBeInTheDocument();
    expect(screen.getByText("1 / 4")).toBeInTheDocument();
    rerender(<ExecutionProgress run={{...run,status:"COMPLETED",progress:{...progress,activeChecks:0}}}/>);
    expect(screen.getByText("校验峰值 4")).toBeInTheDocument();
  });

  it("keeps the list compact and shows updated statistics on hover",async()=>{
    const {rerender}=render(<ExecutionProgress run={run} compact/>);
    expect(screen.queryByText("1 / 4")).not.toBeInTheDocument();
    expect(screen.getByText("传输 1.0 KiB/s")).toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByRole("button",{name:"查看执行进度详情"}));
    await waitFor(()=>expect(screen.getByText("1 / 4")).toBeVisible());
    rerender(<ExecutionProgress run={{...run,progress:{...run.progress!,processedFiles:2}}} compact/>);
    expect(screen.getByText("2 / 4")).toBeVisible();
    fireEvent.keyDown(screen.getByRole("button",{name:"查看执行进度详情"}),{key:"Escape"});
    expect(screen.getByRole("button",{name:"查看执行进度详情"})).toHaveAttribute("aria-expanded","false");
  });
  it("shows totals and sampled throughput instead of the current file percentage",()=>{
    render(<ExecutionProgress run={run}/>);
    expect(runProgress(run)).toBe(25);
    expect(screen.getByText("1.0 KiB / 4.0 KiB")).toBeInTheDocument();
    expect(screen.getByText("1 / 4")).toBeInTheDocument();
    expect(screen.getByText("00:00:04")).toBeInTheDocument();
    expect(screen.getByText("约 00:00:16")).toBeInTheDocument();
    expect(screen.getByRole("img",{name:"传输 1.0 KiB/s"})).toBeInTheDocument();
  });
  it("does not present stale throughput or estimates as live",()=>{
    render(<ExecutionProgress run={run} stale/>);
    expect(screen.getByRole("img",{name:"传输速度 —"})).toBeInTheDocument();
    expect(screen.queryByText(/约 00:00:16/)).not.toBeInTheDocument();
  });
  it("uses unknown totals before the plan and caps unfinished backups at 99",()=>{
    render(<ExecutionProgress run={{...run,progress:{...run.progress!,planComplete:false}}}/>);
    expect(screen.getAllByText("统计中")).toHaveLength(2);
    expect(screen.getByText("估算中")).toBeInTheDocument();
    expect(runProgress({...run,progress:{...run.progress!,processedBytes:4096}})).toBe(99);
    expect(runProgress({...run,status:"COMPLETED"})).toBe(100);
  });
  it("accepts an omitted estimate from the non_null API response",()=>{
    const progress={...run.progress!};
    delete progress.estimatedTotalMillis;
    render(<ExecutionProgress run={{...run,progress}}/>);
    expect(screen.getByText("估算中")).toBeInTheDocument();
    expect(screen.queryByText(/NaN/)).not.toBeInTheDocument();
  });
  it.each([null,NaN,Infinity,-1])("does not format an unavailable or invalid estimate (%s)",estimate=>{
    render(<ExecutionProgress run={{...run,progress:{...run.progress!,estimatedTotalMillis:estimate}}}/>);
    expect(screen.getByText("估算中")).toBeInTheDocument();
  });
  it("shows verification speed while reading and switches back when transmission resumes",()=>{
    const verifying={...run,phase:"HASHING",progress:{...run.progress!,transferBytesPerSecond:0,verificationBytesPerSecond:1048576}};
    const {rerender}=render(<ExecutionProgress run={verifying} compact/>);
    expect(screen.getByText("校验 1.0 MiB/s")).toBeInTheDocument();
    expect(screen.queryByText(/传输 0 B/)).not.toBeInTheDocument();
    rerender(<ExecutionProgress run={run} compact/>);
    expect(screen.getByText("传输 1.0 KiB/s")).toBeInTheDocument();
    expect(screen.queryByText("校验 1.0 MiB/s")).not.toBeInTheDocument();
  });
  it("switches the instrument to verification and clears live speed after completion",()=>{
    const verifying={...run,phase:"HASHING",progress:{...run.progress!,transferBytesPerSecond:0,verificationBytesPerSecond:1048576,sampledAt:new Date().toISOString()}};
    const {rerender}=render(<ExecutionProgress run={verifying}/>);
    expect(screen.getByRole("img",{name:"校验 1.0 MiB/s"})).toBeInTheDocument();
    rerender(<ExecutionProgress run={{...verifying,status:"COMPLETED"}}/>);
    expect(screen.getByRole("img",{name:"传输速度 —"})).toBeInTheDocument();
    expect(screen.queryByRole("img",{name:"校验 1.0 MiB/s"})).not.toBeInTheDocument();
  });
  it("expires a sample even when the next poll is still pending",()=>{
    vi.useRealTimers();
    vi.useFakeTimers();
    try {
      render(<ExecutionProgress run={{...run,progress:{...run.progress!,sampledAt:new Date().toISOString()}}}/>);
      expect(screen.getByRole("img",{name:"传输 1.0 KiB/s"})).toBeInTheDocument();
      act(()=>vi.advanceTimersByTime(6001));
      expect(screen.getByRole("img",{name:"传输速度 —"})).toBeInTheDocument();
      expect(screen.getByText("等待最新采样")).toBeInTheDocument();
    } finally { vi.useRealTimers(); }
  });
});
