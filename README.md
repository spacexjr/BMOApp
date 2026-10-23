# BMOApp

Aplicativo Android offline do BMO (inspirado em *Adventure Time*), feito para rodar
localmente no **Samsung Galaxy A22 (SM-A225M)**. A conversa, o reconhecimento de
voz, a síntese de voz e a palavra de ativação funcionam no próprio aparelho, sem
enviar áudio ou texto para servidores.

O app usa o modelo **Qwen3-0.6B-Q8_0** via llama.cpp (JNI), **Vosk** para ASR em
português brasileiro, **sherpa-onnx + Piper VITS** para TTS e os SVGs originais do
BMO para as expressões faciais.

> **Importante:** o modelo de linguagem GGUF **não está incluído no APK**.
> Ele precisa ser copiado para o aparelho uma única vez. Os modelos de voz
> (Vosk e Piper) já estão empacotados no APK e são extraídos automaticamente.

---

## Estado do projeto

Implementado e compilando:

- Interface em paisagem, com fundo `#90E5C8`.
- Rosto animado usando 40 SVGs existentes em `app/src/main/assets/faces`.
- Conversa por texto e por voz.
- Inferência real com llama.cpp em `arm64-v8a`.
- ASR offline com Vosk `vosk-model-small-pt-0.3`.
- TTS offline com Piper `pt_BR-faber-medium`.
- Palavra de ativação “Ei, BMO” com Vosk + gate de silêncio RMS.
- Ações permitidas do Android (hora, data, abrir app, configurações, bateria,
  informações do aparelho, volume, lista de apps e lanterna).
- Configurações persistentes e diagnóstico em tela.
- Cancelamento de geração/fala/escuta.

Validado no Galaxy A22 SM-A225M:

- Build e instalação do APK.
- Carregamento do Qwen3 e conversa por texto.
- Reconhecimento de fala e síntese de voz.
- Tela de configurações, orientação em paisagem, cor de fundo e envio pelo teclado.
- Cache de prefixo do prompt entre turnos (reuso do KV cache).

Ainda vale testar em uso real, com o aparelho na mão:

- Todas as ações individuais (abrir apps, lanterna, volume etc.).
- Palavra de ativação em ambiente silencioso e com ruído.
- Transições de microfone entre wake word, ASR e TTS.
- Rolagem e ajustes finos da tela de configurações em paisagem.

### Problemas conhecidos em aberto

- **Demora para pensar e responder.** No Galaxy A22, a primeira resposta pode
  levar em torno de 30 segundos ou mais, e respostas seguintes ainda podem
  demorar bastante, dependendo do tamanho do contexto, do número de tokens e da
  velocidade de geração. A fala só começa depois que o texto inteiro é gerado.
- **Fala repetindo a resposta anterior.** Em alguns testes, a síntese de voz
  reproduziu a resposta do turno anterior em vez da resposta atual. Esse
  comportamento ainda precisa ser corrigido no fluxo de TTS.
- **Emoji narrado.** O texto enviado ao TTS ainda contém emojis, e o Piper tenta
  pronunciá-los ou descrevê-los em vez de ignorá-los. A correção prevista é
  remover emojis e símbolos antes da síntese.

---

## Funcionalidades

### Conversa

- **Toque no rosto:** começa a ouvir; se estiver falando, interrompe a fala;
  se estiver ouvindo, para a escuta.
- **Toque duplo:** cancela geração, fala e escuta em andamento.
- **Toque longo:** abre as configurações.
- **Ícone de teclado:** abre o diálogo de conversa por texto. No teclado, a tecla
  de ação (Enter/Enviar) envia a mensagem.
- **Ícone de engrenagem:** abre as configurações.

As respostas são faladas quando a síntese de voz está habilitada. Se o microfone
não tiver permissão, o app solicita a permissão ao tocar no rosto.

### Expressões faciais

As expressões são os SVGs originais do BMO, renderizados com AndroidSVG. O app
não redesenha os rostos: ele apenas substitui os parâmetros usados pelos templates
originais (`.m`, `.x`, `.t`) e alterna entre os estados disponíveis. Há expressões
para neutro, feliz, triste, bravo, pensando, ouvindo, falando, dormindo, entre
outras.

### Voz

- **ASR:** Vosk pt-BR local, com detecção de início/fim de fala e timeouts.
- **TTS:** Piper VITS pt-BR Faber, executado no aparelho.
- **Velocidade e volume** da fala ajustáveis nas configurações.
- **Testar voz** e **Parar fala** disponíveis na tela de configurações.

### Palavra de ativação

