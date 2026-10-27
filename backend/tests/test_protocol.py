import pytest

from bmo_backend.protocol import ProtocolError, validate_audio_start, validate_client_message, validate_text


def test_valid_message():
    assert validate_client_message({"type": "ping"})["type"] == "ping"


def test_unknown_type():
    with pytest.raises(ProtocolError) as exc:
        validate_client_message({"type": "rm -rf"})
    assert exc.value.code == "unknown_type"


def test_text_validation():
    assert validate_text({"text": "  olá  "}) == "olá"
    with pytest.raises(ProtocolError):
        validate_text({"text": ""})
    with pytest.raises(ProtocolError):
        validate_text({"text": "x" * 1001})


def test_audio_start_validation():
    cfg = validate_audio_start({"sample_rate": 16000, "channels": 1}, 16000)
    assert cfg["format"] == "pcm_s16le"
    with pytest.raises(ProtocolError):
        validate_audio_start({"channels": 2})
    with pytest.raises(ProtocolError):
        validate_audio_start({"sample_rate": 123})
    with pytest.raises(ProtocolError):
        validate_audio_start({"format": "mp3"})
