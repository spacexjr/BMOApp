# BMO App

Aplicativo Android nativo e **offline** do BMO (inspirado em *Adventure Time*),
feito para o **Samsung Galaxy A22 4G (SM-A225M, arm64-v8a)**.

Conversa por texto ou voz, reconhecimento de fala, síntese de voz, palavra de
ativação e ações do aparelho acontecem **no próprio celular**. O aplicativo não
possui permissão de internet e não envia áudio, texto ou histórico para
servidores.

- Pacote: `com.spacexjr.bmo`
- Versão: `2.0.0` (`versionCode 2`)
- Interface: paisagem, imersiva, pt-BR
- Android mínimo: 8.0 (API 26); alvo: API 34
- ABIs: somente `arm64-v8a`

---

## Componentes reais

| Função | Implementação |
|---|---|
| Modelo de linguagem | Qwen2.5-0.5B-Instruct GGUF via llama.cpp/JNI |
| Reconhecimento de fala | Vosk `vosk-model-small-pt-0.3`, embarcado no APK |
| Detecção de fala | Resultados parciais/finais do Vosk (sem limiar de volume) |
| Síntese de voz | Piper VITS pt-BR Faber via sherpa-onnx, embarcado no APK |
| Palavra de ativação | Experimental: gramática “ei bmo” no Vosk (limitada pelo vocabulário) |
| Rostos | PNGs originais do projeto `be-more-agent`, sem redesenho |
| Ações | Lista permitida de APIs Android; sem root e sem shell |

O APK **não inclui o modelo GGUF** de linguagem. Os modelos de voz Vosk e Piper
já estão dentro do APK e são extraídos automaticamente no primeiro uso.

---

## Rostos originais

Os 12 quadros usados vieram de `faces/` do projeto original e estão em
`app/src/main/assets/faces/`:

```text
idle/       idle_01.png
listening/  listen_01.png, listen_02.png
thinking/   thinking_01.png ... thinking_04.png
speaking/   speaking_01.png ... speaking_03.png
error/      error_01.png
warmup/     warmup_01.png
```

`BmoFaceView` apenas decodifica, escala e alterna esses PNGs. O fundo é a cor
original `#BDFFCB`. As expressões seguem o estado real do assistente:

- `LISTENING`: microfone ativo ou fala detectada;
- `THINKING`: inferência ou execução de ação em andamento;
- `SPEAKING`: somente enquanto há áudio sendo reproduzido;
- `ERROR` / `CONFUSED`: falha real de ASR, TTS ou modelo;
- `RESTING`: expressão de repouso escolhida nas configurações.

Não há câmera, fotografia, análise de imagens ou lanterna. Essas permissões e
dependências foram removidas do manifesto e do código.

---

## Como compilar

Requisitos:

- JDK 21
- Android SDK com API 34
- NDK `27.2.12479018`
- CMake `3.22.1`

Exemplo no ambiente usado no desenvolvimento:

```bash
export JAVA_HOME=/home/re/.jdks/jbr-21.0.11
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME=/home/re/Android/Sdk

./gradlew :app:assembleDebug --no-daemon
```

APK gerado:

```text
app/build/outputs/apk/debug/app-debug.apk
```

O projeto nativo fica em `app/src/main/cpp/` (llama.cpp vendorizado + ponte JNI
`bmo_jni.cpp`). O sherpa-onnx vem de `app/libs/sherpa-onnx-1.10.44.aar`.

---

## Instalação no aparelho

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Copiar o modelo GGUF

O arquivo usado nos testes foi:

```text
qwen2.5-0.5b-instruct-q8_0-Q4_K_M-requant.gguf
```

Copie-o para a pasta privada externa do app:

```bash
adb shell mkdir -p /sdcard/Android/data/com.spacexjr.bmo/files/models
adb push qwen2.5-0.5b-instruct-q8_0-Q4_K_M-requant.gguf \
  /sdcard/Android/data/com.spacexjr.bmo/files/models/
```

Ao abrir o app, ele procura automaticamente um arquivo `.gguf` nessa pasta,
dando prioridade a nomes com `qwen2.5`. Também é possível escolher outro GGUF em
**Configurações → Inteligência artificial → Selecionar arquivo GGUF**. Nada é
baixado automaticamente.

