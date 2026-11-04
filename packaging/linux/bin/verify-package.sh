#!/bin/sh
set -eu
package_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
cd "$package_root"
if ! command -v sha256sum >/dev/null 2>&1; then
  printf '需要 sha256sum 校验安装包（GNU coreutils）。\n' >&2
  exit 1
fi
sha256sum --check --quiet SHA256SUMS
printf '应用、启动脚本和包内 Java 文件校验通过。\n'
