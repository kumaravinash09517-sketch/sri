#!/usr/bin/env sh
# Gradle start up script for UN*X
if [ -z "$DEFAULT_JVM_OPTS" ]; then
  DEFAULT_JVM_OPTS="-Xmx1g"
fi

APP_NAME="Gradle"
APP_BASE_NAME=`basename "$0"`

# Resolve links - $0 may be a link
PRG="$0"
while [ -h "$PRG" ]; do
  ls=`ls -ld "$PRG"`
  link=`expr "$ls" : '.*-> \(.*\)$'`
  if expr "$link" : '/.*' > /dev/null; then
    PRG="$link"
  else
    PRG=`dirname "$PRG"`"/$link"
  fi
done

PRGDIR=`dirname "$PRG"`
EXECUTABLE="${PRGDIR}/gradle"

CLASSPATH="$PRGDIR/gradle-wrapper.jar"

# Execute Gradle
"$EXECUTABLE" "$@"
