#!/bin/sh
set -eu
umask 077
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd -P)
set -a
. "${SYNCSRV_ENV_FILE:-/etc/syncsrv/syncsrv.env}"
SPRING_CONFIG_ADDITIONAL_LOCATION=${SPRING_CONFIG_ADDITIONAL_LOCATION:-file:/etc/syncsrv/application-private.yml}
export SPRING_CONFIG_ADDITIONAL_LOCATION
set +a
unset JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS CLASSPATH LD_PRELOAD LD_LIBRARY_PATH
exec "$root/runtime/bin/java" -jar "$root/app/syncsrv.jar" initialize-state
