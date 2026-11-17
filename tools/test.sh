#!/usr/bin/env bash
# Runner unico di 151 Editor: prove della logica sul PC + build + controllo permessi.
# Esce non-zero se una cosa fallisce.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?set JAVA_HOME to a JDK 21}" "${ANDROID_HOME:?set ANDROID_HOME to your Android SDK}"
./gradlew --no-daemon -q testDebugUnitTest assembleDebug assembleRelease
R=app/build/test-results/testDebugUnitTest
grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' "$R"/*.xml
AAPT=$(ls -d "$ANDROID_HOME"/build-tools/*/ | sort -V | tail -1)aapt2
for APK in app/build/outputs/apk/debug/app-debug.apk app/build/outputs/apk/release/app-release*.apk; do
PERM=$("$AAPT" dump permissions "$APK" | grep uses-permission || true)
# Il lettore video di Google aggiunge ACCESS_NETWORK_STATE e WAKE_LOCK: normali.
# RECORD_AUDIO: «Record your voice» (0.11.0), chiesto solo quando si tocca.
ATTESI="uses-permission: name='android.permission.WRITE_EXTERNAL_STORAGE' maxSdkVersion='28'
uses-permission: name='android.permission.RECORD_AUDIO'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.WAKE_LOCK'"
if [ "$PERM" != "$ATTESI" ]; then echo "PERMESSI INATTESI in $APK:"; echo "$PERM"; exit 1; fi
done
echo "permessi: i quattro attesi (il microfono si chiede solo per registrare la voce)"
echo "TUTTO OK"
