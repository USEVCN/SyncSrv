import { App, ConfigProvider } from "antd";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppShell } from "../app/AppShell";
import { SystemSettingsDrawer } from "../modules/settings/SystemSettingsDrawer";
import { defaultUiSettings } from "../modules/settings/settingsModel";
import { api } from "../platform/http";

const state=vi.hoisted(()=>({data:{system:{version:"0.4.3",java:"17",database:"H2",port:18010,bindAddress:"127.0.0.1"},settings:{bandwidthMiB:8},currentUser:{username:"admin",role:"ADMIN"},endpoints:[],jobs:[],runs:[],snapshots:[],audit:{}},refresh:vi.fn()}));
vi.mock("../app/State",()=>({useStateData:()=>state}));
vi.mock("../platform/http",()=>({api:vi.fn()}));
vi.mock("../modules/jobs/JobsPage",()=>({JobsPage:()=> <div>备份任务背景</div>}));
vi.mock("../modules/settings/OperationsPanel",()=>({OperationsPanel:()=> <div>运维状态</div>}));
function Location(){const value=useLocation();return <div data-testid="location">{value.pathname+value.search}</div>;}
const wrap=(element:React.ReactNode)=>render(<ConfigProvider><App>{element}</App></ConfigProvider>);
beforeEach(()=>{state.data.currentUser.role="ADMIN";vi.mocked(api).mockClear();vi.mocked(api).mockImplementation(async path=>path==="/system/https"?{enabled:false,active:false,port:18014,bindAddress:"127.0.0.1",publicUrl:"",certificateInstalled:false}:structuredClone(defaultUiSettings));});
afterEach(async()=>{cleanup();await act(async()=>{await new Promise(resolve=>setTimeout(resolve,20));});});

