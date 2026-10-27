#!/usr/bin/env bash
# Gera uma chave de assinatura local para o APK release.
# A chave e a senha ficam em android-app/keystore/ e NÃO devem ir para o Git.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="$ROOT/android-app/keystore"
KEYSTORE="$DIR/bmo-release.jks"
PROP="$DIR/keystore.properties"

umask 077
mkdir -p "$DIR"

if [ -f "$KEYSTORE" ] || [ -f "$PROP" ]; then
  echo "[keystore] Já existe keystore ou propriedades em $DIR"
  echo "[keystore] Para gerar outra, remova os dois arquivos e rode de novo."
  exit 0
fi

if ! command -v keytool >/dev/null 2>&1; then
  echo "ERRO: keytool não encontrado. Instale um JDK 17+." >&2
  exit 1
fi

PASS="$(openssl rand -base64 24 | tr -d '\n')"
keytool -genkeypair \
  -keystore "$KEYSTORE" \
  -alias bmo \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass "$PASS" \
  -keypass "$PASS" \
  -dname "CN=BMO App, OU=Desenvolvimento Local, O=BMO, L=Local, ST=Local, C=BR"

cat > "$PROP" <<EOF
storeFile=keystore/bmo-release.jks
storePassword=$PASS
keyAlias=bmo
keyPassword=$PASS
EOF

chmod 600 "$KEYSTORE" "$PROP"
echo "[keystore] Criado: $KEYSTORE"
echo "[keystore] Segredo local: $PROP"
echo "[keystore] Não faça commit desses arquivos."
