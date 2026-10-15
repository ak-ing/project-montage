#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
. ./scripts/cloud-env.sh
# Supply DISPLAY from an existing graphical session (e.g. the cloud's Xorg dummy display).
exec ./kotlin run -m desktop-app --no-compose-hot-reload \
  --jvm-args="-Dskiko.data.path=/workspace/.tooling/skiko -Dskiko.renderApi=SOFTWARE --enable-native-access=ALL-UNNAMED" "$@"
