#!/usr/bin/env sh
# Gradle wrapper bootstrap. Requires gradle-wrapper.jar next to this script
# or a local Gradle install.

APP_HOME=$(cd "$(dirname "$0")" && pwd)
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVACMD="$JAVA_HOME/bin/java"
else
  JAVACMD="java"
fi

if [ ! -f "$CLASSPATH" ]; then
  echo "Missing $CLASSPATH"
  echo "Install Android Studio/SDK and generate the wrapper, or copy gradle-wrapper.jar here."
  exit 1
fi

exec "$JAVACMD" -Xmx64m -Xms64m -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
