#!/bin/sh
# Minimal gradlew stand-in: requires a Gradle 8.7+ install on PATH or GRADLE_HOME.
# Prefer: gradle wrapper --gradle-version 8.9 (once, with network) to get the real wrapper.
if [ -n "$GRADLE_HOME" ]; then
  exec "$GRADLE_HOME/bin/gradle" "$@"
elif command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
else
  echo "Gradle not found. Install Gradle 8.7+ or set GRADLE_HOME. See README." >&2
  exit 1
fi
