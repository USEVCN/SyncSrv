import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AgentInstall } from "../modules/agents/AgentInstall";
import { JobForm } from "../modules/jobs/JobForm";
const asset = (platform: string, arch: string) => ({ platform, arch,
  packageName: `syncsrv-agent-${platform}-${arch}.${platform === "windows" ? "zip" : "tar.gz"}`,
  packageSize: 1048576, packageSha256: "a".repeat(64) });
const release = {version:"0.8.0-dev",assets:[asset("windows","amd64"),asset("windows","arm64"),asset("darwin","arm64")]};
function setup(value: unknown = release) {
  vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({success:true,data:value})));
  render(<ConfigProvider theme={{token:{motion:false}}}><App><AgentInstall /></App></ConfigProvider>);
}
describe("agent package setup", () => {
  it("downloads the published package without putting pairing secrets in a URL or shell command", async () => {
    setup();
    const download = await screen.findByRole("link", {name:"下载完整安装包"});
    expect(download).toHaveAttribute("href", "/agent/releases/0.8.0-dev/syncsrv-agent-windows-amd64.zip");
    expect(screen.queryByText(/Invoke-WebRequest/)).not.toBeInTheDocument();
    expect(screen.queryByText(/复制安装命令/)).not.toBeInTheDocument();
    expect(screen.getByText(/双击 install.bat/)).toBeInTheDocument();
  });
  it("selects the target architecture instead of guessing from the browser machine", async () => {
    setup();
    await screen.findByRole("link", {name:"下载完整安装包"});
    fireEvent.mouseDown(screen.getByRole("combobox", {name:"目标设备架构"}));
    fireEvent.click(await screen.findByText("ARM64"));
    expect(screen.getByRole("link", {name:"下载完整安装包"})).toHaveAttribute("href", "/agent/releases/0.8.0-dev/syncsrv-agent-windows-arm64.zip");
    fireEvent.mouseDown(screen.getByRole("combobox", {name:"目标设备系统"}));
    fireEvent.click(await screen.findByText("Linux"));
    expect(screen.queryByRole("link", {name:"下载完整安装包"})).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("当前版本未提供此系统与架构的安装包");
  });
  it("shows an unavailable release with a retry instead of offering a broken package link", async () => {
    setup([]);
    expect(await screen.findByRole("alert")).toHaveTextContent("安装包暂不可用");
    expect(screen.queryByRole("link", {name:"下载完整安装包"})).not.toBeInTheDocument();
  });
  it("creates agent tasks using only B and C connections", async () => {
    vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({success:true,data:[]})));
    render(<ConfigProvider theme={{token:{motion:false}}}><App><JobForm value={{}} endpoints={[]} onClose={()=>{}} onSaved={async()=>{}} /></App></ConfigProvider>);
    expect(await screen.findByText("源目录直传备份库")).toBeInTheDocument();
    expect(screen.queryByText("源目录")).not.toBeInTheDocument();
    fireEvent.mouseEnter(screen.getByRole("button", {name:"存储连接准备"}));
    await waitFor(() => expect(screen.getByText(/先保存备份库、归档库两个独立存储连接/)).toBeVisible());
  });
});
