export function bytes(value = 0) {
  if (!Number.isFinite(value) || value < 0) return "—";
  if (!value) return "0 B";
  const unit = Math.max(0, Math.min(Math.floor(Math.log(value) / Math.log(1024)), 4));
  return `${(value / 1024 ** unit).toFixed(unit ? 1 : 0)} ${["B", "KiB", "MiB", "GiB", "TiB"][unit]}`;
}
export function time(value?: string) {
  return value
    ? new Date(value).toLocaleString("zh-CN", { hour12: false })
    : "—";
}
export function short(value = "") {
  return value ? `${value.slice(0, 8)}…${value.slice(-6)}` : "—";
}
export const statusLabels: Record<string, string> = {
  QUEUED: "待执行",
  RUNNING: "执行中",
  COMPLETED: "已完成",
  FAILED: "失败",
  PAUSED: "已暂停",
  PAUSING: "正在暂停",
  INTERRUPTED: "曾中断",
};
export const phaseLabels: Record<string, string> = {
  PREPARING: "准备连接",
  CHECKING_TARGET: "校验备份库",
  CLEANUP: "清理暂存",
  CHECKING_CHANGES: "检查变化路径",
  SCANNING: "扫描目录",
  HASHING: "校验内容",
  VERIFYING_RESUME: "校验续传断点",
  TRANSFERRING: "传输文件",
  ARCHIVING: "归档旧版本",
  SNAPSHOT: "提交恢复节点",
  RESTORING: "生成恢复目录",
  ARCHIVE_REVIEW: "核对清理范围",
  ARCHIVE_RETIRE: "移除过期恢复节点",
  ARCHIVE_VERIFY: "核验待清理文件",
  ARCHIVE_REMOVE: "清理归档文件",
  COMPLETE: "完整校验通过",
};
export const executionPhase = (run:{kind:string;phase:string}) => run.kind==="ARCHIVE_CLEANUP" && run.phase==="COMPLETE"
  ? "归档整理完成" : phaseLabels[run.phase];
export const runKindLabel: Record<string,string> = {SYNC:"文件备份",RESTORE:"恢复",ARCHIVE_CLEANUP:"归档整理",STATE_VERIFY:"灾备校验"};
export const protocols = ["LOCAL", "SMB", "SFTP", "FTP", "WEBDAV"];
export const protocolLabel: Record<string, string> = {
  LOCAL: "本地磁盘",
  SMB: "SMB",
  FTP: "FTP",
  SFTP: "SFTP",
  WEBDAV: "WebDAV",
};
