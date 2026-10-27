# Protocolo bmo/1 — esquemas

- [`client-message.schema.json`](schemas/client-message.schema.json): mensagens JSON enviadas pelo app.
- [`server-event.schema.json`](schemas/server-event.schema.json): eventos JSON enviados pelo backend.

Os esquemas não cobrem os quadros binários de áudio. Depois de um evento `audio.start`, o servidor envia bytes PCM 16-bit little-endian, mono, na taxa informada, até o evento `audio.end`.

Documentação completa: [docs/PROTOCOLO.md](../docs/PROTOCOLO.md).

Validação rápida dos arquivos:

```bash
python3 -m json.tool protocol/schemas/client-message.schema.json >/dev/null
python3 -m json.tool protocol/schemas/server-event.schema.json >/dev/null
```
