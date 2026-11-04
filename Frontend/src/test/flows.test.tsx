import { App, ConfigProvider } from "antd";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Login } from "../modules/auth/Login";
import { RestoreModal } from "../modules/restore/RestoreModal";
import { EndpointForm } from "../modules/storage/EndpointForm";
import type { Snapshot } from "../shared/types";

const wrap = (element: React.ReactNode) =>
  render(
    <ConfigProvider>
      <App>{element}</App>
    </ConfigProvider>,
  );
describe("critical forms", () => {
  it("sends a literal single backslash in the administrator password", async () => {
    const fetcher = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(
        new Response(
          JSON.stringify({ success: true, data: { username: "admin" } }),
        ),
      );
    const loggedIn = vi.fn();
    wrap(<Login onLogin={loggedIn} />);
    fireEvent.change(screen.getByLabelText("账号"), { target: { value: "admin" } });
    fireEvent.change(screen.getByLabelText("密码"), {
      target: { value: "test\\@2026" },
    });
    fireEvent.click(screen.getByRole("button", { name: /登\s*录/ }));
    await waitFor(() => expect(loggedIn).toHaveBeenCalledOnce());
    expect(JSON.parse(fetcher.mock.calls[0][1]?.body as string).password).toBe(
      "test\\@2026",
    );
  });
  it("requires a pinned SFTP host key before saving", async () => {
    const fetcher = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({success:true,data:[]})),
    );
    wrap(
      <EndpointForm
        value={{
          name: "secure source",
          protocol: "SFTP",
          root: "/",
          host: "127.0.0.1",
        }}
        onClose={vi.fn()}
        onSaved={async () => {}}
      />,
    );
    await screen.findByLabelText("服务器主机密钥指纹");
    fireEvent.click(screen.getByRole("button", { name: /保存连接/ }));
    expect(
      await screen.findByText("请填写 SSH SHA256 主机密钥指纹"),
    ).toBeInTheDocument();
    // Loading available execution devices is read-only; invalid connection data
    // must never be submitted even though the form fetches that device list.
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === "POST")).toHaveLength(0);
  });
  it("shows archived selections and excludes files newer than the snapshot", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          success: true,
          data: {
            snapshot: { id: "s1" },
            items: [
              {
                path: "old.txt",
                versionId: "v1",
                endpointId: "C",
                location: "old.v1",
                hash: "abc",
                size: 5,
                decision: "HISTORY_C",
              },
            ],
            excludedCurrentPaths: ["added-later.txt"],
          },
        }),
      ),
    );
    const snapshot = {
      id: "s1",
      sequence: 1,
      files: 1,
      totalBytes: 5,
      completedAt: "2026-09-16T00:00:00Z",
    } as Snapshot;
    wrap(
      <RestoreModal
        snapshot={snapshot}
        onClose={vi.fn()}
        onRestored={async () => {}}
      />,
    );
    expect(await screen.findByText("归档库")).toBeInTheDocument();
    fireEvent.click(screen.getByText("排除的当前路径"));
    expect(await screen.findByText("added-later.txt")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /恢\s*复/ }));
    expect(await screen.findByText("请选择独立的恢复库连接")).toBeInTheDocument();
  });
});