Com a opção ativada, o app escuta “Ei, BMO” enquanto está em primeiro plano.
Variantes aceitas incluem “ei bmo”, “eibmo”, “hey bmo”, “oi bmo” e “ei bemo”.
O detector usa uma gramática restrita do Vosk e um gate de RMS para reduzir CPU.

### Ações do Android

O modelo só pode pedir as ações abaixo. Não existe shell, root, execução de código
arbitrário ou acesso genérico ao sistema.

| Ação | O que faz |
| --- | --- |
| `get_current_time` | Retorna a hora local. |
| `get_current_date` | Retorna a data local. |
| `open_application` | Abre um app instalado pelo nome ou pacote. |
| `open_system_settings` | Abre uma tela de configurações do Android. |
| `get_battery_status` | Retorna nível e estado da bateria. |
| `get_device_info` | Retorna modelo, versão do Android e arquitetura. |
| `set_media_volume` | Ajusta o volume de mídia entre 0 e 100. |
| `list_installed_apps` | Lista apps com ícone na tela inicial. |
| `set_flashlight` | Liga ou desliga a lanterna, quando disponível. |

As ferramentas retornam resultado real. Se uma ação falhar (por exemplo, um app
inexistente ou uma lanterna indisponível), o BMO informa a falha em vez de fingir
que executou.

---

## Privacidade e funcionamento offline

- O APK **não declara a permissão `INTERNET`**.
- Áudio e texto não são enviados para nuvem.
- Nenhum modelo é baixado automaticamente.
- O modelo GGUF é lido do armazenamento local do aparelho.
- Os modelos de voz ficam dentro do APK e são extraídos para o armazenamento
  privado do app na primeira execução.
- O app não usa serviços de ASR/TTS do Google.

---

## Requisitos

### Aparelho

- Android 8.0 (API 26) ou superior.
- Arquitetura **arm64-v8a** (o APK contém apenas essa ABI).
- Microfone.
- Espaço livre:
  - cerca de **220 MB** no armazenamento privado para extrair os modelos de voz;
  - cerca de **610 MB** para o modelo Qwen3-0.6B-Q8_0;
  - espaço adicional para o APK (aproximadamente 145 MB em debug).

Testado no Samsung Galaxy A22 SM-A225M, Android 16.

### Ambiente de build

- Linux (o projeto foi compilado em Linux).
- JDK 17 ou 21 (testado com JDK 21).
- Android SDK com:
  - `platforms;android-34`
  - `build-tools;34.0.0`
  - `ndk;27.2.12479018`
  - `cmake;3.22.1`
- Gradle 8.7 (o wrapper baixa automaticamente).
- AGP 8.2.2 e Kotlin 1.9.22.

Instalação dos componentes do SDK, se necessário:

```bash
sdkmanager --install \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "ndk;27.2.12479018" \
  "cmake;3.22.1"
```

---

## Como compilar

Na raiz do projeto:

```bash
export JAVA_HOME=/caminho/para/jdk-17-ou-21
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"

./gradlew :app:assembleDebug --no-daemon
```

O APK será gerado em:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Para uma build de release:

```bash
./gradlew :app:assembleRelease --no-daemon
```

O projeto usa `local.properties` para apontar para o SDK. Se ele não existir:

```properties
sdk.dir=/home/SEU_USUARIO/Android/Sdk
```

### Observações de build

- O llama.cpp é compilado a partir do código vendorizado em
  `app/src/main/cpp/llama.cpp`.
- A compilação usa `-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod`, adequada ao A22.
- A ABI é limitada a `arm64-v8a` para reduzir tamanho e complexidade.
- O AAR do sherpa-onnx está fixado em **1.10.44** em `app/libs/`.

---

## Como instalar

Com o aparelho conectado por USB e a depuração USB habilitada:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.spacexjr.bmo/.MainActivity
```

Na primeira abertura, o app pode levar alguns segundos para extrair os modelos de
voz. A barra de status mostra o progresso.

---

## Modelo de linguagem (GGUF)

O modelo esperado por padrão é:

```text
Qwen3-0.6B-Q8_0.gguf
```

Caminho padrão no aparelho:

```text
/storage/emulated/0/Android/data/com.spacexjr.bmo/files/models/Qwen3-0.6B-Q8_0.gguf
```

### Opção 1: copiar via ADB

Com o app já instalado (para o diretório existir):

```bash
adb shell mkdir -p /sdcard/Android/data/com.spacexjr.bmo/files/models
adb push assets/models/Qwen3-0.6B-Q8_0.gguf \
  /sdcard/Android/data/com.spacexjr.bmo/files/models/
