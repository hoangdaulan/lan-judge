#!/bin/sh
set -eu
tool=${0##*/}
backend_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
exec java -jar "$backend_dir/target/lan-judge.jar" "$tool" "$@"
