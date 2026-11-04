#!/bin/sh
prompt() {
  printf '%s [%s]：' "$1" "$2"
  IFS= read -r answer || fail '安装已取消。'
  answer=${answer:-$2}
}
verify_package() {
  [ -f "$script_dir/package-info" ] && [ -f "$script_dir/SHA256SUMS" ] || fail '请先完整解压安装包。'
  version=$(sed -n '1p' "$script_dir/package-info")
  package_platform=$(sed -n '2p' "$script_dir/package-info")
  package_arch=$(sed -n '3p' "$script_dir/package-info")
  case "$version" in ''|*[!A-Za-z0-9._-]*) fail '安装包版本无效。' ;; esac
  case "$(uname -s)" in Linux) platform=linux ;; Darwin) platform=darwin ;; *) fail '此系统不支持此安装入口。' ;; esac
  case "$(uname -m)" in x86_64|amd64) arch=amd64 ;; aarch64|arm64) arch=arm64 ;; *) fail '不支持此 CPU 架构。' ;; esac
  [ "$platform/$arch" = "$package_platform/$package_arch" ] || fail "请下载 $platform $arch 对应的压缩包。"
  if command -v sha256sum >/dev/null 2>&1; then hash_tool=sha256sum
  elif command -v shasum >/dev/null 2>&1; then hash_tool=shasum
  else fail '系统缺少 SHA-256 校验工具。'; fi
  seen=' '
  while read -r expected filename extra; do
    [ -z "$extra" ] && [ "${#expected}" = 64 ] || fail '安装包校验清单格式错误。'
    case "$expected" in *[!a-f0-9]*) fail '无效的 SHA-256。' ;; esac
    case "$filename" in ''|.|..|*[!A-Za-z0-9_.-]*) fail '校验清单包含无效文件名。' ;; esac
    case "$seen" in *" $filename "*) fail '校验清单包含重复文件。' ;; esac
    seen="$seen$filename "
    file=$script_dir/$filename
    [ -f "$file" ] && [ ! -L "$file" ] || fail "安装包缺少文件：$filename"
    if [ "$hash_tool" = sha256sum ]; then actual=$(sha256sum "$file" | awk '{print $1}')
    else actual=$(shasum -a 256 "$file" | awk '{print $1}'); fi
    [ "$actual" = "$expected" ] || fail "安装包校验失败：$filename"
  done <"$script_dir/SHA256SUMS"
  for name in syncsrv-agent service-install.sh install.sh install-common.sh uninstall.sh agent-uninstall.sh agent-control.sh package-info; do
    case "$seen" in *" $name "*) ;; *) fail "校验清单缺少：$name" ;; esac
  done
  [ -x "$script_dir/syncsrv-agent" ] || fail '程序没有执行权限，请用保留 Unix 权限的工具解压 tar.gz。'
  reported=$("$script_dir/syncsrv-agent" version)
  [ "$reported" = "$version $platform $arch" ] || fail '程序版本与安装包不一致。'
}
