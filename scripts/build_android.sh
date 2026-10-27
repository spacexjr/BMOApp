#!/usr/bin/env bash
# Compila o APK de depuração ou o release assinado.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODE="${1:-debug}"
cd "$ROOT/android-app"

case "$MODE" in
  debug)
    echo "[build] Compilando APK debug"
    ./gradlew assembleDebug
    APK="$ROOT/android-app/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  release)
    PROP="$ROOT/android-app/keystore/keystore.properties"
    if [ ! -f "$PROP" ]; then
      echo "ERRO: keystore não encontrado." >&2
      echo "Execute: ./scripts/generate_release_keystore.sh" >&2
      exit 1
    fi
    STORE_FILE="$(sed -n 's/^storeFile=//p' "$PROP")"
    STORE_PASS="$(sed -n 's/^storePassword=//p' "$PROP")"
    KEY_ALIAS="$(sed -n 's/^keyAlias=//p' "$PROP")"
    KEY_PASS="$(sed -n 's/^keyPassword=//p' "$PROP")"
    if [ -z "$STORE_FILE" ] || [ -z "$STORE_PASS" ] || [ -z "$KEY_ALIAS" ]; then
      echo "ERRO: keystore.properties incompleto." >&2
      exit 1
    fi
    case "$STORE_FILE" in
      /*) KEYSTORE="$STORE_FILE" ;;
      *) KEYSTORE="$ROOT/android-app/$STORE_FILE" ;;
    esac
    if [ ! -f "$KEYSTORE" ]; then
      echo "ERRO: keystore não encontrado em $KEYSTORE" >&2
      exit 1
    fi
    echo "[build] Compilando APK release assinado"
    BMO_KEYSTORE="$KEYSTORE" \
    BMO_KEY_ALIAS="$KEY_ALIAS" \
    BMO_STORE_PASSWORD="$STORE_PASS" \
    BMO_KEY_PASSWORD="$KEY_PASS" \
      ./gradlew assembleRelease
    APK="$ROOT/android-app/app/build/outputs/apk/release/app-release.apk"
    ;;
  *)
    echo "Uso: $0 [debug|release]" >&2
    exit 2
    ;;
esac

if [ ! -f "$APK" ]; then
  echo "ERRO: APK não encontrado em $APK" >&2
  exit 1
fi

echo
echo "[build] APK: $APK"
echo "[build] Tamanho: $(du -h "$APK" | cut -f1)"
echo "[build] SHA-256: $(sha256sum "$APK" | cut -d' ' -f1)"