```

### Opção 2: importar pela interface

1. Abra as configurações do BMO (engrenagem ou toque longo no rosto).
2. Toque em **Selecionar arquivo GGUF**.
3. Escolha o arquivo `.gguf` pelo seletor de arquivos do Android.
4. O app copia o arquivo, valida o cabeçalho `GGUF` e carrega o modelo.

Também é possível usar **Carregar / recarregar** e **Descarregar** na tela de
configurações.

O arquivo de origem do modelo neste repositório fica em
`assets/models/Qwen3-0.6B-Q8_0.gguf` e **não é empacotado no APK**.

---

## Primeiro uso

1. Instale o APK.
2. Abra o BMO e aguarde a extração dos modelos de voz.
3. Conceda a permissão de microfone quando solicitado.
4. Copie ou importe o `Qwen3-0.6B-Q8_0.gguf`.
5. Toque no rosto e fale, ou use o ícone de teclado para testar por texto.
6. Se quiser a palavra de ativação, habilite-a nas configurações.

Na primeira resposta, o modelo precisa processar o prompt inicial, o que pode
levar em torno de **30 segundos** no A22 com o contexto padrão. Depois disso, o
cache de prefixo reutiliza o KV cache e as respostas tendem a ser mais rápidas.

---

## Configurações

As configurações ficam em `SharedPreferences` e são aplicadas em tempo real
quando possível.

### Inteligência artificial

- Selecionar arquivo GGUF.
- Carregar / recarregar e descarregar modelo.
- Habilitar/desabilitar “Thinking” do Qwen3 (desabilitado por padrão).
- Limite de tokens por resposta (16 a 256).
- Tamanho do contexto (256 a 2048).
- Threads de inferência (1 a 6).

Para o A22, **4 threads** é um bom ponto de partida. Contextos maiores consomem
mais memória e aumentam o tempo de processamento.

### Voz

- Permissão de microfone.
- Reconhecimento de voz offline.
- Síntese de voz offline.
- Palavra de ativação “Ei, BMO”.
- Velocidade da fala (50% a 180%).
- Volume da voz (0% a 100%).
- Testar voz / parar fala.
- Conversa por texto para diagnóstico.

### Aparência

- Expressão facial padrão.
- Escala do rosto (70% a 120%).
- Prévia da expressão com o fundo `#90E5C8`.

### Sistema

- Status do modelo, ASR, TTS e microfone.
- Diagnóstico recente (últimos eventos).
- Atalho para as permissões do aplicativo.

---

## Estrutura do projeto

```text
BMOApp/
├── app/
│   ├── libs/
│   │   └── sherpa-onnx-1.10.44.aar
│   ├── src/main/
│   │   ├── assets/
│   │   │   ├── faces/              # 40 SVGs originais do BMO
│   │   │   └── models/
│   │   │       ├── tts-pt.zip      # Piper pt-BR Faber
│   │   │       └── vosk-pt.zip     # Vosk small pt-BR
│   │   ├── cpp/
│   │   │   ├── bmo_jni.cpp         # JNI, llama.cpp e cache de prompt
│   │   │   ├── CMakeLists.txt
│   │   │   └── llama.cpp/          # llama.cpp vendorizado
│   │   ├── java/com/spacexjr/bmo/
│   │   │   ├── MainActivity.kt
│   │   │   ├── SettingsActivity.kt
│   │   │   ├── BmoAssistant.kt     # orquestração da conversa
│   │   │   ├── actions/            # ações permitidas
│   │   │   ├── data/               # preferências, modelos e diagnóstico
│   │   │   ├── face/               # SVGs, estados e animação
│   │   │   ├── llm/                # ponte Kotlin/JNI
│   │   │   └── speech/             # ASR, TTS e wake word
│   │   ├── res/
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── assets/models/Qwen3-0.6B-Q8_0.gguf   # não empacotado
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── README.md
```

---

## Desempenho e memória

- Modelo: Qwen3-0.6B em quantização **Q8_0** (cerca de 610 MB de pesos).
- Contexto padrão: **1024 tokens**.
- Máximo de tokens por resposta padrão: **96**.
- Threads padrão: **3**; 4 threads tende a ser melhor no A22.
- Primeiro carregamento do modelo: cerca de 1 segundo.
- Primeira resposta: cerca de **30 segundos** no A22 (prompt inicial grande +
  prefill). O cache de prefixo evita reprocessar o mesmo início nos turnos
  seguintes, mas a geração ainda pode ser lenta.
- A fala só começa depois que a resposta inteira é gerada; por isso a sensação de
  demora inclui o tempo de “pensar” e o tempo de sintetizar/iniciar o áudio.
- A geração é cancelável com toque duplo.

O uso de memória pode ser alto por causa do modelo Q8_0. Se o Android encerrar o
app, reduza o contexto, o limite de tokens ou use um modelo menor/mais quantizado.

---

