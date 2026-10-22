#!/bin/sh
set -eu
PROJECT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
exec "${JAVA_BIN}java" -Djava.net.useSystemProxies=true -jar "$PROJECT_DIR/build/OCIBastion_Java.jar"
