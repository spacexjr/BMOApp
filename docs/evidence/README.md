# Evidências visuais

Capturas geradas durante a validação manual. Não contêm token nem dados de configuração sensíveis.

| Arquivo | O que mostra |
|---|---|
| `rosto-somente-1600x720.png` | Rosto somente, resolução de teste 1600×720. |
| `release-rosto-somente-1600x720.png` | APK release abrindo em modo somente rosto. |
| `real-wifi-rosto-2712x1220.png` | Celular real Xiaomi 2412DPC0AI, resolução nativa, somente rosto. |
| `real-wifi-controles-2712x1220.png` | Controles revelados por toque no celular real. |
| `real-wifi-transcricao-2712x1220.png` | Microfone ativo e transcrição espontânea no celular real. |
| `ajustes-paisagem-1600x720.png` | Tela de Ajustes em paisagem. |

As capturas em 1600×720 foram feitas enquanto a resolução do aparelho estava reduzida por `adb` para facilitar a inspeção. As capturas em 2712×1220 usam a resolução nativa (1220×2712 em paisagem).

A captura release em 1600×720 é anterior ao ajuste final de cor de fundo/ícone; o APK final foi recompilado e assinado, sem mudança de comportamento da tela.

Detalhes e ressalvas: [EVIDENCIAS_TESTES.md](../EVIDENCIAS_TESTES.md).
