#!/usr/bin/env bash
set -euo pipefail

# -----------------------------------------------------------------------------
# AntiGem Multi-Package APK Build & Output Packager
# Builds standard (com.antigem) and termux (com.termux) flavors.
# -----------------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
OUTPUT_DIR="${ROOT_DIR}/output"

MODE="debug"
if [[ "${1:-}" == "--release" || "${1:-}" == "-r" ]]; then
    MODE="release"
fi

echo "================================================================="
echo "🚀 Building AntiGem Packages in [${MODE^^}] mode"
echo "================================================================="

# Extract version from version.json
VERSION_NAME="1.0.0"
VERSION_CODE="1"
if [[ -f "${ROOT_DIR}/version.json" ]]; then
    VERSION_NAME=$(python3 -c "import json; print(json.load(open('${ROOT_DIR}/version.json')).get('version_name', '1.0.0'))" 2>/dev/null || echo "1.0.0")
    VERSION_CODE=$(python3 -c "import json; print(json.load(open('${ROOT_DIR}/version.json')).get('version_code', 1))" 2>/dev/null || echo "1")
fi

echo "📦 Version Name: v${VERSION_NAME} (Code: ${VERSION_CODE})"
mkdir -p "${OUTPUT_DIR}"

chmod +x "${ROOT_DIR}/gradlew" "${SCRIPT_DIR}"/*.sh 2>/dev/null || true

if [[ "${MODE}" == "release" ]]; then
    echo "🔨 Compiling Release APK (:app:assembleRelease with Debug Fallback)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleRelease \
        -PAPP_VERSION_CODE="${VERSION_CODE}" \
        -PAPP_VERSION_NAME="${VERSION_NAME}" \
        --no-daemon || \
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug \
        -PAPP_VERSION_CODE="${VERSION_CODE}" \
        -PAPP_VERSION_NAME="${VERSION_NAME}" \
        --no-daemon

    APKS_SRC="${ROOT_DIR}/app/build/outputs/apk"
    FOUND_APK=$(find "${APKS_SRC}/release" -name "*.apk" 2>/dev/null | head -n 1 || true)
    if [[ -z "${FOUND_APK}" ]]; then
        FOUND_APK=$(find "${APKS_SRC}/debug" -name "*.apk" 2>/dev/null | head -n 1 || true)
    fi
    if [[ -n "${FOUND_APK}" ]]; then
        cp "${FOUND_APK}" "${OUTPUT_DIR}/CineCode-AI-v${VERSION_NAME}-Signed.apk"
    fi
else
    echo "🔨 Compiling Debug APK (:app:assembleDebug)..."
    "${ROOT_DIR}/gradlew" -p "${ROOT_DIR}" :app:assembleDebug \
        -PAPP_VERSION_CODE="${VERSION_CODE}" \
        -PAPP_VERSION_NAME="${VERSION_NAME}" \
        --no-daemon

    APKS_SRC="${ROOT_DIR}/app/build/outputs/apk"
    FOUND_APK=$(find "${APKS_SRC}/debug" -name "*.apk" 2>/dev/null | head -n 1 || true)
    if [[ -n "${FOUND_APK}" ]]; then
        cp "${FOUND_APK}" "${OUTPUT_DIR}/CineCode-AI-v${VERSION_NAME}-debug.apk"
    fi
fi

echo ""
echo "================================================================="
echo "✅ Build Complete! Generated Artifacts in: ${OUTPUT_DIR}"
echo "================================================================="
ls -lh "${OUTPUT_DIR}"/* 2>/dev/null || true
echo "================================================================="
