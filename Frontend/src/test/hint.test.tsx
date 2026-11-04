import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { Hint } from "../shared/Hint";
import { AgentUpdates } from "../modules/agents/AgentUpdates";

it("opens help on hover or keyboard focus, with Escape support", async () => {
  render(<Hint label="帮助" title="提示标题" description="完整帮助内容" />);
  const trigger = screen.getByRole("button", { name: "帮助" });
  expect(screen.queryByText("完整帮助内容")).not.toBeInTheDocument();
  fireEvent.mouseEnter(trigger);
  await waitFor(() => expect(screen.getByText("完整帮助内容")).toBeVisible());
  fireEvent.keyDown(trigger, { key: "Escape" });
  await waitFor(() => expect(trigger).toHaveAttribute("aria-expanded", "false"));
  fireEvent.focus(trigger);
  await waitFor(() => expect(trigger).toHaveAttribute("aria-expanded", "true"));
});
it("keeps upgrade guidance and package downloads available in a compact popover", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ success: true, data: {
    version: "0.7.0-dev", assets: [{ platform: "windows", arch: "amd64", packageName: "agent.zip", packageSha256: "abc" }],
  } })));
  render(<ConfigProvider><App><AgentUpdates agents={[]} /></App></ConfigProvider>);
  expect(screen.queryByText(/发现新版仅提示/)).not.toBeInTheDocument();
  fireEvent.mouseEnter(screen.getByRole("button", { name: "源端版本" }));
  expect(await screen.findByRole("link", { name: "Windows x64" })).toHaveAttribute("href", "/agent/releases/0.7.0-dev/agent.zip");
  await waitFor(() => expect(screen.getByText(/发现新版仅提示/)).toBeVisible());
});
