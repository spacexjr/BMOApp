import math
import struct

from bmo_backend.audio import EnergyVad, pcm16_rms, pcm16_to_wav, split_wav


def tone(seconds: float, rate: int = 16000, amplitude: float = 0.3, freq: float = 220.0) -> bytes:
    count = int(seconds * rate)
    return b"".join(
        struct.pack("<h", int(32767 * amplitude * math.sin(2 * math.pi * freq * i / rate)))
        for i in range(count)
    )


def silence(seconds: float, rate: int = 16000) -> bytes:
    return b"\x00\x00" * int(seconds * rate)


def feed_frames(vad: EnergyVad, pcm: bytes, frame_bytes: int = 640):
    events = []
    for offset in range(0, len(pcm), frame_bytes):
        events.extend(vad.feed(pcm[offset : offset + frame_bytes]))
    return events


def test_pcm_rms():
    assert pcm16_rms(silence(0.1)) == 0.0
    assert pcm16_rms(tone(0.1)) > 0.15


def test_vad_detects_speech_and_silence_end():
    vad = EnergyVad(min_rms=0.01, start_ms=180, end_silence_ms=400, min_utterance_ms=200)
    events = []
    events.extend(feed_frames(vad, silence(0.3)))
    events.extend(feed_frames(vad, tone(0.8)))
    events.extend(feed_frames(vad, silence(0.7)))
    kinds = [e.kind for e in events]
    assert "speech_start" in kinds
    assert "speech_end" in kinds
    end = next(e for e in events if e.kind == "speech_end")
    assert len(end.utterance) > 16000


def test_vad_ignores_short_noise():
    vad = EnergyVad(min_rms=0.01, start_ms=180, end_silence_ms=300, min_utterance_ms=300)
    events = []
    events.extend(feed_frames(vad, tone(0.06)))
    events.extend(feed_frames(vad, silence(0.5)))
    assert all(e.kind != "speech_end" or e.utterance == b"" for e in events)


def test_wav_roundtrip():
    pcm = tone(0.2)
    wav = pcm16_to_wav(pcm, 16000)
    pcm2, rate, channels = split_wav(wav)
    assert pcm2 == pcm
    assert rate == 16000
    assert channels == 1
