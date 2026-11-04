import { Hint } from "../../shared/Hint";
import { Button, Input, Select, Space, Steps, Typography } from "antd";
import { DownloadOutlined, ReloadOutlined } from "@ant-design/icons";
import { useEffect, useState } from "react";
import { api } from "../../platform/http";

type Asset = { platform: string; arch: string; packageName: string; packageSha256: string; packageSize: number };
type Release = { version: string; assets: Asset[] };
const platforms: Record<string, string> = { windows: "Windows", linux: "Linux", darwin: "macOS", synology: "群晖 DSM" };
const mechanisms: Record<string, string> = {
  synology: "DSM 7.2.2+ 套件中心 · 开机自启 · 系统内部用户 SyncSrvAgent",
  windows: "Windows 系统服务 · 开机自启 · LocalSystem 账户",
  linux: "SyncSrvAgent · systemd 系统服务 · 开机自启",
  darwin: "launchd 系统服务 · 开机自启",
};
export function AgentInstall() {
  const [release, setRelease] = useState<Release>();
  const [error, setError] = useState("");
  const [attempt, setAttempt] = useState(0);
  const [platform, setPlatform] = useState("windows");
  const [arch, setArch] = useState("amd64");
  const [server, setServer] = useState(["localhost", "127.0.0.1", "[::1]"].includes(window.location.hostname) || window.location.origin === "null" ? "" : window.location.origin);
  useEffect(() => {
    let active = true;
    setRelease(undefined); setError("");
    void api<Release>("/agent-release").then(value => {
      if (!value?.version || !value.assets?.length) throw new Error("暂无完整安装包");
      if (active) setRelease(value);
    }).catch(() => { if (active) setError("安装包暂不可用，请确认服务器已发布完整版本。"); });
    return () => { active = false; };
  }, [attempt]);
  const asset = release?.assets.find(item => item.platform === platform && item.arch === arch);
  return <div className="space-top">
    <p>{platform === "synology" ? "下载群晖原生 SPK，在 DSM 套件中心选择「手动安装」。同一设备上的备份库、归档库、恢复库共用一个 Agent。" : "下载安装包，复制到目标设备并完整解压。安装过程中直接使用包内程序，无需复制下载命令。"}</p>
    <Space wrap className="space-bottom">
      <Select aria-label="目标设备系统" value={platform} onChange={setPlatform} style={{ width: 145 }}
        options={Object.entries(platforms).map(([value, label]) => ({value, label}))} />
      <Select aria-label="目标设备架构" value={arch} onChange={setArch} style={{ width: 185 }}
        options={[
          { value: "amd64", label: platform === "darwin" ? "Intel · x64" : "Intel / AMD · x64" },
          { value: "arm64", label: platform === "darwin" ? "Apple Silicon · ARM64" : "ARM64" },
        ]} />
      <Button type="primary" aria-label="下载完整安装包" icon={<DownloadOutlined />} disabled={!asset}
        href={asset && release ? `/agent/releases/${encodeURIComponent(release.version)}/${encodeURIComponent(asset.packageName)}` : undefined}
        download={asset?.packageName}>下载完整安装包</Button>
    </Space>
    {error ? <p role="alert">{error} <Button size="small" icon={<ReloadOutlined />} onClick={() => setAttempt(a => a + 1)}>重试</Button></p>
      : release ? asset ? <p className="muted">{release.version} · {(asset.packageSize / 1048576).toFixed(1)} MiB · <Typography.Text copyable={{text:asset.packageSha256}}>SHA-256</Typography.Text></p>
        : <p role="alert">当前版本未提供此系统与架构的安装包。</p>
      : <p role="status">正在读取已发布的安装包…</p>}
    <Steps size="small" orientation="vertical" className="space-top" items={[
      { title: platform === "synology" ? "套件中心手动安装" : "解压后运行安装入口", content: platform === "synology" ? "上传 SPK，按 DSM 安装向导操作；无需解压和 SSH 命令。" : platform === "windows"
        ? "双击 install.bat，允许 Windows 管理员权限提示。"
        : platform === "darwin" ? "双击 install.command，或在终端运行 sh install.sh；按提示输入管理员密码。"
        : "在解压目录运行 sh install.sh，脚本会请求 sudo 管理员权限。" },
      { title: "按向导填写必要信息", content: platform === "synology" ? "填写备份服务器、设备名称、一次性配对凭据。安装卷由 DSM 管理，身份和队列保存在套件数据目录。" : "填写备份服务器、设备名称、安装目录及一次性配对密钥。密钥由本页「添加设备」生成，安装时隐藏输入。" },
      { title: "授权设备并配置备份", content: "安装后回到设备列表授权。在备份任务中配置源目录；在存储连接中为备份库、归档库、恢复库绑定执行设备和本地目录。同一台设备只需安装一次。" },
    ]} />
    <p>备份服务器地址 <Hint iconOnly label="安装服务器地址说明" title="填写目标设备可访问的固定 IP 或域名；开发测试使用 Mac 的局域网 IP，前后端都需保持运行。" /></p>
    <Space.Compact className="full-width">
      <Input aria-label="备份服务器地址" placeholder="例如 http://192.168.50.20:18011" value={server} onChange={e => setServer(e.target.value)} />
      <Typography.Text copyable={server ? {text: server} : false} style={{padding:"6px 12px",whiteSpace:"nowrap"}}>复制地址</Typography.Text>
    </Space.Compact>
    <p className="muted">{mechanisms[platform]}。程序和数据目录在安装向导中设置，备份目录在安装后的网页任务中选择。</p>
    {platform === "linux" && <Hint type="warning" label="NAS 安装范围" title="Linux 包要求 systemd" description="群晖请在系统选项中选择「群晖 DSM」下载 SPK。QNAP 等其他 NAS 的厂商套件尚未提供，可继续使用存储协议连接。" />}
    {platform === "synology" && <Hint label="群晖目录权限" title="为套件授予所用共享文件夹的读写权限" description="DSM 控制面板 → 共享文件夹 → 编辑 → 权限 → 系统内部用户 → SyncSrvAgent。更新请在套件中心手动安装新版 SPK，身份和队列会保留；当前为开发测试包，DSM 实机验收尚待完成。" />}
    <Hint label="启停与卸载" title="服务管理" description={platform === "synology"
      ? "在 DSM 套件中心管理 SyncSrvAgent 的启动、停止和卸载。"
      : platform === "linux"
        ? "查看状态：systemctl status SyncSrvAgent；启停和重启：sudo systemctl start / stop / restart SyncSrvAgent（选择其中一个动作）。卸载：sudo sh uninstall.sh。卸载保留程序、配置、身份、队列、日志和备份文件。"
        : platform === "windows"
          ? "服务名称为 SyncSrvAgent。运行 start.bat、stop.bat、restart.bat 管理服务；uninstall.bat 停止并移除服务注册，保留程序、配置、身份、队列、日志和备份文件。"
          : "运行 start.sh、stop.sh、restart.sh 管理服务；双击 uninstall.command 或运行 sudo sh uninstall.sh 卸载。卸载保留程序、配置、身份、队列、日志和备份文件。"} />
    <Hint label="已有 Agent 的设备" title="升级时保留配对身份和队列" description="直接运行原安装目录的 update.bat 或 sudo sh update.sh，从中心下载新版并补齐管理脚本。缺少管理脚本时才需下载完整包。升级保留设备身份和队列，无需重新配对。" />
  </div>;
}
