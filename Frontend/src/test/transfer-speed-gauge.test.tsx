import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { TransferSpeedGauge } from "../modules/jobs/TransferSpeedGauge";
const MIB=1024**2;
describe("throughput instrument",()=>{
  it("expands its visible range and keeps that range when throughput falls",()=>{
    const {rerender}=render(<TransferSpeedGauge value={7.9*MIB} label="传输" state="实时"/>);
    expect(screen.getByText("自动量程 · 10 MiB/s")).toBeInTheDocument();
    rerender(<TransferSpeedGauge value={11*MIB} label="传输" state="实时"/>);
    expect(screen.getByText("自动量程 · 20 MiB/s")).toBeInTheDocument();
    rerender(<TransferSpeedGauge value={2*MIB} label="传输" state="实时"/>);
    expect(screen.getByText("自动量程 · 20 MiB/s")).toBeInTheDocument();
    expect(screen.getByRole("img",{name:"传输 2.0 MiB/s"})).toBeInTheDocument();
  });
  it.each([null,NaN,Infinity,-1])("distinguishes missing or invalid sampling (%s) from zero",value=>{
    render(<TransferSpeedGauge value={value} label="传输" state="等待最新采样"/>);
    expect(screen.getByRole("img",{name:"传输速度 —"})).toBeInTheDocument();
    expect(screen.queryByText(/NaN|Infinity/)).not.toBeInTheDocument();
  });
  it("handles idle and sub-byte rates without an invalid unit",()=>{
    const {rerender}=render(<TransferSpeedGauge value={0} label="传输" state="实时"/>);
    expect(screen.getByRole("img",{name:"传输 0 B/s"})).toBeInTheDocument();
    rerender(<TransferSpeedGauge value={.3} label="传输" state="实时"/>);
    expect(screen.getByRole("img",{name:"传输 0 B/s"})).toBeInTheDocument();
  });
});
