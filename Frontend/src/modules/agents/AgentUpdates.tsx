import { Hint } from "../../shared/Hint";
import { Button, Space, Tag, Typography } from "antd";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";
import { pollWhileVisible } from "../../platform/polling";
import type { Agent } from "../../shared/types";

type Asset = { platform: string; arch: string; packageName: string; packageSha256: string };
type Release = { version: string; assets: Asset[] };
export function newerRelease(candidate: string, installed: string) {
  const parse = (s: string) => /^(\d+)\.(\d+)\.(\d+)(?:-dev(?:\.(\d+))?)?$/.exec(s);
  const a = parse(candidate), b = parse(installed);
  if (!a || !b) return false;
  const parts = (m: RegExpExecArray, s: string) => [Number(m[1]), Number(m[2]), Number(m[3]), s.includes("-") ? 0 : 1, Number(m[4] || 0)];
  const left = parts(a, candidate), right = parts(b, installed);
  for (let i = 0; i < left.length; i++) if (left[i] !== right[i]) return left[i] > right[i];
  return false;
}
export function AgentUpdates({ agents }: { agents: Agent[] }) {
  const [release, setRelease] = useState<Release>();
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    const check = async () => {
      try {
        const value = await api<Release>("/agent-release");
        if (!value?.version || !Array.isArray(value.assets)) throw new Error();
        if (active) { setRelease(value); setError(""); }
      } catch { if (active) { setRelease(undefined); setError("版本检查暂不可用，请确认中心已更新且发布包已生成。"); } }
    };
    const stop = pollWhileVisible(check, 60000);
    return () => { active = false; stop(); };
  }, []);
  const outdated = release ? agents.filter(a => newerRelease(release.version, a.version) && release.assets.some(x => x.platform === a.platform && x.arch === a.arch)) : [];
  return <Hint label={error ? "版本检查异常" : outdated.length ? `${outdated.length} 台可升级` : "源端版本"} type={error ? "warning" : outdated.length ? "info" : "success"} showIcon
    title={error || (release ? `源端版本 ${release.version}${outdated.length ? ` · ${outdated.length} 台设备可升级` : " · 手动升级"}` : "正在检查源端版本")}
    description={<>
      <p>发现新版仅提示，后台不会自动升级。Windows 以管理员运行 update.bat；Linux、macOS 和 NAS 运行 sudo sh update.sh。升级会补齐启动、停止、重启和卸载脚本；群晖原生套件通过 DSM 套件中心管理。</p>
      {!!outdated.length && <p>{outdated.map(a => <Tag key={a.id}>{a.name} · {a.version} → {release?.version}</Tag>)}</p>}
      <Space wrap>{release?.assets.map(a => <span key={`${a.platform}-${a.arch}`}>
        <Button size="small" href={`/agent/releases/${release.version}/${a.packageName}`} download>{a.platform === "synology" ? "群晖 DSM" : a.platform === "darwin" ? "macOS" : a.platform === "linux" ? "Linux" : "Windows"} {a.arch === "amd64" ? "x64" : "ARM64"}</Button>{" "}
        <Typography.Text copyable={{text:a.packageSha256}}>SHA-256</Typography.Text>
      </span>)}</Space>
      <p className="muted">已有设备直接执行原安装目录的 update 脚本即可下载新版；身份和队列会保留。Linux 服务名统一为 SyncSrvAgent，旧版 syncsrv-agent 服务会在升级时迁移。卸载使用 uninstall.bat / uninstall.sh，保留程序与数据。</p>
    </>} />;
}
