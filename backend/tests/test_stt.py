from bmo_backend.stt import clean_transcript


def test_clean_transcript_removes_whisper_sound_annotations():
    assert clean_transcript("[Risos]") == ""
    assert clean_transcript("[BLANK_AUDIO] Ei, BMO. [Música]") == "Ei, BMO."
    assert clean_transcript("(risos) tudo bem?") == "tudo bem?"


def test_clean_transcript_collapses_whitespace():
    assert clean_transcript("  Ei,   BMO.  ") == "Ei, BMO."
