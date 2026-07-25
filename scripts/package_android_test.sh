#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd "${script_dir}/.." && pwd)"
android_dir="${project_dir}/android"
delivery_dir="${project_dir}/build"
android_java_home="/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home"
android_sdk_root="/opt/homebrew/share/android-commandlinetools"
apk_source="${android_dir}/app/build/outputs/apk/debug/app-debug.apk"
firebase_config="${android_dir}/app/google-services.json"
apk_destination="${delivery_dir}/UNISTBus-0.3.1-fcm-debug.apk"
apksigner="${android_sdk_root}/build-tools/37.0.0/apksigner"

mkdir -p "${delivery_dir}"

if [[ ! -f "${firebase_config}" ]]; then
    echo "Firebase 설정 파일이 없습니다: ${firebase_config}" >&2
    exit 1
fi

(
    cd "${android_dir}"
    JAVA_HOME="${android_java_home}" \
        ANDROID_SDK_ROOT="${android_sdk_root}" \
        ./gradlew -PunistFirebaseEnabled=true \
        :app:clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
)

cp "${apk_source}" "${apk_destination}"
JAVA_HOME="${android_java_home}" "${apksigner}" verify --verbose "${apk_destination}"
shasum -a 256 "${apk_destination}" > "${apk_destination}.sha256"

echo "${apk_destination}"
echo "${apk_destination}.sha256"
