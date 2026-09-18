#!/usr/bin/env bash
set -euo pipefail

apk=${1:?Usage: verify_release_signer.sh APK [EXPECTED_SHA256_FILE]}
expected_file=${2:-release/signing-certificate.sha256}

if [[ ! -f "$apk" ]]; then
  echo "APK introuvable : $apk" >&2
  exit 1
fi
if [[ ! -f "$expected_file" ]]; then
  echo "Empreinte de signature introuvable : $expected_file" >&2
  exit 1
fi

apksigner_bin=$(command -v apksigner || true)
if [[ -z "$apksigner_bin" && -n "${ANDROID_HOME:-}" ]]; then
  apksigner_bin=$(find "$ANDROID_HOME/build-tools" -type f -name apksigner -print 2>/dev/null | sort -V | tail -n 1)
fi
if [[ -z "$apksigner_bin" ]]; then
  echo "apksigner est requis pour contrôler la signature" >&2
  exit 1
fi

verification=$($apksigner_bin verify --verbose --print-certs "$apk")
actual=$(printf '%s\n' "$verification" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n 1)
expected=$(tr -d '[:space:]:-' < "$expected_file")
actual=$(printf '%s' "$actual" | tr -d '[:space:]:-')

if [[ -z "$actual" ]]; then
  echo "Impossible de lire l'empreinte du signataire" >&2
  exit 1
fi
if [[ "${actual,,}" != "${expected,,}" ]]; then
  echo "SIGNER_INVALID actual=${actual,,} expected=${expected,,}" >&2
  exit 1
fi

echo "SIGNER_VALID sha256=${actual,,}"