> O modelo é carregado em memória e ocupa cerca de 0,5 GB de RAM, além do
> contexto. O aparelho precisa ter espaço e memória livres.

---

## Uso

- **Toque no rosto:** começa a ouvir; se estiver falando, interrompe a fala;
  se estiver ouvindo, encerra a escuta.
- **Toque duplo:** cancela geração, fala e escuta.
- **Toque longo:** abre as configurações.
- **Ícone de teclado:** abre a conversa por texto.
- **Ícone de engrenagem:** abre as configurações.

As respostas são faladas quando a síntese está ligada. O texto gerado é limpo
antes do TTS: markdown, URLs, emojis e símbolos não são pronunciados.

### Comandos diretos

Alguns comandos explícitos não passam pelo modelo; executam a ação real e
respondem com o resultado, reduzindo latência e evitando invenções:

- “Que horas são?”
- “Que dia é hoje?”
- “Qual a bateria?”
- “Qual é o aparelho?”
- “Volume 40”
- “Abrir Wi-Fi”, “abrir Bluetooth”, “abrir configurações de tela”
- “Abrir WhatsApp”, “abrir Relógio”

Outras frases seguem para o Qwen2.5, que pode solicitar uma ferramenta da lista
permitida. Toda ação retorna sucesso ou uma mensagem de erro real; o app não
finge que executou algo.

Ações permitidas: hora, data, abrir aplicativo instalado, abrir uma tela de
configurações do Android, bateria, informações do aparelho, volume de mídia e
lista de apps. Não há shell, root, execução de código arbitrário, câmera,
fotografia ou lanterna.

---

## Voz e detecção de fala

1. O microfone grava PCM 16 kHz mono com `AudioRecord`.
2. O **Vosk** transcreve continuamente.
3. O início da fala é confirmado quando o Vosk devolve um texto parcial válido.
4. O fim é confirmado pelo resultado final do Vosk ou por cerca de 1,1 s sem
   novas hipóteses parciais.
5. Se houver fala válida, o texto é enviado ao assistente.
6. A resposta é sintetizada pelo Piper e reproduzida localmente.

Essa detecção usa o próprio reconhecedor, não um limiar de RMS. Isso melhora a
captura de fala baixa e evita abrir o microfone por ruído alto isolado. Se o
Vosk não reconhecer nenhuma palavra em 6 s, o app avisa e volta ao repouso.

A palavra de ativação “Ei, BMO” também usa Vosk, com uma gramática restrita.
Ela funciona **somente com o app aberto** (não há serviço em segundo plano) e
não substitui um detector neural dedicado de wake word.

> **Limitação real:** o modelo `vosk-model-small-pt-0.3` não possui a palavra
> “bmo” no vocabulário; o próprio Vosk registra que ignora esse termo ao montar
> a gramática. Por isso o recurso está marcado como **experimental** e pode não
> disparar. O toque no rosto e a conversa por texto são os métodos confiáveis.
> O projeto original possui um `wakeword.onnx` do openWakeWord; integrá-lo ao
> Android exigiria ONNX Runtime e o pipeline de mel-espectrograma/embedding.

---

## Configurações funcionais

- Modelo GGUF: selecionar, carregar/recarregar, descarregar e ver o caminho.
- Contexto, limite de tokens, threads e quantidade de mensagens do histórico.
- Apagar o histórico da conversa.
- Reconhecimento de voz, síntese de voz e palavra de ativação.
- Velocidade e volume da voz, teste de fala e botão para parar.
- Expressão de repouso, prévia e escala do rosto.
- Diagnóstico com versão, Android, aparelho, RAM, PSS, modelo, estado do ASR/TTS,
  wake word e últimas mensagens de erro/latência.

Alterações de contexto, threads ou modelo exigem **Carregar / recarregar** para
valer no motor nativo. O app não recarrega o GGUF ao apenas voltar para a tela
principal: se o caminho, o contexto e as threads não mudaram, o modelo já
carregado é reutilizado.

---

## Privacidade e modo offline

