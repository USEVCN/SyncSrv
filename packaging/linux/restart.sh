#!/bin/sh
set -eu
exec systemctl restart syncsrv.service "$@"
