#!/bin/sh
set -e
WRAPPER_JAR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/.mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Downloading Maven Wrapper..."
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar" -o "$WRAPPER_JAR"
fi
exec java -Dmaven.multiModuleProjectDirectory=. -classpath "$WRAPPER_JAR" org.apache.maven.wrapper.MavenWrapperMain "$@"
