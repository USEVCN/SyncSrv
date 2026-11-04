#!/bin/sh
set -eu
exec systemctl status syncsrv.service "$@"
