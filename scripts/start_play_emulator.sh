#!/usr/bin/env bash
set -euo pipefail

# Keep the video decoder workaround local to this test emulator.
# The API 37 comparison image had black video; use the Play-enabled API 35 AVD.
rebyte_sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -z "$rebyte_sdk" ]]; then
  echo "Set ANDROID_HOME to your Android SDK directory." >&2
  exit 1
fi
rebyte_avd="${REBYTE_TEST_AVD:-Rebyte_Play_API_35}"
rebyte_port="${REBYTE_TEST_PORT:-5586}"

exec "$rebyte_sdk/emulator/emulator" \
  -avd "$rebyte_avd" \
  -port "$rebyte_port" \
  -no-snapshot-load -no-snapshot-save \
  -no-metrics -crash-report-mode never \
  -gpu auto -feature -HardwareDecoder \
  -no-boot-anim \
  "$@"
