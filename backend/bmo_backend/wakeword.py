"""Detecção da palavra de ativação 'Ei, BMO'.

A detecção padrão é textual: o áudio é transcrito pelo Whisper.cpp e o texto
normalizado é comparado com frases configuráveis. Isso permite testar o
reconhecimento sem depender de um modelo acústico treinado para "Ei, BMO".

O arquivo wakeword.onnx do projeto original é o modelo "Hey Jarvis" do
OpenWakeWord. Ele NÃO é usado silenciosamente como se fosse "Ei, BMO".
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass

DEFAULT_PHRASES = [
    "ei bmo",
    "ei bemo",
    "hey bmo",
    "oi bmo",
    "e bmo",
    "bmo",
]


def normalize_text(text: str) -> str:
    """Minúsculas, sem acentos e sem pontuação, com espaços simples."""
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.lower()
    text = re.sub(r"[^a-z0-9]+", " ", text)
    return re.sub(r"\s+", " ", text).strip()


@dataclass
class WakeWordResult:
    detected: bool
    remaining_text: str
    matched_phrase: str | None
    normalized_text: str


class WakeWordDetector:
    def __init__(self, phrases: list[str] | None = None, enabled: bool = True) -> None:
        raw = phrases if phrases is not None else DEFAULT_PHRASES
        # Frases maiores primeiro evita que "bmo" consuma só o final de "ei bmo".
        self.phrases = sorted(
            {normalize_text(p) for p in raw if normalize_text(p)},
            key=len,
            reverse=True,
        )
        self.enabled = enabled
        self._patterns = [
            (phrase, re.compile(r"(?<![a-z0-9])" + re.escape(phrase) + r"(?![a-z0-9])"))
            for phrase in self.phrases
        ]

    def detect(self, text: str) -> WakeWordResult:
        normalized = normalize_text(text)
        if not self.enabled:
            return WakeWordResult(True, text.strip(), None, normalized)
        for phrase, pattern in self._patterns:
            match = pattern.search(normalized)
            if not match:
                continue
            # Remove a ocorrência da frase do texto original normalizado e
            # devolve o restante em minúsculas (sem acentos). O texto original
            # é usado apenas para transcrição; a resposta do LLM não depende de
            # acentuação do comando.
            remaining = (normalized[: match.start()] + " " + normalized[match.end() :]).strip()
            remaining = re.sub(r"\s+", " ", remaining).strip(" ,.!?;:-")
            return WakeWordResult(True, remaining, phrase, normalized)
        return WakeWordResult(False, text.strip(), None, normalized)
