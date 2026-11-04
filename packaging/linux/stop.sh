#!/bin/sh
set -eu
exec systemctl stop syncsrv.service "$@"