## Limitações conhecidas

- **Somente `arm64-v8a`**. Não funciona em aparelhos antigos de 32 bits.
- **O GGUF não acompanha o APK**; é necessário copiá-lo ou importá-lo.
- **Palavra de ativação não é um detector neural dedicado.** É Vosk com gramática
  restrita + gate de RMS. Funciona apenas com o app em primeiro plano, pode
  errar em ambiente ruidoso e consome bateria.
- **Microfone exclusivo:** wake word, ASR e TTS não usam o microfone ao mesmo
  tempo; o app pausa/retoma o detector durante a conversa.
- **ASR pequeno:** o modelo Vosk small tem vocabulário e precisão limitados,
  especialmente com ruído, sotaque forte ou fala muito rápida.
- **TTS:** há apenas a voz Piper pt-BR Faber (média). O sherpa-onnx está fixado
  em 1.10.44 porque versões mais novas testadas ignoravam o `dataDir` do VITS e
  falhavam com “phoneme_codepoints is empty”.
- **Qualidade do LLM:** Qwen3-0.6B é pequeno; pode alucinar, repetir frases ou
  errar ferramentas. O prompt tenta reduzir isso, mas não elimina o problema.
- **Latência** no A22 é alta no primeiro turno e na geração de respostas longas.
  O TTS também pode demorar a iniciar, e a fala pode repetir a resposta anterior
  em vez da atual (problema em aberto).
- **Emojis no TTS:** emojis presentes na resposta podem ser narrados pela voz.
  Ainda não há filtro de emojis antes da síntese (problema em aberto).
- **Ações** dependem das permissões e das telas disponíveis em cada versão do
  Android; algumas configurações podem abrir apenas a tela principal.
- **Configurações em paisagem:** o teclado pode ocupar boa parte da tela; use a
  tecla de ação do teclado para enviar o texto.
- **Sem testes automatizados** no momento (há dependências de JUnit configuradas,
  mas a suíte ainda não foi escrita).

---

## Diagnóstico e logs

A tela de configurações mostra os eventos mais recentes. Para acompanhar pelo
terminal:

```bash
adb logcat -s BMO/Assistant BMO/LLM BMO/Action BMO/Speech BMO/Tts BmoLlama BmoWakeWord
```

Ou, de forma mais ampla:

```bash
adb logcat | grep -Ei "BMO|BmoLlama|llama|vosk|sherpa|FATAL|AndroidRuntime"
```

Logs úteis:

- `prompt tokens=N (cache C, reuse R)` — tamanho do prompt e reuso do KV cache.
- `model loaded, n_ctx=... threads=...` — configuração efetiva do modelo.
- `model released` — modelo descarregado.
- `reprodução concluída` — fim da reprodução do TTS.

Para limpar os modelos de voz extraídos e forçar nova extração:

```bash
adb shell pm clear com.spacexjr.bmo
```

Isso também apaga as preferências e o caminho salvo do modelo GGUF.

---

## Solução de problemas

### “Modelo não carregado”

Verifique se o arquivo existe no caminho configurado e se é um GGUF válido.
Use **Selecionar arquivo GGUF** nas configurações para importar novamente.

### Modelo demora muito ou o app é encerrado

Reduza o contexto e o limite de tokens. Feche outros apps. Confirme que o aparelho
tem memória livre suficiente para os pesos Q8_0.

### Microfone não funciona

Conceda a permissão nas configurações do Android. Verifique se outro app está
usando o microfone. O wake word e o ASR não podem escutar ao mesmo tempo.

### Wake word não dispara

Fale perto do microfone, em ambiente mais silencioso. O detector não é neural e
a precisão é limitada. Confirme que a opção está habilitada e que o app está em
primeiro plano.

### Voz não sai

Confira o volume de mídia do aparelho e o volume da voz nas configurações. Use
**Testar voz**. Veja o diagnóstico para erros de carregamento do modelo Piper.

### Erro ao importar o GGUF

O arquivo precisa ter mais de 1 MB e começar com o magic `GGUF`. Se estiver
corrompido, copie novamente.

---

## Créditos e tecnologias

- **llama.cpp** — inferência local do modelo GGUF.
- **Qwen3-0.6B** — modelo de linguagem (não incluído no APK).
- **Vosk** — reconhecimento de voz offline.
- **sherpa-onnx** — execução do modelo VITS/Piper.
- **Piper** — modelo de voz pt-BR Faber.
- **AndroidSVG** — renderização dos SVGs.
- **AndroidX / Material Components** — interface.
- SVGs do rosto do BMO oriundos dos assets originais do projeto.

Verifique as licenças de cada componente e do modelo antes de redistribuir o APK
ou os arquivos de modelo.
