import { App, ConfigProvider } from "antd";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { AppShell } from "../app/AppShell";
import { AccountSettingsModal } from "../modules/auth/AccountSettingsModal";

vi.mock("../app/State", () => ({ useStateData: () => ({ data: null, refresh: vi.fn() }) }));
function Location() { return <div data-testid="location">{useLocation().pathname}</div>; }
const wrap = (children: React.ReactNode) => render(<ConfigProvider><App>{children}</App></ConfigProvider>);
afterEach(async () => {
  cleanup();
  // Ant Form schedules empty validation feedback after 10 ms, even after unmount.
  // Drain that known library timer before this file's jsdom window is destroyed.
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
});

it("opens account settings without navigation and clears passwords when dismissed", async () => {
  wrap(<MemoryRouter initialEntries={["/runs"]}><AppShell onLogout={vi.fn()} /><Location /></MemoryRouter>);
  fireEvent.click(screen.getByRole("button", { name: "账号菜单" }));
  fireEvent.click(await screen.findByRole("menuitem", { name: /账号设置/ }));
  expect(await screen.findByRole("dialog")).toHaveTextContent("账号设置");
  expect(screen.getByTestId("location")).toHaveTextContent("/runs");
  fireEvent.change(screen.getByLabelText("当前密码"), { target: { value: "temporary-input" } });
  fireEvent.click(screen.getByRole("button", { name: /取\s*消/ }));
  await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
  fireEvent.click(screen.getByRole("button", { name: "账号菜单" }));
  fireEvent.click(await screen.findByRole("menuitem", { name: /账号设置/ }));
  expect(await screen.findByLabelText("当前密码")).toHaveValue("");
});

it("retains password confirmation and only logs out after a successful password change", async () => {
  const fetcher = vi.spyOn(globalThis, "fetch").mockImplementation(async()=>new Response(JSON.stringify({ success: true, data: {username:"admin",role:"ADMIN"} })));
  const onLogout = vi.fn(), onClose = vi.fn();
  wrap(<AccountSettingsModal onClose={onClose} onLogout={onLogout} />);
  fireEvent.change(screen.getByLabelText("当前密码"), { target: { value: "old-password" } });
  fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "new-password" } });
  fireEvent.change(screen.getByLabelText("确认新密码"), { target: { value: "different-password" } });
  fireEvent.click(screen.getByRole("button", { name: /更新密码/ }));
  expect(await screen.findByText("两次密码不一致")).toBeInTheDocument();
  expect(fetcher.mock.calls.filter(call=>call[0]==="/api/auth/password")).toHaveLength(0);
  expect(onLogout).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("确认新密码"), { target: { value: "new-password" } });
  fireEvent.click(screen.getByRole("button", { name: /更新密码/ }));
  await waitFor(() => expect(onLogout).toHaveBeenCalledOnce());
  expect(onClose).toHaveBeenCalledOnce();
  const passwordCall=fetcher.mock.calls.find(call=>call[0]==="/api/auth/password")!;
  expect(passwordCall[0]).toBe("/api/auth/password");
  expect(JSON.parse(passwordCall[1]?.body as string)).toEqual({ current: "old-password", next: "new-password" });
});
