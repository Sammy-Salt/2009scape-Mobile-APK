#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

JRE8_URL="https://github.com/MojoLauncher/android-openjdk-build-multiarch/releases/download/rolling/jre8-pojav.zip"
JRE8_SHA256="a97cf40dc2eb1e642c3b5188260d7c42908a8f603556cc52492bf29d03eab6b3"
JRE17_URL="https://github.com/MojoLauncher/android-openjdk-build-17-25/releases/download/rolling/jre17-pojav.zip"
JRE17_SHA256="cb1978a46c037752d464cfa6430016e68249992791d0d501e101219c2f13617a"

install_runtime() {
  local label="$1" url="$2" expected_sha="$3" destination="$4"
  local archive="$TMP/${label}.zip"

  echo "Downloading ${label} runtime..."
  curl --fail --location --retry 3 --retry-all-errors --silent --show-error "$url" -o "$archive"
  echo "$expected_sha  $archive" | sha256sum --check --status || {
    echo "SHA-256 mismatch for ${label} runtime." >&2
    exit 1
  }
  unzip -tq "$archive"
  mkdir -p "$destination"
  unzip -oq "$archive" -d "$destination"

  for item in universal.tar.xz bin-arm.tar.xz bin-arm64.tar.xz bin-x86.tar.xz bin-x86_64.tar.xz version; do
    [[ -s "$destination/$item" ]] || {
      echo "Missing ${label} runtime component: $item" >&2
      exit 1
    }
  done

  for item in universal.tar.xz bin-arm.tar.xz bin-arm64.tar.xz bin-x86.tar.xz bin-x86_64.tar.xz; do
    tar -tJf "$destination/$item" >/dev/null
  done
}

verify_freetype_archive() {
  local archive="$1" architecture="$2"
  local listing
  listing="$(tar -tvJf "$archive")"
  if ! printf '%s\n' "$listing" | awk '$1 ~ /^-/ && $NF ~ /(^|\/)libfreetype\.so(\..*)?$/ { found=1 } END { exit !found }'; then
    echo "Missing a regular FreeType library in $architecture runtime archive: $archive" >&2
    printf '%s\n' "$listing" | grep -i freetype >&2 || true
    exit 1
  fi
  echo "Verified $architecture FreeType payload:"
  printf '%s\n' "$listing" | grep -E '(^|[[:space:]])[^[:space:]]*libfreetype\.so(\.[^[:space:]]*)?$' || true
}

JRE8_DIR="$ROOT/app_pojavlauncher/src/main/assets/components/jre"
JRE17_DIR="$ROOT/app_pojavlauncher/src/main/assets/components/jre-new"
install_runtime "jre8" "$JRE8_URL" "$JRE8_SHA256" "$JRE8_DIR"
install_runtime "jre17" "$JRE17_URL" "$JRE17_SHA256" "$JRE17_DIR"

# The upstream JRE 8 version has not changed since the broken runtime was first
# installed. Bump the installed binpack version so devices replace the stale
# Internal runtime instead of treating it as current.
verify_freetype_archive "$JRE8_DIR/bin-arm64.tar.xz" "ARM64"
verify_freetype_archive "$JRE8_DIR/bin-arm.tar.xz" "ARM32"
JRE8_UPSTREAM_VERSION="$(tr -d '\r\n' < "$JRE8_DIR/version")"
[[ -n "$JRE8_UPSTREAM_VERSION" ]] || { echo "JRE 8 version marker is empty." >&2; exit 1; }
printf '%s-freetype-reextract-1\n' "$JRE8_UPSTREAM_VERSION" > "$JRE8_DIR/version"

mkdir -p "$ROOT/out"
cat > "$ROOT/out/runtime-sources.txt" <<EOF
JRE 8 URL: $JRE8_URL
JRE 8 SHA-256: $JRE8_SHA256
JRE 8 upstream version: $JRE8_UPSTREAM_VERSION
JRE 8 installed version: $(cat "$JRE8_DIR/version")
JRE 8 local runtime revision: freetype-reextract-1
JRE 17 URL: $JRE17_URL
JRE 17 SHA-256: $JRE17_SHA256
EOF

echo "Verified JRE 8 and JRE 17 runtime archives for ARM32, ARM64, x86, and x86_64."
