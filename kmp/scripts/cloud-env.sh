#!/bin/sh
# Source this file only in the managed /workspace cloud environment.
# Do not put credentials here; preserve the platform's injected proxy and CA configuration.
export KOTLIN_CLI_BOOTSTRAP_CACHE_DIR="${KOTLIN_CLI_BOOTSTRAP_CACHE_DIR:-/workspace/.tooling/kotlin-cli}"
export KOTLIN_SHARED_CACHE_DIR="${KOTLIN_SHARED_CACHE_DIR:-/workspace/.tooling/kotlin-cache}"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/workspace/.tooling/android-backend-cache}"
export KOTLIN_CLI_NO_WELCOME_BANNER=1
# The CLI's Java HTTP client does not consume HTTPS_PROXY. Use the same managed
# proxy as /etc/codex/network-policy.json, with the system CA trust store.
export KOTLIN_CLI_JAVA_OPTIONS="${KOTLIN_CLI_JAVA_OPTIONS:-} -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts"
export ANDROID_HOME="${ANDROID_HOME:-/workspace/.tooling/android-sdk}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-/workspace/.tooling/android-user}"
export XDG_CACHE_HOME="${XDG_CACHE_HOME:-/workspace/.tooling/xdg-cache}"
