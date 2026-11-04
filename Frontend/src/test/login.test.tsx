import { App, ConfigProvider } from "antd";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "../platform/http";
import { Login } from "../modules/auth/Login";
import { LOGIN_LOCALE_KEY } from "../modules/auth/loginLocale";
import { darkTheme } from "../shared/theme";

vi.mock("../platform/http", async importOriginal => ({
  ...await importOriginal<typeof import("../platform/http")>(), api: vi.fn(),
}));
beforeEach(() => { localStorage.removeItem(LOGIN_LOCALE_KEY); vi.mocked(api).mockReset(); });
const mount = (onLogin = vi.fn()) => render(<ConfigProvider theme={darkTheme}><App><Login onLogin={onLogin} /></App></ConfigProvider>);
const credentials = () => {
  fireEvent.change(screen.getByLabelText("账号"), { target: { value: "ui-test" } });
  fireEvent.change(screen.getByLabelText("密码", { exact: true }), { target: { value: "test-only-password" } });
};
const submit = () => fireEvent.submit(document.querySelector("form")!);

it("validates required fields locally and translates visible errors without a request", async () => {
  mount(); submit();
  expect(await screen.findByText("请输入账号")).toBeVisible();
  expect(screen.getByText("请输入密码")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Switch to English" }));
  expect(await screen.findByText("Enter your username")).toBeVisible();
  expect(screen.getByText("Enter your password")).toBeVisible();
  expect(api).not.toHaveBeenCalled();
});

it("preserves entered credentials while changing language and persists the choice", () => {
  const view = mount(); credentials();
  fireEvent.click(screen.getByRole("button", { name: "Switch to English" }));
  expect(screen.getByLabelText("Username")).toHaveValue("ui-test");
  expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("test-only-password");
  expect(localStorage.getItem(LOGIN_LOCALE_KEY)).toBe("en");
  view.unmount(); mount();
  expect(screen.getByRole("button", { name: "Sign in" })).toBeVisible();
  expect(screen.getByLabelText("Password", { exact: true })).toHaveValue("");
});

it("submits only once while pending and enters the workspace after success", async () => {
  let resolve!: (value: unknown) => void;
  vi.mocked(api).mockImplementation(() => new Promise(done => { resolve = done; }));
  const onLogin = vi.fn(); mount(onLogin); credentials();
  submit(); submit();
  await waitFor(() => expect(api).toHaveBeenCalledTimes(1));
  expect(api).toHaveBeenCalledWith("/auth/login", "POST", { username: "ui-test", password: "test-only-password" });
  expect(onLogin).not.toHaveBeenCalled();
  await act(async () => resolve({}));
  expect(onLogin).toHaveBeenCalledTimes(1);
});

it("keeps login errors visible and allows a corrected retry", async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError("用户名或密码不正确", 400)).mockResolvedValueOnce({});
  const onLogin = vi.fn(); mount(onLogin); credentials(); submit();
  expect(await screen.findByRole("alert")).toHaveTextContent("用户名或密码不正确");
  expect(onLogin).not.toHaveBeenCalled();
  expect(screen.getByLabelText("账号")).toHaveValue("ui-test");
  fireEvent.click(screen.getByRole("button", { name: "Switch to English" }));
  expect(screen.getByRole("alert")).toHaveTextContent("Incorrect username or password");
  submit();
  await waitFor(() => expect(onLogin).toHaveBeenCalledTimes(1));
  expect(api).toHaveBeenCalledTimes(2);
});

it("retains the form when graphics or persistent storage are unavailable", () => {
  vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => { throw new Error("Blocked"); });
  vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new Error("Blocked"); });
  mount();
  expect(screen.getByLabelText("账号")).toBeVisible();
  expect(screen.getByRole("img", { name: "SyncSrv" })).toBeVisible();
  expect(document.querySelector(".auth-login-aurora canvas")).not.toBeInTheDocument();
});
