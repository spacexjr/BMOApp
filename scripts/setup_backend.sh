#!/usr/bin/env bash
# Prepara o backend Python e verifica as ferramentas locais.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT/backend"

if ! command -v python3 >/dev/null 2>&1; then
  echo "ERRO: python3 não encontrado." >&2
  exit 1
fi

if [ ! -d .venv ]; then
  echo "[setup] Criando ambiente virtual em backend/.venv"
  python3 -m venv .venv
fi

echo "[setup] Instalando dependências Python"
.venv/bin/pip install --upgrade pip >/dev/null
.venv/bin/pip install -r requirements.txt
if [ -f requirements-dev.txt ]; then
  .venv/bin/pip install -r requirements-dev.txt
fi

mkdir -p data
echo "[setup] Gerando/verificando token local"
TOKEN="$(.venv/bin/python -m bmo_backend --print-token)"
if [ -z "$TOKEN" ]; then
  echo "ERRO: não foi possível obter o token." >&2
  exit 1
fi
echo "[setup] Token disponível em backend/data/token"

check_file() {
  local label="$1" path="$2"
  if [ -f "$path" ]; then
    echo "[ok] $label: $path"
  else
    echo "[falta] $label: $path"
  fi
}

check_cmd() {
  local label="$1" cmd="$2"
  if command -v "$cmd" >/dev/null 2>&1; then
    echo "[ok] $label: $(command -v "$cmd")"
  else
    echo "[falta] $label: $cmd"
  fi
}

echo
echo "[setup] Verificação de ferramentas"
check_file "Whisper.cpp" "$ROOT/tools/whisper.cpp/build/bin/whisper-cli"
check_file "Modelo Whisper" "$ROOT/tools/whisper-models/ggml-base.bin"
check_file "Piper" "$ROOT/tools/piper/piper"
check_file "Voz Piper pt-BR" "$ROOT/tools/voices/pt_BR-faber-medium.onnx"
check_cmd "espeak-ng" "espeak-ng"
check_cmd "ffmpeg" "ffmpeg"
check_cmd "Ollama" "ollama"

echo
echo "[setup] Próximos passos:"
echo "  1. ollama serve"
echo "  2. ollama pull qwen3.5:2b"
echo "  3. ./backend/run.sh"
echo "  4. cat backend/data/token"
echo "  5. Instale o APK e configure IP/porta/token no app."
echo
echo "Se algum item estiver faltando, veja docs/INSTALACAO.md."
