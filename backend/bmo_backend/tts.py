"""Síntese de voz local: Piper com fallback para espeak-ng."""

from __future__ import annotations

import asyncio
import hashlib
import logging
import os
import shutil
import tempfile
import wave
from collections import OrderedDict
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .audio import split_wav

log = logging.getLogger("bmo.tts")


class TtsUnavailable(RuntimeError):
    pass


class TtsError(RuntimeError):
    pass


@dataclass
class TtsAudio:
    wav: bytes
    pcm: bytes
    sample_rate: int
    channels: int
    duration_s: float
    engine: str


class _Cache:
    def __init__(self, max_items: int = 48) -> None:
        self.max_items = max_items
        self._items: OrderedDict[str, TtsAudio] = OrderedDict()

    def get(self, key: str) -> TtsAudio | None:
        item = self._items.get(key)
        if item is not None:
            self._items.move_to_end(key)
        return item

    def put(self, key: str, value: TtsAudio) -> None:
        self._items[key] = value
        self._items.move_to_end(key)
        while len(self._items) > self.max_items:
            self._items.popitem(last=False)


class TtsService:
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.engine = str(config.get("engine", "auto")).lower()
        self.piper = Path(config.get("piper_binary", ""))
        self.voice = Path(config.get("voice_model", ""))
        self.espeak = shutil.which(str(config.get("espeak_binary", "espeak-ng"))) or str(
            config.get("espeak_binary", "espeak-ng")
        )
        self.espeak_voice = config.get("espeak_voice", "pt-br")
        self.espeak_speed = int(config.get("espeak_speed", 150))
        self.timeout_s = float(config.get("timeout_s", 90))
        self.max_chars = int(config.get("max_chars", 1200))
        self.cache = _Cache()
        self._lock = asyncio.Lock()

    def selected_engine(self) -> str | None:
        if self.engine == "piper":
            return "piper" if self._piper_ok() else None
        if self.engine == "espeak":
            return "espeak" if self._espeak_ok() else None
        if self._piper_ok():
            return "piper"
        if self._espeak_ok():
            return "espeak"
        return None

    def _piper_ok(self) -> bool:
        return self.piper.is_file() and os.access(self.piper, os.X_OK) and self.voice.is_file()

    def _espeak_ok(self) -> bool:
        return bool(self.espeak) and Path(self.espeak).exists()

    def available(self) -> bool:
        return self.selected_engine() is not None

    def status(self) -> dict[str, Any]:
        return {
            "engine": self.selected_engine(),
            "available": self.available(),
            "piper_available": self._piper_ok(),
            "espeak_available": self._espeak_ok(),
            "voice": str(self.voice),
        }

    async def synthesize(self, text: str) -> TtsAudio:
        text = text.strip()
        if not text:
            raise TtsError("Texto vazio para síntese.")
        if len(text) > self.max_chars:
            text = text[: self.max_chars].rsplit(" ", 1)[0] + "."
        engine = self.selected_engine()
        if engine is None:
            raise TtsUnavailable(
                "Nenhum mecanismo de TTS local disponível. Instale Piper ou espeak-ng."
            )
        key = hashlib.sha256(f"{engine}\0{text}\0{self.espeak_voice}\0{self.espeak_speed}".encode()).hexdigest()
        cached = self.cache.get(key)
        if cached is not None:
            return cached
        async with self._lock:
            cached = self.cache.get(key)
            if cached is not None:
                return cached
            if engine == "piper":
                wav_bytes = await self._piper_wav(text)
            else:
                wav_bytes = await self._espeak_wav(text)
            try:
                pcm, rate, channels = split_wav(wav_bytes)
            except Exception as exc:  # noqa: BLE001
                raise TtsError(f"Áudio TTS inválido: {exc}") from exc
            duration = len(pcm) / max(1, channels) / 2 / max(1, rate)
            audio = TtsAudio(wav_bytes, pcm, rate, channels, duration, engine)
            self.cache.put(key, audio)
            return audio

    async def _piper_wav(self, text: str) -> bytes:
        with tempfile.TemporaryDirectory(prefix="bmo-tts-") as tmp:
            out = Path(tmp) / "fala.wav"
            cmd = [str(self.piper), "-m", str(self.voice), "-f", str(out)]
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                stdin=asyncio.subprocess.PIPE,
                stdout=asyncio.subprocess.DEVNULL,
                stderr=asyncio.subprocess.PIPE,
            )
            try:
                _, stderr = await asyncio.wait_for(
                    proc.communicate((text + "\n").encode("utf-8")), timeout=self.timeout_s
                )
            except asyncio.TimeoutError as exc:
                proc.kill()
                await proc.wait()
                raise TtsError("Tempo limite do Piper excedido.") from exc
            if proc.returncode != 0 or not out.exists():
                detail = stderr.decode("utf-8", "ignore")[-300:]
                raise TtsError(f"Piper falhou (código {proc.returncode}). {detail}")
            return out.read_bytes()

    async def _espeak_wav(self, text: str) -> bytes:
        with tempfile.TemporaryDirectory(prefix="bmo-tts-") as tmp:
            out = Path(tmp) / "fala.wav"
            cmd = [
                self.espeak,
                "-v",
                self.espeak_voice,
                "-s",
                str(self.espeak_speed),
                "-w",
                str(out),
                text,
            ]
            proc = await asyncio.create_subprocess_exec(
                *cmd, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE
            )
            try:
                _, stderr = await asyncio.wait_for(proc.communicate(), timeout=self.timeout_s)
            except asyncio.TimeoutError as exc:
                proc.kill()
                await proc.wait()
                raise TtsError("Tempo limite do espeak-ng excedido.") from exc
            if proc.returncode != 0 or not out.exists():
                detail = stderr.decode("utf-8", "ignore")[-300:]
                raise TtsError(f"espeak-ng falhou (código {proc.returncode}). {detail}")
            return out.read_bytes()

    async def close(self) -> None:
        return None
