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
        FINAL_APK="${OUTPUT_DIR}/CineCode-AI-v${VERSION_NAME}-Signed.apk"
        SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
        BUILD_TOOLS_DIR=""
        if [[ -n "${SDK_ROOT}" && -d "${SDK_ROOT}/build-tools" ]]; then
            BUILD_TOOLS_DIR="${SDK_ROOT}/build-tools/$(ls "${SDK_ROOT}/build-tools" | sort -V | tail -n 1)"
        fi
        KS_FILE="${ROOT_DIR}/cinecode-release.jks"
        if [[ ! -f "${KS_FILE}" ]]; then
            KS_FILE="${ROOT_DIR}/debug.keystore"
        fi
        if [[ ! -f "${KS_FILE}" ]]; then
            keytool -genkeypair -v -keystore "${KS_FILE}" -storepass android \
                -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 \
                -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
        fi
        if [[ -n "${BUILD_TOOLS_DIR}" && -x "${BUILD_TOOLS_DIR}/zipalign" && -x "${BUILD_TOOLS_DIR}/apksigner" ]]; then
            ALIGNED_TEMP="${OUTPUT_DIR}/aligned-temp.apk"
            "${BUILD_TOOLS_DIR}/zipalign" -p -f 4 "${FOUND_APK}" "${ALIGNED_TEMP}"
            "${BUILD_TOOLS_DIR}/apksigner" sign \
                --ks "${KS_FILE}" \
                --ks-key-alias "${KEY_ALIAS:-androiddebugkey}" \
                --ks-pass "pass:${KEYSTORE_PASS:-android}" \
                --key-pass "pass:${KEY_PASS:-android}" \
                --v1-signing-enabled true \
                --v2-signing-enabled true \
                --v3-signing-enabled true \
                --out "${FINAL_APK}" \
                "${ALIGNED_TEMP}"
            rm -f "${ALIGNED_TEMP}"
            "${BUILD_TOOLS_DIR}/apksigner" verify --verbose --print-certs "${FINAL_APK}"
        else
            cp "${FOUND_APK}" "${FINAL_APK}"
        fi
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
