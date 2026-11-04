#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
environment_file=${SYNCSRV_ENV_FILE:-./.env}
if [ -f "$environment_file" ]; then
  set -a
  . "$environment_file"
  set +a
fi
if [ ! -f Backend/target/syncsrv.jar ]; then
  printf '请先执行 sh scripts/build.sh\n' >&2
  exit 1
fi
# Run an immutable copy so a later Maven build cannot invalidate the running classloader.
release_hash=$(shasum -a 256 Backend/target/syncsrv.jar | cut -d ' ' -f 1)
release_dir="work/releases/$release_hash"
mkdir -p "$release_dir"
if [ ! -f "$release_dir/syncsrv.jar" ]; then
  release_temp="$release_dir/syncsrv.$$.tmp"
  cp Backend/target/syncsrv.jar "$release_temp"
  copied_hash=$(shasum -a 256 "$release_temp" | cut -d ' ' -f 1)
  if [ "$copied_hash" != "$release_hash" ]; then
    rm "$release_temp"
    printf '构建包正在变化，请在构建完成后重新启动。\n' >&2
    exit 1
  fi
  mv "$release_temp" "$release_dir/syncsrv.jar"
fi
exec java ${SYNCSRV_JAVA_OPTS:--Xms64m -Xmx512m} -jar "$release_dir/syncsrv.jar" "$@"
