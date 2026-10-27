"""Contratos e validação do protocolo BMO/1."""

from __future__ import annotations

import time
from typing import Any

PROTOCOL_VERSION = "bmo/1"
SERVICE_VERSION = "0.1.0"


class ProtocolError(ValueError):
    """Mensagem inválida enviada pelo cliente."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


CLIENT_MESSAGE_TYPES = {
    "hello",
    "ping",
    "audio.start",
    "audio.end",
    "text",
    "activate",
    "interrupt",
    "playback.started",
    "playback.done",
    "reset",
}

AUDIO_FORMATS = {"pcm_s16le"}
MAX_JSON_DEPTH = 8


def now_ms() -> int:
    return int(time.time() * 1000)


def validate_client_message(payload: Any) -> dict[str, Any]:
    if not isinstance(payload, dict):
        raise ProtocolError("invalid_message", "A mensagem deve ser um objeto JSON.")
    msg_type = payload.get("type")
    if not isinstance(msg_type, str) or not msg_type:
        raise ProtocolError("missing_type", "Campo 'type' obrigatório.")
    if msg_type not in CLIENT_MESSAGE_TYPES:
        raise ProtocolError("unknown_type", f"Tipo de mensagem desconhecido: {msg_type}")
    if _depth(payload) > MAX_JSON_DEPTH:
        raise ProtocolError("invalid_message", "Mensagem JSON muito profunda.")
    return payload


def validate_audio_start(payload: dict[str, Any], expected_rate: int = 16000) -> dict[str, Any]:
    fmt = payload.get("format", "pcm_s16le")
    if fmt not in AUDIO_FORMATS:
        raise ProtocolError("unsupported_audio_format", "Formato de áudio não suportado.")
    try:
        rate = int(payload.get("sample_rate", expected_rate))
        channels = int(payload.get("channels", 1))
    except (TypeError, ValueError) as exc:
        raise ProtocolError("invalid_audio_config", "Taxa de amostragem/canais inválidos.") from exc
    if rate < 8000 or rate > 48000:
        raise ProtocolError("invalid_sample_rate", "sample_rate deve estar entre 8000 e 48000.")
    if channels != 1:
        raise ProtocolError("unsupported_channels", "Somente áudio mono é aceito.")
    return {"format": fmt, "sample_rate": rate, "channels": channels}


def validate_text(payload: dict[str, Any], max_chars: int = 1000) -> str:
    text = payload.get("text")
    if not isinstance(text, str):
        raise ProtocolError("invalid_text", "Campo 'text' deve ser texto.")
    text = text.strip()
    if not text:
        raise ProtocolError("empty_text", "O texto está vazio.")
    if len(text) > max_chars:
        raise ProtocolError("text_too_long", f"O texto excede {max_chars} caracteres.")
    return text


def _depth(value: Any, level: int = 0) -> int:
    if level > MAX_JSON_DEPTH:
        return level
    if isinstance(value, dict):
        return max([level] + [_depth(v, level + 1) for v in value.values()])
    if isinstance(value, list):
        return max([level] + [_depth(v, level + 1) for v in value])
    return level
