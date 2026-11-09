#!/usr/bin/env bash
set -euo pipefail

APK="${1:?Usage: verify-apk.sh path/to.apk runtime|no-runtime}"
MODE="${2:?Usage: verify-apk.sh path/to.apk runtime|no-runtime}"
[[ -s "$APK" ]] || { echo "APK missing or empty: $APK" >&2; exit 1; }
unzip -tq "$APK"
CONTENTS="$(unzip -Z1 "$APK")"
for entry in classes.dex assets/rt4.jar lib/arm64-v8a/libpojavexec.so lib/arm64-v8a/libgl4es_114.so; do
  grep -Fxq "$entry" <<< "$CONTENTS" || {
    echo "Required APK entry missing: $entry ($APK)" >&2
    exit 1
  }
done

if [[ "$MODE" == "runtime" ]]; then
  for entry in assets/components/jre/universal.tar.xz assets/components/jre/bin-arm.tar.xz assets/components/jre/bin-arm64.tar.xz assets/components/jre-new/universal.tar.xz assets/components/jre-new/bin-arm.tar.xz assets/components/jre-new/bin-arm64.tar.xz; do
    grep -Fxq "$entry" <<< "$CONTENTS" || {
      echo "Runtime APK is missing required JRE asset: $entry" >&2
      exit 1
    }
  done
elif [[ "$MODE" == "no-runtime" ]]; then
  if grep -Eq '^assets/components/jre(-new)?/' <<< "$CONTENTS"; then
    echo "No-runtime APK unexpectedly contains bundled JRE assets." >&2
    exit 1
  fi
else
  echo "Unknown APK verification mode: $MODE" >&2
  exit 2
fi

echo "APK structure verified ($MODE): $APK"
