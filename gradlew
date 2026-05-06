#!/bin/sh

##############################################################################
# Gradle wrapper shell script
##############################################################################

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
GRADLE_WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
GRADLE_WRAPPER_PROPERTIES="$APP_HOME/gradle/wrapper/gradle-wrapper.properties"

# Используем системный java
JAVA_EXE="java"

exec "$JAVA_EXE" \
  -classpath "$GRADLE_WRAPPER_JAR" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
