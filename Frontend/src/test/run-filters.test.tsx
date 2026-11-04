import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { RunsPage } from "../modules/jobs/RunsPage";

vi.mock("../app/State", () => ({useStateData: () => ({
  refresh: async () => {}, data: {
    jobs: [{id:"done",name:"完成任务"},{id:"failed",name:"失败任务"}],
    runs: [...Array.from({length:11},(_,i)=>({id:`done-${i}`,jobId:"done",status:"COMPLETED"})),
      {id:"failure-one",jobId:"failed",status:"FAILED"},
      {id:"failure-two",jobId:"failed",status:"FAILED"}].map(r=>({...r,
        startedAt:"2026-09-18T01:00:00Z",phase:"COMPLETE",kind:"SYNC",bytes:0})),
  },
})}));

describe("execution status shortcuts", () => {
  it("filters from a later page, keeps search counts meaningful, and returns to all results", () => {
    render(<ConfigProvider><App><RunsPage /></App></ConfigProvider>);
    const filters = within(screen.getByRole("group",{name:"执行状态快捷筛选"}));
    expect(filters.getByRole("button",{name:"已加载 13"})).toHaveAttribute("aria-pressed","true");
    fireEvent.click(screen.getByTitle("2"));
    fireEvent.click(filters.getByRole("button",{name:"失败 2"}));
    expect(screen.queryByText("完成任务")).not.toBeInTheDocument();
    expect(screen.getAllByText("失败任务")).toHaveLength(2);
    expect(filters.getByRole("button",{name:"失败 2"})).toHaveAttribute("aria-pressed","true");
    fireEvent.change(screen.getByPlaceholderText("搜索任务、路径或执行编号"),{target:{value:"failure-one"}});
    expect(filters.getByRole("button",{name:"失败 1"})).toHaveAttribute("aria-pressed","true");
    expect(screen.getAllByText("失败任务")).toHaveLength(1);
    fireEvent.change(screen.getByPlaceholderText("搜索任务、路径或执行编号"),{target:{value:""}});
    fireEvent.click(filters.getByRole("button",{name:"已加载 13"}));
    expect(screen.getAllByText("完成任务")).toHaveLength(10);
    expect(screen.getByTitle("1")).toHaveClass("ant-pagination-item-active");
  });

  it("keeps the dropdown and zero-count shortcuts synchronized", () => {
    render(<ConfigProvider><App><RunsPage /></App></ConfigProvider>);
    const filters = within(screen.getByRole("group",{name:"执行状态快捷筛选"}));
    fireEvent.mouseDown(screen.getByRole("combobox",{name:"执行状态"}));
    fireEvent.click(screen.getByText("已完成",{selector:".ant-select-item-option-content"}));
    expect(filters.getByRole("button",{name:"已完成 11"})).toHaveAttribute("aria-pressed","true");
    fireEvent.click(filters.getByRole("button",{name:"待执行 0"}));
    expect(screen.getByText("暂无记录")).toBeInTheDocument();
    expect(screen.getByRole("combobox",{name:"执行状态"}).closest(".ant-select")).toHaveTextContent("待执行");
  });
});
