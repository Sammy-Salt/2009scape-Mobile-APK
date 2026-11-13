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

inject_freetype() {
  local abi="$1" source_archive="$2" target_archive="$3" runtime_arch="$4" machine="$5"
  local source_file="$TMP/libfreetype-$abi.so"
  local stage="$TMP/jre8-$abi"
  local patched_archive="$TMP/bin-$abi-with-freetype.tar.xz"

  echo "Extracting JRE 17 FreeType for $abi..."
  tar -xJOf "$source_archive" ./lib/libfreetype.so > "$source_file"
  [[ -s "$source_file" ]] || { echo "JRE 17 $abi FreeType is missing or empty." >&2; exit 1; }

  readelf -h "$source_file" | grep -Eq "Machine:.*$machine" || {
    echo "JRE 17 FreeType has the wrong ELF architecture for $abi." >&2
    readelf -h "$source_file" >&2
    exit 1
  }

  rm -rf "$stage"
  mkdir -p "$stage"
  tar -xJf "$target_archive" -C "$stage"
  mkdir -p "$stage/lib/$runtime_arch"
  install -m 0755 "$source_file" "$stage/lib/$runtime_arch/libfreetype.so"
  tar -cJf "$patched_archive" -C "$stage" .
  mv "$patched_archive" "$target_archive"

  local listing
  listing="$(tar -tvJf "$target_archive")"
  printf '%s\n' "$listing" | awk -v path="./lib/$runtime_arch/libfreetype.so" '$1 ~ /^-/ && $NF == path { found=1 } END { exit !found }' || {
    echo "Injected FreeType was not found in the rebuilt JRE 8 $abi archive." >&2
    exit 1
  }
  echo "Injected $abi FreeType: $(sha256sum "$source_file" | cut -d' ' -f1)"
}

JRE8_DIR="$ROOT/app_pojavlauncher/src/main/assets/components/jre"
JRE17_DIR="$ROOT/app_pojavlauncher/src/main/assets/components/jre-new"
install_runtime "jre8" "$JRE8_URL" "$JRE8_SHA256" "$JRE8_DIR"
install_runtime "jre17" "$JRE17_URL" "$JRE17_SHA256" "$JRE17_DIR"

# JRE 8's published Android runtime omits FreeType, but its fontmanager needs it.
# Reuse the same-ABI Android FreeType builds shipped with the verified JRE 17 pack.
inject_freetype "arm64" "$JRE17_DIR/bin-arm64.tar.xz" "$JRE8_DIR/bin-arm64.tar.xz" "aarch64" "AArch64"
inject_freetype "arm32" "$JRE17_DIR/bin-arm.tar.xz" "$JRE8_DIR/bin-arm.tar.xz" "arm" "ARM"

# Force existing devices to replace the stale Internal runtime even though the
# upstream JRE 8 release version itself has not changed.
JRE8_UPSTREAM_VERSION="$(tr -d '\r\n' < "$JRE8_DIR/version")"
[[ -n "$JRE8_UPSTREAM_VERSION" ]] || { echo "JRE 8 version marker is empty." >&2; exit 1; }
printf '%s-freetype-reextract-2\n' "$JRE8_UPSTREAM_VERSION" > "$JRE8_DIR/version"

mkdir -p "$ROOT/out"
cat > "$ROOT/out/runtime-sources.txt" <<EOF
JRE 8 URL: $JRE8_URL
JRE 8 SHA-256: $JRE8_SHA256
JRE 8 upstream version: $JRE8_UPSTREAM_VERSION
JRE 8 installed version: $(cat "$JRE8_DIR/version")
JRE 8 local runtime revision: freetype-reextract-2
JRE 8 ARM64 FreeType SHA-256: $(sha256sum "$TMP/libfreetype-arm64.so" | cut -d' ' -f1)
JRE 8 ARM32 FreeType SHA-256: $(sha256sum "$TMP/libfreetype-arm32.so" | cut -d' ' -f1)
JRE 17 URL: $JRE17_URL
JRE 17 SHA-256: $JRE17_SHA256
EOF

echo "Verified JRE 8 runtime payloads with matching ARM32 and ARM64 FreeType libraries."
