#!/usr/bin/env bash
# Signs the release APK built by tools/buildcheck with the AOSP test platform
# key, which the MG4 head unit's firmware is signed with (the app shares the
# system UID, so it must carry the same key).
#
#   tools/sign.sh <keys-dir> <out-dir>
#
# <keys-dir> holds platform.pk8 and platform.x509.pem from AOSP's
# build/target/product/security (https://github.com/aosp-mirror/platform_build).
# Needs apksigner (Android SDK build-tools) on PATH or in $APKSIGNER.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
keys="${1:?keys dir}"; out="${2:?output dir}"
apksigner="${APKSIGNER:-apksigner}"
version=$(sed -n 's/.*versionName *"\(.*\)".*/\1/p' "$here/../app/build.gradle" | head -1)
in="$here/buildcheck/app/build/outputs/apk/release/app-release-unsigned.apk"
dest="$out/CarMediaPlayer-$version-MG4-platform-signed.apk"
mkdir -p "$out"
"$apksigner" sign --key "$keys/platform.pk8" --cert "$keys/platform.x509.pem" --out "$dest" "$in"
rm -f "$dest.idsig"
"$apksigner" verify --print-certs "$dest" | grep -i "SHA-256"
echo "$dest"
