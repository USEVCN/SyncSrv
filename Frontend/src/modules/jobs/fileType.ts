export function fileType(entry: { name: string; directory: boolean; symbolic: boolean }, archived = false): string {
  if (entry.symbolic) return "符号链接";
  if (entry.directory) return "文件夹";
  const name = (archived ? entry.name.replace(/\.__v[a-f0-9]{32}\.__(?:deleted|replaced)$/i, "") : entry.name).toLowerCase();
  if (name.endsWith(".tar.gz")) return "TAR.GZ";
  if (name.endsWith(".tar.bz2")) return "TAR.BZ2";
  if (name.endsWith(".tar.xz")) return "TAR.XZ";
  const dot = name.lastIndexOf(".");
  return dot > 0 && dot < name.length - 1 ? name.slice(dot + 1).toUpperCase() : "文件";
}
