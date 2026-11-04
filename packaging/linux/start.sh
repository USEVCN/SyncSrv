#!/bin/sh
set -eu
exec systemctl start syncsrv.service "$@"
