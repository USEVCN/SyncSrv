import { App, ConfigProvider } from "antd";
import { act, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { AppShell } from "../app/AppShell";
import { darkTheme } from "../shared/theme";

vi.mock("../app/State",()=>({useStateData:()=>({data:undefined,error:"",refresh:vi.fn()})}));
const shell=()=>render(<ConfigProvider theme={darkTheme}><App><MemoryRouter initialEntries={["/jobs"]}><AppShell onLogout={vi.fn()} /></MemoryRouter></App></ConfigProvider>);
function resize(width:number) { Object.defineProperty(window,"innerWidth",{configurable:true,value:width}); fireEvent(window,new Event("resize")); }
  beforeEach(()=>{localStorage.removeItem("syncsrv:sidebar-collapsed");resize(1440);});
  afterEach(()=>{act(()=>resize(1440));});
it("keeps all backup routes accessible after grouping the navigation",()=>{
  shell();
  expect(screen.getAllByRole("menuitem")).toHaveLength(7);
  expect(screen.queryByRole("menuitem",{name:"系统设置"})).not.toBeInTheDocument();
  expect(screen.getByRole("menuitem",{name:"备份任务"})).toHaveClass("ant-menu-item-selected");
  fireEvent.click(screen.getByRole("menuitem",{name:"存储连接"}));
  expect(screen.getByRole("menuitem",{name:"存储连接"})).toHaveClass("ant-menu-item-selected");
  expect(screen.getByRole("menuitem",{name:"备份任务"})).not.toHaveClass("ant-menu-item-selected");
});
it("restores the collapsed navigation on remount and can expand it again",()=>{
  const view=shell();
  fireEvent.click(screen.getByRole("button",{name:"收起导航"}));
  expect(localStorage.getItem("syncsrv:sidebar-collapsed")).toBe("true");
  view.unmount();
  shell();
  expect(screen.getByRole("button",{name:"展开导航"})).toHaveAttribute("aria-expanded","false");
  fireEvent.click(screen.getByRole("button",{name:"展开导航"}));
  expect(localStorage.getItem("syncsrv:sidebar-collapsed")).toBe("false");
  expect(screen.getByRole("button",{name:"收起导航"})).toHaveAttribute("aria-expanded","true");
});

it("uses a temporary mobile overlay without replacing the desktop preference",()=>{
  localStorage.setItem("syncsrv:sidebar-collapsed","false");
  resize(390); shell();
  expect(screen.getByRole("button",{name:"打开主导航"})).toHaveAttribute("aria-expanded","false");
  expect(screen.queryByRole("menuitem",{name:"备份任务"})).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button",{name:"打开主导航"}));
  expect(screen.getByRole("button",{name:"关闭主导航"})).toHaveAttribute("aria-expanded","true");
  fireEvent.click(screen.getByRole("menuitem",{name:"存储连接"}));
  expect(screen.getByRole("button",{name:"打开主导航"})).toHaveAttribute("aria-expanded","false");
  expect(localStorage.getItem("syncsrv:sidebar-collapsed")).toBe("false");
  act(()=>resize(1440));
  expect(screen.getByRole("button",{name:"收起导航"})).toBeInTheDocument();
  expect(screen.getByRole("menuitem",{name:"存储连接"})).toHaveClass("ant-menu-item-selected");
});
it("closes the mobile navigation with Escape or its backdrop",()=>{
  resize(390);shell();
  fireEvent.click(screen.getByRole("button",{name:"打开主导航"}));
  fireEvent.keyDown(screen.getByRole("menuitem",{name:"备份任务"}),{key:"Escape"});
  expect(screen.getByRole("button",{name:"打开主导航"})).toHaveAttribute("aria-expanded","false");
  fireEvent.click(screen.getByRole("button",{name:"打开主导航"}));
  fireEvent.click(screen.getByRole("button",{name:"关闭导航遮罩"}));
  expect(screen.getByRole("button",{name:"打开主导航"})).toHaveAttribute("aria-expanded","false");
});
