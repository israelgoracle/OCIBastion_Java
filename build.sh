#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}"
mkdir -p build/classes
"${JAVA_BIN}javac" -encoding UTF-8 -source 8 -target 8 -d build/classes src/main/java/ocitools/*.java
"${JAVA_BIN}jar" cfe build/OCIBastion_Java.jar ocitools.Main -C build/classes .
if [ "${1:-}" = "--test" ]; then
    mkdir -p build/test-classes
    "${JAVA_BIN}javac" -encoding UTF-8 -source 8 -target 8 -cp build/classes -d build/test-classes src/test/java/ocitools/*.java
    "${JAVA_BIN}java" -Djava.awt.headless=true -cp build/classes:build/test-classes ocitools.TestSuite "$PWD"
fi
printf '%s\n' 'Built build/OCIBastion_Java.jar'
