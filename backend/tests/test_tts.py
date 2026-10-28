from bmo_backend.tts import clean_for_speech


def test_clean_for_speech_removes_common_emojis():
    assert clean_for_speech("Olá! 😊 Tudo bem? 👋") == "Olá! Tudo bem?"
    assert clean_for_speech("Que legal 🎉🎉") == "Que legal"


def test_clean_for_speech_removes_flags_skin_tones_and_zwj_sequences():
    assert clean_for_speech("Brasil 🇧🇷 e família 👨‍👩‍👧‍👦") == "Brasil e família"
    assert clean_for_speech("Joinha 👍🏽") == "Joinha"


def test_clean_for_speech_keeps_numbers_and_punctuation():
    assert clean_for_speech("Temperatura: 25 °C 1️⃣") == "Temperatura: 25 °C 1"
