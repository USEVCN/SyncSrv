import { fileType } from "../modules/jobs/fileType";
import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { DirectoryTree } from "../modules/jobs/DirectoryTreeDrawer";
import type { Job } from "../shared/types";
const job = { id: "job", sourceMode: "AGENT" } as Job;
const entry = (path: string, directory = false) => ({ path, name: path.split("/").pop(), directory, size: 4, modified: 1789722000000, symbolic: false });
const listing = (entries: unknown[], hasMore = false) => new Response(JSON.stringify({ success: true, data: {
  root: "/backup", mode: "LIVE", observedAt: "2026-09-18T00:00:00Z", entries, total: hasMore ? 201 : entries.length, hasMore,
} }));
it("loads children only when a folder is expanded and displays files", async () => {
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async input =>
    String(input).includes("path=docs") ? listing([entry("docs/report.txt")]) : listing([entry("docs", true)]));
  render(<ConfigProvider><App><DirectoryTree job={job} role="B" /></App></ConfigProvider>);
  const folder = await screen.findByText("docs");
  expect(fetcher).toHaveBeenCalledTimes(1);
  expect(screen.queryByText("report.txt")).not.toBeInTheDocument();
  fireEvent.click(folder);
  expect(await screen.findByText("report.txt")).toBeInTheDocument();
  expect(fetcher.mock.calls.some(([url]) => String(url).includes("role=B&path=docs"))).toBe(true);
  fireEvent.click(screen.getByText("report.txt"));
  expect(await screen.findByText("docs/report.txt")).toBeInTheDocument();
});
it("retains existing siblings when the next page is loaded", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async input =>
    String(input).includes("offset=1") ? listing([entry("last.txt")]) : listing([entry("first.txt")], true));
  render(<ConfigProvider><App><DirectoryTree job={job} role="C" /></App></ConfigProvider>);
  fireEvent.click(await screen.findByRole("button", { name: /加载更多/ }));
  expect(await screen.findByText("last.txt")).toBeInTheDocument();
  expect(screen.getByText("first.txt")).toBeInTheDocument();
});
it("shows errors instead of declaring an unavailable source directory empty", async () => {
  vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("源端尚未上报目录清单"));
  render(<ConfigProvider><App><DirectoryTree job={job} role="A" /></App></ConfigProvider>);
  fireEvent.mouseEnter(await screen.findByRole("button", { name: "目录读取失败" }));
  await waitFor(() => expect(screen.getByText("源端尚未上报目录清单")).toBeVisible());
  expect(screen.queryByText("此目录暂无文件或子目录")).not.toBeInTheDocument();
});

it("defaults to newest updates and reloads from the root when name sorting is selected", async () => {
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async () => listing([entry("report.txt")]));
  render(<ConfigProvider><App><DirectoryTree job={job} role="B" /></App></ConfigProvider>);
  await screen.findByText("report.txt");
  expect(fetcher.mock.calls[0][0]).toContain("sort=modified");
  expect(screen.getByText("最后更新")).toBeInTheDocument();
  fireEvent.mouseDown(screen.getByRole("combobox", {name:"目录排序"}));
  fireEvent.click(await screen.findByText("文件名称 · 升序"));
  await waitFor(() => expect(fetcher.mock.calls.some(([url]) => String(url).includes("path=&offset=0&sort=name"))).toBe(true));
});

it("identifies folders, extensions and compound archives without mistaking dotfiles", () => {
  expect(fileType({name:"docs",directory:true,symbolic:false})).toBe("文件夹");
  expect(fileType({name:"REPORT.PDF",directory:false,symbolic:false})).toBe("PDF");
  expect(fileType({name:"package.tar.gz",directory:false,symbolic:false})).toBe("TAR.GZ");
  expect(fileType({name:".env",directory:false,symbolic:false})).toBe("文件");
  expect(fileType({name:`report.pdf.__v${"a".repeat(32)}.__deleted`,directory:false,symbolic:false}, true)).toBe("PDF");
});
