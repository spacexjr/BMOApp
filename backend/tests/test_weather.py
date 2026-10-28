import asyncio

import pytest

from bmo_backend.weather import (
    WeatherIntent,
    WeatherService,
    WeatherUnavailable,
    detect_weather_intent,
    format_weather,
)


def sample_weather():
    return {
        "current": {
            "temperature_2m": 24.6,
            "apparent_temperature": 25.4,
            "weather_code": 2,
        },
        "daily": {
            "time": ["2026-10-28", "2026-10-29", "2026-10-30"],
            "weather_code": [61, 1, 0],
            "temperature_2m_max": [28.2, 30.1, 31.0],
            "temperature_2m_min": [19.4, 20.0, 21.0],
            "precipitation_probability_max": [80, 10, 0],
            "precipitation_sum": [5.2, 0.0, 0.0],
        },
    }


def test_detect_rain_intent_today_and_tomorrow():
    today = detect_weather_intent("Ei BMO, vai chover hoje?")
    assert today == WeatherIntent("rain", 0)
    tomorrow = detect_weather_intent("vai chover amanhã?")
    assert tomorrow == WeatherIntent("rain", 1)
    assert detect_weather_intent("preciso levar guarda-chuva?") == WeatherIntent("rain", 0)


def test_weather_without_location_asks_for_gps():
    service = WeatherService({"enabled": True})
    with pytest.raises(WeatherUnavailable, match="GPS"):
        asyncio.run(service.answer(WeatherIntent("summary")))


def test_detect_temperature_and_summary_intents():
    assert detect_weather_intent("qual a temperatura agora?") == WeatherIntent(
        "temperature", 0
    )
    assert detect_weather_intent("como está o tempo?") == WeatherIntent("summary", 0)
    assert detect_weather_intent("previsão do tempo para amanhã") == WeatherIntent(
        "summary", 1
    )


def test_unrelated_text_has_no_weather_intent():
    assert detect_weather_intent("conte uma história sobre robôs") is None
    assert detect_weather_intent("vou guardar dinheiro") is None


def test_format_rain_answer_positive_and_negative():
    positive = format_weather(sample_weather(), "São Paulo", WeatherIntent("rain", 0))
    assert positive.startswith("Sim, há previsão de chuva para hoje")
    assert "80% de chance de chuva" not in positive
    assert "chance de 80% e chuva leve" in positive
    assert "Agora faz 25 graus" in positive

    negative = format_weather(sample_weather(), "São Paulo", WeatherIntent("rain", 1))
    assert negative.startswith("Não deve chover amanhã")
    assert "chance de 10%" in negative


def test_format_temperature_and_summary():
    temperature = format_weather(
        sample_weather(), "São Paulo", WeatherIntent("temperature", 0)
    )
    assert "máxima de 28 graus" in temperature
    assert "mínima de 19 graus" in temperature
    assert "80% de chance de chuva" in temperature

    summary = format_weather(sample_weather(), "São Paulo", WeatherIntent("summary", 0))
    assert "Para hoje" in summary
    assert "chuva leve" in summary
