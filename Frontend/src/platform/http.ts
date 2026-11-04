import { storageText } from "../shared/storageText";

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(storageText(message));
  }
}
export async function api<T>(
  path: string,
  method = "GET",
  body?: unknown,
): Promise<T> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), method === "GET" ? 30000 : 120000);
  try {
  const response = await fetch(`/api${path}`, {
    method,
    credentials: "same-origin",
    headers: { "Content-Type": "application/json", "X-SyncSrv-Request": "1" },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: controller.signal,
  });
  const result = await response.json().catch(() => null);
  if (!response.ok || !result?.success) {
    if (response.status === 401)
      window.dispatchEvent(new Event("syncsrv:unauthorized"));
    throw new ApiError(
      result?.message || `服务请求失败 (${response.status})`,
      response.status,
    );
  }
  return result.data as T;
  } catch (error) {
    if (controller.signal.aborted) throw new ApiError(method === "GET"
      ? "读取服务状态超时，请检查连接后重试"
      : "请求等待超时，执行结果尚未确认；请先刷新状态，避免重复提交", 408);
    throw error;
  } finally {
    window.clearTimeout(timer);
  }
}
export async function download(path: string, filename: string) {
  const response = await fetch(`/api${path}`, { credentials: "same-origin" });
  if (!response.ok) throw new Error("下载失败，请检查登录状态");
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement("a");
  link.href = url;
  link.download = filename;
  link.click();
  URL.revokeObjectURL(url);
}
