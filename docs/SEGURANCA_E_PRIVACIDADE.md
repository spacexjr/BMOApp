# Segurança e privacidade

O BMO foi projetado para uso local, doméstico e offline depois da instalação dos modelos. Ele **não** é um serviço para exposição na internet.

## Modelo de ameaça

O que o projeto tenta proteger:

- acesso não autorizado ao microfone/backend por outras máquinas da rede;
- leitura casual do token no celular;
- envio de áudio ou texto para serviços de nuvem;
- execução de comandos arbitrários pelo modelo de linguagem;
- exposição de câmera e fotos.

O que **não** está no escopo:

- proteção contra alguém com acesso físico desbloqueado ao celular;
- proteção contra root/ADB no aparelho;
- TLS ou autenticação de usuários múltiplos;
- proteção contra um atacante que já controla o PC ou a rede local;
- isolamento contra outros apps maliciosos no mesmo celular.

## Autenticação

HTTP e WebSocket exigem token Bearer:

```http
Authorization: Bearer <token>
```

O token é gerado com `secrets.token_urlsafe(32)` e fica em `backend/data/token` (permissão `600` quando possível). Também pode ser definido por `BMO_TOKEN`.

- O backend usa comparação de tempo constante (`secrets.compare_digest`).
- Token inválido no WebSocket gera `unauthorized` e fecha com 4401.
- Token inválido em HTTP gera `401`.
- O endpoint `/health` é público de propósito, mas não expõe dados sensíveis.
- `/docs` é público na rede local; se isso incomodar, desative ou restrinja no Uvicorn/reverse proxy.

Boas práticas:

- use um token longo e aleatório;
- não reutilize o token em outros serviços;
- se o token vazar, apague `backend/data/token` com o backend parado e reinicie para gerar outro;
- não faça port forwarding de `8765` no roteador;
- se possível, use uma VLAN/rede separada para dispositivos IoT.

## Token no Android

O app não guarda o token em texto puro. Ele usa:

- `SharedPreferences` para host, porta, volume e preferências;
- **Android Keystore** com chave AES de 256 bits;
- **AES/GCM/NoPadding** para cifrar o token;
- IV aleatório anexado ao texto cifrado;
- Base64 no armazenamento.

A chave privada fica no Keystore e não é exportável. Se o Keystore for invalidado (por exemplo, restauração do app ou mudança de bloqueio), o token precisa ser digitado novamente.

O backup do app está desativado (`android:allowBackup="false"`).

## Áudio

- O microfone captura PCM 16 kHz mono em blocos de 20 ms.
- Os blocos são enviados ao PC somente enquanto a escuta está ativa.
- O app **não grava áudio em arquivo**.
- O backend mantém o áudio da fala atual em memória para o VAD/STT e descarta depois do processamento.
- O TTS gera WAV em diretório temporário no PC; o arquivo é removido ao fim da síntese.
- O cache de áudio HTTP fica em memória, com TTL de 300 s (configurável).
- O teste de microfone no app é local e não envia áudio.

O backend pode manter logs de eventos e erros. O app mantém até 300 entradas de log em memória, incluindo transcrições e respostas; elas somem ao fechar o processo. Evite mostrar a tela de logs para terceiros.

## Sem câmera e sem nuvem

- O manifesto não declara `CAMERA` nem permissões de fotos/armazenamento.
- Não há biblioteca de câmera.
- O app não faz upload para nuvem.
- O backend conversa com o Ollama em `127.0.0.1:11434`; não envia áudio para APIs externas.
- Whisper.cpp, Piper e espeak-ng rodam localmente.

Se você trocar o Ollama por um provedor remoto, a política de privacidade muda. O projeto padrão é local.

## Comandos e LLM

O LLM pode sugerir ações, mas o backend só autoriza:

- `get_time`
- `get_date`

Qualquer outro JSON de ação é ignorado. Não há execução de shell, leitura de arquivos arbitrários, acesso à rede pelo modelo ou controle do aparelho. O prompt do sistema também instrui o modelo a não inventar ações.

## HTTP sem TLS

A API usa `http://` e `ws://` para funcionar em LAN offline. Isso significa que:

- o token e o áudio trafegam **sem criptografia** na rede local;
- alguém com acesso ao mesmo Wi-Fi e capacidade de sniffing pode ver o tráfego;
- não use redes públicas, de hotel ou corporativas sem controle.

Mitigações:

- mantenha o backend apenas na LAN;
- use WPA2/WPA3;
- evite redes com isolamento de clientes quebrado ou muitos dispositivos desconhecidos;
- para uso mais sério, coloque um reverse proxy com TLS e um certificado local, o que não está implementado neste projeto.

## Limites do Android

- O serviço em primeiro plano mantém o microfone com a tela apagada, mas o fabricante pode encerrá-lo para economizar bateria. Autorize o app nas exceções de bateria.
- A permissão de notificações é opcional, mas necessária para ver o aviso do serviço em algumas versões.
- O app pode ser interrompido pelo sistema; a reconexão automática tenta voltar.

## Dados e retenção

| Dado | Onde fica | Retenção |
|---|---|---|
| Token | PC (`data/token`), celular (Keystore) | até ser trocado/apagado |
| Áudio do microfone | memória do app/backend | descartado após processamento |
| WAV de TTS | temp do PC / cache HTTP | temp removido; cache 300 s |
| Histórico de conversa | memória do backend | até desconectar/apagar |
| Logs do backend | `backend/data/bmo-backend.log` | rotação de 2 MB × 3 |
| Logs do app | memória | até 300 entradas/processo |

Para apagar o histórico no app: **Ajustes → Apagar histórico**. Para apagar os logs do backend, pare o serviço e remova `backend/data/bmo-backend.log`.

## Checklist de segurança

- [ ] Token alterado em relação ao padrão e não publicado.
- [ ] Backend escutando apenas na LAN, sem port forwarding.
- [ ] Firewall liberando 8765/tcp e 8766/udp somente na rede local.
- [ ] Wi-Fi com senha forte e WPA2/WPA3.
- [ ] App com permissão de microfone apenas quando necessário.
- [ ] Notificação do serviço em primeiro plano visível.
- [ ] `backend/data/token` fora do Git.
- [ ] `android-app/keystore/` fora do Git.
- [ ] Nenhum print ou issue com token/transcrições sensíveis.
