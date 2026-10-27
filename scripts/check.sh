#!/usr/bin/env bash
# Verificações rápidas do projeto.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

echo "[check] Validando esquemas JSON"
for file in "$ROOT"/protocol/schemas/*.json; do
  python3 -m json.tool "$file" >/dev/null
  echo "[ok] $file"
done

echo "[check] Confirmando ausência de permissão de câmera"
if grep -q 'android.permission.CAMERA' "$ROOT/android-app/app/src/main/AndroidManifest.xml"; then
  echo "ERRO: permissão de câmera encontrada no manifesto." >&2
  exit 1
fi
echo "[ok] sem permissão de câmera"

if [ ! -x "$ROOT/backend/.venv/bin/python" ]; then
  echo "ERRO: backend/.venv não existe. Rode ./scripts/setup_backend.sh" >&2
  exit 1
fi

echo "[check] Testes do backend"
cd "$ROOT/backend"
.venv/bin/python -m pytest -q

echo "[check] Testes unitários do Android"
cd "$ROOT/android-app"
./gradlew testDebugUnitTest

echo "[check] Build debug do Android"
./gradlew assembleDebug

echo "[check] Tudo certo."
