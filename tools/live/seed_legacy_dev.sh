#!/usr/bin/env bash
# WP-8.B / D24: seeds a connected device's installed legacy fixture
# (com.example.dev built from commit 11373bb, see build_legacy_dev_apk.sh)
# with scratch-account state, ahead of live gate scenario 10 (upgrade path).
#
# This only pushes seed data via `adb`; it does not build or install the
# APK (see build_legacy_dev_apk.sh) and does not touch the Kotlin schema --
# WP-8.2 has not landed yet in W0, so no Room/DataStore assumptions are made
# here beyond what commit 11373bb itself already persists.
set -euo pipefail

PKG="com.example.dev"
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/seed"

if ! command -v adb >/dev/null 2>&1; then
  echo "error: adb not found on PATH" >&2
  exit 1
fi

DEVICE_COUNT="$(adb devices | grep -c 'device$' || true)"
if [[ "${DEVICE_COUNT}" -lt 1 ]]; then
  echo "error: no adb device/emulator attached" >&2
  exit 1
fi

if ! adb shell pm list packages | grep -q "${PKG}"; then
  echo "error: ${PKG} is not installed; run build_legacy_dev_apk.sh and install the fixture first" >&2
  exit 1
fi

if [[ ! -d "${SEED_DIR}" ]]; then
  echo "note: no seed payload at ${SEED_DIR}; nothing to push. Scenario 10 owners add" >&2
  echo "      per-scenario seed files here (e.g. localStorage export) as they land." >&2
  exit 0
fi

for f in "${SEED_DIR}"/*; do
  base="$(basename "${f}")"
  echo "pushing ${base} into ${PKG} files dir"
  adb push "${f}" "/data/local/tmp/${base}"
  adb shell run-as "${PKG}" cp "/data/local/tmp/${base}" "files/${base}" || {
    echo "error: run-as copy failed for ${base} (is the device a debuggable/userdebug build?)" >&2
    exit 1
  }
  adb shell rm "/data/local/tmp/${base}"
done

echo "Legacy dev fixture seeded for ${PKG}."
