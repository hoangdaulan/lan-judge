#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
./mvnw -q -DskipTests package
mkdir -p bin
for tool in api worker migrate seed lannet wsprobe loadtest; do
    cp scripts/java-tool.sh "bin/$tool"
    chmod 0755 "bin/$tool"
done