- O manifesto não solicita `INTERNET`.
- Áudio, texto, histórico e modelo ficam no aparelho.
- O histórico é mantido em memória e pode ser apagado.
- Não há telemetria, conta, nuvem ou download automático.
- O app depende apenas de permissões de microfone e ajuste de áudio.

---

## Testes realizados no Galaxy A22

Aparelho: SM-A225M, Android 16/API 36, 720×1600, arm64-v8a.

- Build `assembleDebug` e instalação via ADB.
- Carregamento real do Qwen2.5 Q4_K_M pelo llama.cpp.
- Conversa por texto com respostas em pt-BR e TTS Piper local.
- Prompt de conversa reduzido para **68 tokens**.
- Com 4 threads: avaliação do prompt em **~3,1–4,3 s** e geração curta em
  **~0,3–1,0 s**; primeira resposta total em **~3,5–5,3 s**.
- Com cache KV entre turnos: resposta seguinte em **~1,7–1,9 s**.
- Comando direto “que horas são” executou `get_current_time` e respondeu
  “Agora são 22:15”.
- Vosk carregou, detectou fala pelo resultado parcial e transcreveu áudio
  ambiente real; o app respondeu ao texto reconhecido.
- TTS iniciou e concluiu a reprodução local.
- Correção de fala antiga: cada pedido de TTS recebe um identificador; uma
  síntese nova invalida a anterior e resultados atrasados são descartados.
- Modelo já carregado é preservado ao voltar para a atividade.

Também foi observado que o protótipo físico pode receber toques fantasmas do
touchscreen/carcaça durante os testes. Isso não é gerado pelo app; quando um
toque real chega ao rosto, o comportamento esperado é iniciar a escuta.

---

## Limitações atuais

- **Latência:** a primeira resposta ainda leva alguns segundos, principalmente
  pela avaliação do prompt no CPU do A22. A fala só começa após a geração do
  texto completo; não há streaming de tokens para o TTS.
- **Modelo pequeno:** o Qwen2.5-0.5B pode ignorar instruções de ferramenta ou
  produzir respostas estranhas. Por isso os comandos de aparelho mais comuns têm
  caminho direto determinístico.
- **Palavra de ativação:** usa gramática Vosk, apenas com o app em primeiro
  plano. O vocabulário do Vosk pequeno não contém “bmo”, então o recurso é
  experimental e pode não disparar. Não foi validado em ambientes de ruído.
- **Uma única voz:** o APK inclui apenas Piper pt-BR Faber; não há seletor
  fictício de várias vozes.
- **Sem serviço em segundo plano:** ao sair do app, escuta, fala e geração são
  interrompidas.
- **Sem câmera/fotografia/lanterna**, por decisão de escopo.

---

## Estrutura do projeto

```text
app/src/main/java/com/spacexjr/bmo/
  BmoApp.kt                    inicialização dos motores
  MainActivity.kt              tela cheia, toques, diálogo de texto
  SettingsActivity.kt          configurações e diagnóstico
  BmoAssistant.kt              orquestração, histórico, prompt, ações
  actions/ActionManager.kt     ações Android permitidas
  data/SettingsRepository.kt   preferências e migração do modelo
  data/LocalModelStore.kt      extração de Vosk/Piper
  data/DiagnosticsManager.kt   logs e latência
  face/BmoFaceView.kt          renderização dos PNGs originais
  face/ExpressionManager.kt    catálogo das expressões
  llm/LlmEngine.kt             carregamento/geração e cache de modelo
  speech/SpeechRecognizerManager.kt  ASR Vosk + VAD por parciais
  speech/TextToSpeechManager.kt      TTS Piper + cancelamento por geração
  speech/VoskModelProvider.kt        modelo Vosk local
  speech/WakeWordManager.kt          palavra de ativação

app/src/main/cpp/
  bmo_jni.cpp                  ponte JNI, prompt, geração e tempos
  llama.cpp/                   llama.cpp vendorizado

app/src/main/assets/
  faces/                       PNGs originais
  models/vosk-pt.zip           modelo Vosk pt-BR
  models/tts-pt.zip            voz Piper pt-BR
```
