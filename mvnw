#!/bin/sh
set -e
BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
JAVA_CMD="${JAVA_HOME:+$JAVA_HOME/bin/}java"
exec "$JAVA_CMD" -Dmaven.multiModuleProjectDirectory="$BASE_DIR" -cp "$BASE_DIR/.mvn/wrapper/maven-wrapper.jar" org.apache.maven.wrapper.MavenWrapperMain "$@"