it("opens settings over the current task and returns to the same route and query",async()=>{
  wrap(<MemoryRouter initialEntries={["/jobs?filter=failed"]}><AppShell onLogout={()=>{}}/><Location/></MemoryRouter>);
  expect(await screen.findByText("备份任务背景")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button",{name:"账号菜单"}));
  await waitFor(()=>expect(document.querySelector(".account-menu-popup")).not.toBeNull());
  fireEvent.click(within(document.querySelector(".account-menu-popup") as HTMLElement).getByRole("menuitem",{name:/系统设置/}));
  const dialog=await screen.findByRole("dialog",{name:"系统设置"});
  expect(await screen.findByRole("tab",{name:"HTTPS"})).toBeInTheDocument();
  expect(screen.getByText("备份任务背景")).toBeInTheDocument();
  expect(screen.getByTestId("location")).toHaveTextContent("/settings");
  expect(screen.getByRole("menuitem",{name:"备份任务"})).toHaveClass("ant-menu-item-selected");
  expect(screen.getByRole("menuitem",{name:"服务总览"})).not.toHaveClass("ant-menu-item-selected");
  fireEvent.click(dialog.querySelector<HTMLButtonElement>(".ant-drawer-close")!);
  await waitFor(()=>expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  expect(screen.getByTestId("location")).toHaveTextContent("/jobs?filter=failed");
});

it("keeps unsaved basic edits while the service state is polled and saves only reviewed values",async()=>{
  const {rerender}=wrap(<SystemSettingsDrawer onClose={()=>{}}/>);
  const name=await screen.findByLabelText("系统名称");
  fireEvent.change(name,{target:{value:"本地备份中心"}});
  rerender(<ConfigProvider><App><SystemSettingsDrawer onClose={()=>{}}/></App></ConfigProvider>);
  expect(screen.getByLabelText("系统名称")).toHaveValue("本地备份中心");
  fireEvent.click(screen.getByRole("button",{name:/保存基础设置/}));
  await waitFor(()=>expect(api).toHaveBeenCalledWith("/system/settings","PUT",expect.objectContaining({systemName:"本地备份中心",typography:defaultUiSettings.typography})));
  expect(api).not.toHaveBeenCalledWith("/settings","PUT",expect.anything());
  fireEvent.click(screen.getByRole("tab",{name:"运行环境"}));
  expect(await screen.findByRole("region",{name:"系统关于"})).toHaveTextContent("0.4.3");
  expect(screen.getByRole("region",{name:"系统关于"})).toHaveTextContent("H2");
  expect(screen.getByRole("region",{name:"系统关于"})).toHaveTextContent("18010");
});

it("keeps system settings visible but disabled for a non-admin, with the account modal separate",async()=>{
  state.data.currentUser.role="AUDITOR";
  wrap(<MemoryRouter initialEntries={["/jobs"]}><AppShell onLogout={()=>{}}/><Location/></MemoryRouter>);
  await screen.findByText("备份任务背景");
  fireEvent.click(screen.getByRole("button",{name:"账号菜单"}));
  const menu=await screen.findByRole("menuitem",{name:/系统设置（仅管理员）/});
  expect(menu).toHaveAttribute("aria-disabled","true");fireEvent.click(menu);
  expect(screen.getByTestId("location")).toHaveTextContent("/jobs");
  expect(api).not.toHaveBeenCalledWith("/system/settings");
  expect(screen.queryByRole("dialog",{name:"系统设置"})).not.toBeInTheDocument();
});

it("disables both settings forms after a failed read and enables them only after a successful retry",async()=>{
  vi.mocked(api).mockRejectedValueOnce(new Error("read unavailable"));
  wrap(<SystemSettingsDrawer onClose={()=>{}}/>);
  expect(await screen.findByRole("button",{name:"设置读取失败"})).toBeInTheDocument();
  expect(screen.getByRole("button",{name:"保存基础设置"})).toBeDisabled();
  expect(screen.getByRole("button",{name:"保存传输设置"})).toBeDisabled();
  fireEvent.click(screen.getByRole("button",{name:"重新读取"}));
  await waitFor(()=>expect(screen.getByRole("button",{name:"保存传输设置"})).toBeEnabled());
  expect(screen.getByRole("button",{name:"保存基础设置"})).toBeEnabled();
  expect(api).not.toHaveBeenCalledWith("/settings","PUT",expect.anything());
});

it("presents seven real typography levels and clears TLS draft material on tab changes",async()=>{
  wrap(<SystemSettingsDrawer onClose={()=>{}}/>);
  fireEvent.click(await screen.findByRole("tab",{name:"界面字号"}));
  expect(await screen.findByRole("spinbutton",{name:"辅助说明"})).toHaveValue("11");
  fireEvent.change(screen.getByRole("spinbutton",{name:"正文与表单"}),{target:{value:"15"}});
  fireEvent.click(screen.getByRole("button",{name:/保存并应用/}));
  await waitFor(()=>expect(api).toHaveBeenCalledWith("/system/settings","PUT",expect.objectContaining({typography:expect.objectContaining({body:15})})));
  fireEvent.click(screen.getByRole("tab",{name:"HTTPS"}));
  expect(await screen.findByLabelText("启用 HTTPS")).not.toBeChecked();
  expect(screen.getAllByText("尚未选择",{selector:"span"})).toHaveLength(2);
  const cert=new File(["certificate"],"local-cert.pem"),key=new File(["key"],"local-key.pem");
  Object.defineProperty(cert,"text",{value:async()=>"-----BEGIN CERTIFICATE-----\nfixture\n-----END CERTIFICATE-----"});
  Object.defineProperty(key,"text",{value:async()=>"-----BEGIN PRIVATE KEY-----\nsecret-fixture\n-----END PRIVATE KEY-----"});
  const files=document.querySelectorAll<HTMLInputElement>('input[type="file"]');
  fireEvent.change(files[0],{target:{files:[cert]}});fireEvent.change(files[1],{target:{files:[key]}});
  expect(await screen.findByText("local-cert.pem")).toBeInTheDocument();
  expect(await screen.findByText("local-key.pem")).toBeInTheDocument();
  expect(screen.queryByText(/secret-fixture/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("tab",{name:"在线升级"}));
  expect(await screen.findByText("update.sh update")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("tab",{name:"HTTPS"}));
  await screen.findByLabelText("启用 HTTPS");
  expect(screen.queryByText("local-cert.pem")).not.toBeInTheDocument();
  expect(screen.queryByText("local-key.pem")).not.toBeInTheDocument();
  expect(api).not.toHaveBeenCalledWith("/system/https","PUT",expect.anything());
});
