"""Reconhecimento de fala local com whisper.cpp."""

from __future__ import annotations

import asyncio
import logging
import os
import re
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .audio import pcm16_to_wav

log = logging.getLogger("bmo.stt")


class SttUnavailable(RuntimeError):
    pass


class SttError(RuntimeError):
    pass


@dataclass
class SttResult:
    text: str
    language: str
    duration_ms: int


class WhisperCppStt:
    def __init__(self, config: dict[str, Any]) -> None:
        self.binary = Path(config.get("binary", ""))
        self.model = Path(config.get("model", ""))
        self.language = config.get("language", "pt")
        self.threads = int(config.get("threads", 4))
        self.timeout_s = float(config.get("timeout_s", 90))
        self.prompt = config.get("prompt", "")
        self._lock = asyncio.Lock()

    def available(self) -> bool:
        return self.binary.is_file() and os.access(self.binary, os.X_OK) and self.model.is_file()

    def status(self) -> dict[str, Any]:
        return {
            "engine": "whisper.cpp",
            "available": self.available(),
            "binary": str(self.binary),
            "model": str(self.model),
            "language": self.language,
        }

    async def transcribe(self, pcm: bytes, sample_rate: int = 16000) -> SttResult:
        if not self.available():
            raise SttUnavailable(
                "Whisper.cpp não encontrado. Execute scripts/setup_backend.sh ou ajuste stt.binary e stt.model."
            )
        if not pcm:
            raise SttError("Áudio vazio.")
        wav_bytes = pcm16_to_wav(pcm, sample_rate=sample_rate)
        duration_ms = int(len(pcm) / 2 / sample_rate * 1000)

        async with self._lock:
            with tempfile.TemporaryDirectory(prefix="bmo-stt-") as tmp:
                wav_path = Path(tmp) / "entrada.wav"
                out_prefix = Path(tmp) / "transcricao"
                wav_path.write_bytes(wav_bytes)
                cmd = [
                    str(self.binary),
                    "-m",
                    str(self.model),
                    "-f",
                    str(wav_path),
                    "-l",
                    self.language,
                    "-t",
                    str(self.threads),
                    "-otxt",
                    "-of",
                    str(out_prefix),
                    "-np",
                ]
                if self.prompt:
                    cmd.extend(["--prompt", self.prompt])
                log.debug("Executando STT: %s", " ".join(cmd[:6]))
                # O whisper-cli carrega libwhisper.so/libggml.so do próprio diretório.
                # Incluí-lo no LD_LIBRARY_PATH evita falhas quando o projeto é movido
                # ou renomeado e o RUNPATH gravado no binário fica desatualizado.
                env = os.environ.copy()
                lib_dir = str(self.binary.resolve().parent)
                current = env.get("LD_LIBRARY_PATH", "")
                env["LD_LIBRARY_PATH"] = f"{lib_dir}:{current}" if current else lib_dir
                proc = await asyncio.create_subprocess_exec(
                    *cmd,
                    stdout=asyncio.subprocess.DEVNULL,
                    stderr=asyncio.subprocess.PIPE,
                    env=env,
                )
                try:
                    _, stderr = await asyncio.wait_for(proc.communicate(), timeout=self.timeout_s)
                except asyncio.TimeoutError as exc:
                    proc.kill()
                    await proc.wait()
                    raise SttError("Tempo limite do reconhecimento de fala excedido.") from exc
                if proc.returncode != 0:
                    detail = stderr.decode("utf-8", "ignore")[-400:]
                    raise SttError(f"Whisper.cpp falhou (código {proc.returncode}). {detail}")
                text_file = out_prefix.with_suffix(".txt")
                text = text_file.read_text(encoding="utf-8", errors="ignore") if text_file.exists() else ""
                text = clean_transcript(text)
                return SttResult(text=text, language=self.language, duration_ms=duration_ms)

    async def close(self) -> None:
        return None


def clean_transcript(text: str) -> str:
    text = text.strip()
    # Remove marcações de som do Whisper, como [Risos], (música) e [BLANK_AUDIO].
    text = re.sub(r"\[[^\]]{1,60}\]", " ", text)
    text = re.sub(r"\((?:risos|música|silêncio|inaudível)[^)]*\)", " ", text, flags=re.I)
    text = re.sub(r"\s+", " ", text)
    return text.strip()
