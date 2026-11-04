import { App, ConfigProvider } from "antd";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { CenterUpdatePanel } from "../modules/settings/CenterUpdatePanel";
import { api } from "../platform/http";

vi.mock("../platform/http", () => ({api: vi.fn()}));
const sample = {status: "UPDATE_AVAILABLE", message: "有兼容新版本；请手动升级", currentVersion: "0.4.2", latestVersion: "0.4.3",
  project: "syncsrv", channel: "stable", os: "linux", arch: "x64", releaseId: "11111111-1111-1111-1111-111111111111",
  signature: "清单与原生签名已验证", sourceMin: "0.4.0", sourceMax: "0.4.2", checkedAt: "2026-10-25T05:00:00Z",
  platformSupported: false, manifestUrl: "https://repo.example/manifest"};
const source = {serverUrl:"https://repo.example",revision:"reviewed-source",project:"syncsrv",channel:"stable"};
const mockChecks = (check:()=>Promise<unknown>) => vi.mocked(api).mockImplementation(path => path === "/system/updates/source" ? Promise.resolve(source) : check());
const checkCalls = () => vi.mocked(api).mock.calls.filter(([path])=>path === "/system/updates/check");
const show = () => render(<ConfigProvider><App><CenterUpdatePanel healthUrl="http://127.0.0.1:18010/api/health" /></App></ConfigProvider>);
afterEach(() => { cleanup(); vi.clearAllMocks(); vi.mocked(api).mockReset(); });

it("checks only after an explicit click and never exposes an install action", async () => {
  mockChecks(async()=>sample); show();
  await waitFor(()=>expect(screen.getByLabelText("更新服务器地址")).toHaveValue(source.serverUrl));
  expect(api).toHaveBeenCalledWith("/system/updates/source");
  expect(checkCalls()).toHaveLength(0); expect(screen.getByText("尚未检查更新")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", {name: /检查版本/}));
  expect(await screen.findByText("发现兼容新版本")).toBeInTheDocument();
  expect(checkCalls()).toEqual([["/system/updates/check"]]);
  expect(screen.getByText("syncsrv / stable / linux / x64")).toBeInTheDocument();
  expect(screen.getByText("当前平台仅供核验")).toBeInTheDocument();
  expect(screen.queryByRole("button", {name: /安装|执行升级|立即更新/})).not.toBeInTheDocument();
});

it("prevents duplicate checks and removes a previous success when signature verification fails", async () => {
  let resolve!: (value: typeof sample) => void;
  mockChecks(() => new Promise(done => { resolve = done; })); show();
  const button = screen.getByRole("button", {name: /检查版本/}); fireEvent.click(button); fireEvent.click(button);
  expect(checkCalls()).toHaveLength(1);
  await act(async () => resolve(sample)); await screen.findByText("发现兼容新版本");
  vi.mocked(api).mockResolvedValue({...sample, status: "SIGNATURE_REJECTED", latestVersion: "", signature: "未验证", releaseId: "", sourceMin: "", sourceMax: ""});
  fireEvent.click(screen.getByRole("button", {name: /检查版本/}));
  expect(await screen.findByText("签名验证失败")).toBeInTheDocument();
  expect(screen.queryByText("发现兼容新版本")).not.toBeInTheDocument(); expect(screen.getByText("未取得可信版本")).toBeInTheDocument();
});

it("offers a retry after request failure and reports signed incompatibility", async () => {
  mockChecks(async()=>{throw new Error("连接超时");}); show(); fireEvent.click(screen.getByRole("button", {name: /检查版本/}));
  expect(await screen.findByText("版本检查请求失败")).toBeInTheDocument();
  vi.mocked(api).mockResolvedValue({...sample, status: "INCOMPATIBLE", message: "当前版本不在签名升级范围内"});
  fireEvent.click(screen.getByRole("button", {name: /检查版本/})); expect(await screen.findByText("版本不兼容")).toBeInTheDocument();
  expect(screen.queryByText("版本检查请求失败")).not.toBeInTheDocument();
});

it("discards a late response after the panel closes", async () => {
  let resolve!: (value: typeof sample) => void; mockChecks(() => new Promise(done => { resolve = done; }));
  const page = show(); fireEvent.click(screen.getByRole("button", {name: /检查版本/})); page.unmount();
  await act(async () => resolve(sample)); expect(screen.queryByText("发现兼容新版本")).not.toBeInTheDocument();
});
