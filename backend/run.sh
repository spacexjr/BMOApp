#!/usr/bin/env bash

#!/bin/bash

echo '=============================================='

# Azul-turquesa do BMO
C1='\033[38;5;80m'
# Azul mais claro
C2='\033[38;5;117m'
# Verde da tela
C3='\033[38;5;119m'
# Azul escuro
C4='\033[38;5;24m'
# Vermelho dos botões
C5='\033[38;5;203m'
RESET='\033[0m'

printf "${C1}"
echo '  ███████╗ █████╗ ████████╗██╗   ██╗██████╗ ███╗   ██╗'
echo '  ██╔════╝██╔══██╗╚══██╔══╝██║   ██║██╔══██╗████╗  ██║'

printf "${C2}"
echo '  ███████╗███████║   ██║   ██║   ██║██████╔╝██╔██╗ ██║'

printf "${C1}"
echo '  ╚════██║██╔══██║   ██║   ██║   ██║████╗   ██║╚██╗██║'
echo '  ███████║██║  ██║   ██║   ╚██████╔╝██  ██╔╝██║ ╚████║'

printf "${C4}"
echo '  ╚══════╝╚═╝  ╚═╝   ╚═╝    ╚═════╝ ╚══  ═╝ ╚═╝  ╚═══╝'

printf "${RESET}\n"

echo '=============================================='

set -euo pipefail

BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$BASE_DIR"

if [ ! -x .venv/bin/python ]; then
  python3 -m venv .venv
  .venv/bin/pip install --upgrade pip
  .venv/bin/pip install -r requirements.txt
fi

export BMO_CONFIG="${BMO_CONFIG:-$BASE_DIR/config.json}"

# Carrega variáveis de .env (não versionado), sem sobrescrever variáveis já exportadas.
if [ -f "$BASE_DIR/.env" ]; then
  set -a
  . "$BASE_DIR/.env"
  set +a
fi

exec .venv/bin/python -m bmo_backend "$@"
