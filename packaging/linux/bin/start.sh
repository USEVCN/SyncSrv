#!/bin/sh
set -eu
umask 027

# No JAVA_HOME, PATH lookup or fallback is used to select the JVM.
package_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
java_bin="$package_root/runtime/bin/java"
if [ ! -x "$java_bin" ]; then
  printf '包内 Java 运行时不存在或不可执行：%s\n不会使用系统 Java。\n' "$java_bin" >&2
  exit 1
fi
if [ ! -f "$package_root/app/syncsrv.jar" ]; then
  printf '缺少应用文件：%s/app/syncsrv.jar\n' "$package_root" >&2
  exit 1
fi

runtime_info=0
if [ "${1:-}" = "--runtime-info" ]; then
  runtime_info=1
  shift
else
  environment_file=${SYNCSRV_ENV_FILE:-$package_root/conf/syncsrv.env}
  if [ ! -f "$environment_file" ]; then
    printf '请先从 conf/syncsrv.env.example 创建配置：%s\n' "$environment_file" >&2
    exit 1
  fi
  set -a
  . "$environment_file"
  set +a
fi

# Prevent host JVM options or native library settings from leaking into this JVM.
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH
unset LD_PRELOAD LD_LIBRARY_PATH JAVA_OPTS
JAVA_HOME="$package_root/runtime"
export JAVA_HOME

if [ "$runtime_info" = 1 ]; then
  printf 'Bundled Java: %s\n' "$java_bin"
  exec "$java_bin" -version
fi

SYNCSRV_DATA=${SYNCSRV_DATA:-$package_root/data}
case "$SYNCSRV_DATA" in
  /*) ;;
  *) printf 'SYNCSRV_DATA 必须使用绝对路径。\n' >&2; exit 1 ;;
esac
export SYNCSRV_DATA
# Agent downloads belong to the installation, not the process data/working directory.
# Preserve an explicitly empty value (disables downloads) and custom locations.
if [ "${SYNCSRV_AGENT_RELEASES-auto}" = auto ]; then
  SYNCSRV_AGENT_RELEASES="$package_root/agent-releases"
fi
export SYNCSRV_AGENT_RELEASES
mkdir -p "$SYNCSRV_DATA"
cd "$SYNCSRV_DATA"

# Expose only explicit memory settings; arbitrary inherited JAVA_OPTS is ignored.
heap_min=${SYNCSRV_HEAP_MIN_MIB:-64}
heap_max=${SYNCSRV_HEAP_MAX_MIB:-512}
for heap_value in "$heap_min" "$heap_max"; do
  case "$heap_value" in
    ''|*[!0-9]*) printf '堆内存配置必须为正整数 MiB。\n' >&2; exit 1 ;;
  esac
done
if ! [ "$heap_min" -ge 16 ] || ! [ "$heap_max" -ge "$heap_min" ]; then
  printf '堆内存最小值至少 16 MiB，最大值不得小于最小值。\n' >&2
  exit 1
fi
exec "$java_bin" "-Xms${heap_min}m" "-Xmx${heap_max}m" \
  -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -jar "$package_root/app/syncsrv.jar" --spring.config.additional-location="optional:file:/etc/syncsrv/application-private.yml" "$@"
