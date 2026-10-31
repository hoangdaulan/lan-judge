#!/bin/sh
set -eu
mode=${0##*/}
exec java -jar /app/lan-judge.jar "$mode" "$@"
