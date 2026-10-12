#!/usr/bin/env bash
# WP-8.B / D24: builds the legacy upgrade fixture APK.
#
# Checks out commit 11373bb (schema v6, pre-Stage-8) into a disposable git
# worktree, applies ONLY the applicationIdSuffix ".dev" patch to
# app/build.gradle.kts (so the fixture installs side-by-side with the
# current dev build under the same debug keystore), runs assembleDebug,
# and copies the resulting APK + sha256 into fixtures/legacy/.
#
# The build uses the default ~/.android/debug.keystore -- the same keystore
# every Stage 8 dev build uses -- so the fixture's certificate matches the
# current dev APK's certificate (verified by WP-8.B's acceptance test via
# `apksigner verify --print-certs`).
set -euo pipefail

LEGACY_COMMIT="11373bb"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKTREE_DIR="${REPO_ROOT}/.build/legacy-fixture-worktree"
FIXTURES_DIR="${REPO_ROOT}/fixtures/legacy"
OUT_APK="${FIXTURES_DIR}/questa-11373bb-dev.apk"

cd "${REPO_ROOT}"

if [[ -n "$(git status --porcelain)" ]]; then
  echo "error: working tree is dirty; commit or stash before building the legacy fixture" >&2
  exit 1
fi

rm -rf "${WORKTREE_DIR}"
mkdir -p "${FIXTURES_DIR}"

git worktree add --detach "${WORKTREE_DIR}" "${LEGACY_COMMIT}"
trap 'git worktree remove --force "${WORKTREE_DIR}" >/dev/null 2>&1 || true' EXIT

# The build reads git-ignored local files; copy them from the main checkout.
for f in .env.example local.properties; do
  if [[ -f "${REPO_ROOT}/${f}" ]]; then
    cp "${REPO_ROOT}/${f}" "${WORKTREE_DIR}/${f}"
  fi
done

BUILD_FILE="${WORKTREE_DIR}/app/build.gradle.kts"
if grep -q 'debug { }' "${BUILD_FILE}"; then
  # Legacy build file: empty debug block -> add the suffix.
  sed -i 's/debug { }/debug { applicationIdSuffix = ".dev" }/' "${BUILD_FILE}"
elif ! grep -q 'applicationIdSuffix = "\.dev"' "${BUILD_FILE}"; then
  echo "error: could not locate the legacy debug{} block to patch in ${BUILD_FILE}" >&2
  echo "       (D24 requires ONLY the applicationIdSuffix \".dev\" patch -- update this script by hand)" >&2
  exit 1
fi

pushd "${WORKTREE_DIR}" >/dev/null
GRADLE="./gradlew.bat"
[[ -f "./gradlew" ]] && GRADLE="./gradlew"
"${GRADLE}" assembleDebug --console=plain
popd >/dev/null

BUILT_APK="$(find "${WORKTREE_DIR}/app/build/outputs/apk/debug" -name '*.apk' | head -n1)"
if [[ -z "${BUILT_APK}" ]]; then
  echo "error: assembleDebug did not produce an APK under app/build/outputs/apk/debug" >&2
  exit 1
fi

cp "${BUILT_APK}" "${OUT_APK}"
sha256sum "${OUT_APK}" > "${OUT_APK}.sha256"

echo "Legacy fixture built: ${OUT_APK}"
cat "${OUT_APK}.sha256"
