"""Consulta de clima e previsão de chuva em serviços públicos da internet.

Usa Open-Meteo (sem chave de API). A localização principal vem do GPS do app
Android, enviada como coordenadas; cidade ou coordenadas no config.json são
apenas uma alternativa manual. Nenhum áudio ou texto da conversa é enviado:
apenas coordenadas/cidade para montar a previsão.
"""

from __future__ import annotations

import asyncio
import logging
import re
import time
from dataclasses import dataclass
from typing import Any

import httpx

from .wakeword import normalize_text

log = logging.getLogger("bmo.weather")

GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
REVERSE_GEOCODING_URL = "https://api.bigdatacloud.net/data/reverse-geocode-client"

_RAIN_PATTERNS = [
    r"\bchover\b",
    r"\b(vai|vai fazer|vai ter|vai dar|tem|tera|ha|previsao de|chance de|possibilidade de) (de )?chuva\b",
    r"\bchuva (hoje|amanha|agora|essa semana)\b",
    r"\bguarda chuva\b",
]

_TEMPERATURE_PATTERNS = [
    r"\btemperatura\b",
    r"\bquantos graus\b",
    r"\bqual (a )?temperatura\b",
    r"\b(esta|ta|faz|fazendo) (muito )?(frio|calor)\b",
]

_SUMMARY_PATTERNS = [
    r"\bprevisao do tempo\b",
    r"\bcomo (esta|ta) (o )?(tempo|clima)\b",
    r"\bcomo vai (estar|ficar) (o )?(tempo|clima)\b",
    r"\bclima (hoje|amanha|agora)\b",
    r"\btempo (hoje|amanha|agora)\b",
]

_RAIN_CODES = {51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82, 95, 96, 99}

_COUNTRY_NAMES = {
    "Brazil": "Brasil",
    "Portugal": "Portugal",
    "Argentina": "Argentina",
    "Bolivia": "Bolívia",
    "Chile": "Chile",
    "Colombia": "Colômbia",
    "Paraguay": "Paraguai",
    "Peru": "Peru",
    "Uruguay": "Uruguai",
    "Venezuela": "Venezuela",
    "Mexico": "México",
    "United States": "Estados Unidos",
    "Canada": "Canadá",
    "Spain": "Espanha",
    "France": "França",
    "Italy": "Itália",
    "Germany": "Alemanha",
    "United Kingdom": "Reino Unido",
    "Japan": "Japão",
}

_WEATHER_CODES = {
    0: "céu limpo",
    1: "predomínio de sol",
    2: "parcialmente nublado",
    3: "nublado",
    45: "neblina",
    48: "neblina com geada",
    51: "garoa leve",
    53: "garoa moderada",
    55: "garoa intensa",
    56: "garoa congelante",
    57: "garoa congelante intensa",
    61: "chuva leve",
    63: "chuva moderada",
    65: "chuva forte",
    66: "chuva congelante",
    67: "chuva congelante forte",
    71: "neve leve",
    73: "neve moderada",
    75: "neve forte",
    77: "grãos de neve",
    80: "pancadas de chuva leves",
    81: "pancadas de chuva moderadas",
    82: "pancadas de chuva fortes",
    85: "pancadas de neve leves",
    86: "pancadas de neve fortes",
    95: "tempestade",
    96: "tempestade com granizo",
    99: "tempestade forte com granizo",
}


class WeatherUnavailable(RuntimeError):
    pass


class WeatherError(RuntimeError):
    pass


@dataclass(frozen=True)
class WeatherIntent:
    kind: str  # "rain", "temperature" ou "summary"
    day_offset: int = 0  # 0 = hoje, 1 = amanhã, 2 = depois de amanhã


def detect_weather_intent(text: str) -> WeatherIntent | None:
    """Detecta pedidos de clima sem depender do LLM."""
    normalized = normalize_text(text)
    if not normalized:
        return None
    if "depois de amanha" in normalized:
        day_offset = 2
    elif "amanha" in normalized:
        day_offset = 1
    else:
        day_offset = 0
    if any(re.search(pattern, normalized) for pattern in _RAIN_PATTERNS):
        return WeatherIntent("rain", day_offset)
    if any(re.search(pattern, normalized) for pattern in _TEMPERATURE_PATTERNS):
        return WeatherIntent("temperature", day_offset)
    if any(re.search(pattern, normalized) for pattern in _SUMMARY_PATTERNS):
        return WeatherIntent("summary", day_offset)
    return None


class WeatherService:
    def __init__(self, config: dict[str, Any] | None = None) -> None:
        config = config or {}
        self.enabled = bool(config.get("enabled", True))
        self.location = str(config.get("location") or "").strip()
        self.latitude = _optional_float(config.get("latitude"))
        self.longitude = _optional_float(config.get("longitude"))
        self.timezone = str(config.get("timezone") or "auto")
        self.timeout_s = float(config.get("timeout_s", 10))
        self.cache_ttl_s = float(config.get("cache_ttl_s", 600))
        # Chave, instante, dados e nome do lugar. A chave inclui as coordenadas
        # para não reaproveitar a previsão de outra localização.
        self._cache: tuple[Any, float, dict[str, Any], str] | None = None
        self._lock = asyncio.Lock()

    def available(self) -> bool:
        return self.enabled

    def status(self) -> dict[str, Any]:
        return {
            "enabled": self.enabled,
            "location": self.location or "GPS do celular (enviado pelo app)",
            "cache_ttl_s": self.cache_ttl_s,
        }

    async def answer(
        self,
        intent: WeatherIntent,
        latitude: float | None = None,
        longitude: float | None = None,
    ) -> str:
        if not self.enabled:
            raise WeatherUnavailable(
                "A consulta de clima está desativada. Ative weather.enabled no config.json."
            )
        data, place = await self._forecast(latitude=latitude, longitude=longitude)
        return format_weather(data, place, intent)

    async def _forecast(
        self,
        latitude: float | None = None,
        longitude: float | None = None,
    ) -> tuple[dict[str, Any], str]:
        if latitude is not None and longitude is not None:
            cache_key: Any = (round(latitude, 3), round(longitude, 3))
        elif self.latitude is not None and self.longitude is not None:
            cache_key = ("config", round(self.latitude, 3), round(self.longitude, 3))
        elif self.location:
            cache_key = ("cidade", self.location.casefold())
        else:
            raise WeatherUnavailable(
                "Não recebi a localização do celular. Autorize o GPS nas permissões "
                "do app para consultar o clima."
            )
        cached = self._cache
        if (
            cached is not None
            and cached[0] == cache_key
            and time.monotonic() - cached[1] < self.cache_ttl_s
        ):
            return cached[2], cached[3]
        async with self._lock:
            cached = self._cache
            if (
                cached is not None
                and cached[0] == cache_key
                and time.monotonic() - cached[1] < self.cache_ttl_s
            ):
                return cached[2], cached[3]
            timeout = httpx.Timeout(self.timeout_s, connect=min(self.timeout_s, 5.0))
            headers = {"User-Agent": "saturnBMO/0.1"}
            try:
                async with httpx.AsyncClient(timeout=timeout, headers=headers) as client:
                    latitude, longitude, place = await self._resolve_location(
                        client, latitude, longitude
                    )
                    response = await client.get(
                        FORECAST_URL,
                        params={
                            "latitude": latitude,
                            "longitude": longitude,
                            "current": (
                                "temperature_2m,apparent_temperature,precipitation,"
                                "weather_code,wind_speed_10m,is_day"
                            ),
                            "daily": (
                                "weather_code,temperature_2m_max,temperature_2m_min,"
                                "precipitation_probability_max,precipitation_sum"
                            ),
                            "timezone": self.timezone,
                            "forecast_days": 3,
                        },
                    )
                    response.raise_for_status()
                    data = response.json()
            except httpx.TimeoutException as exc:
                raise WeatherError(
                    "O serviço de clima demorou demais para responder. Tente novamente."
                ) from exc
            except httpx.HTTPStatusError as exc:
                raise WeatherError(
                    f"O serviço de clima respondeu com erro HTTP {exc.response.status_code}."
                ) from exc
            except httpx.HTTPError as exc:
                raise WeatherError(
                    "Não consegui acessar a internet para consultar o clima. "
                    "Verifique a conexão do PC."
                ) from exc
            except ValueError as exc:
                raise WeatherError("O serviço de clima retornou uma resposta inválida.") from exc
            if not isinstance(data, dict) or not data.get("current") or not data.get("daily"):
                raise WeatherError("O serviço de clima retornou uma resposta incompleta.")
            self._cache = (cache_key, time.monotonic(), data, place)
            return data, place

    async def _resolve_location(
        self,
        client: httpx.AsyncClient,
        latitude: float | None = None,
        longitude: float | None = None,
    ) -> tuple[float, float, str]:
        if latitude is not None and longitude is not None:
            place = await self._reverse_geocode(client, latitude, longitude)
            return latitude, longitude, place
        if self.latitude is not None and self.longitude is not None:
            place = self.location or await self._reverse_geocode(
                client, self.latitude, self.longitude
            )
            return self.latitude, self.longitude, place
        if self.location:
            response = await client.get(
                GEOCODING_URL,
                params={
                    "name": self.location,
                    "count": 1,
                    "language": "pt",
                    "format": "json",
                },
            )
            response.raise_for_status()
            results = response.json().get("results") or []
            if not results:
                raise WeatherError(
                    f"Não encontrei a cidade '{self.location}'. "
                    "Ajuste weather.location no config.json."
                )
            item = results[0]
            place = _join_place(item.get("name"), item.get("admin1"), item.get("country"))
            return float(item["latitude"]), float(item["longitude"]), place

        raise WeatherUnavailable(
            "Não recebi a localização do celular. Autorize o GPS nas permissões "
            "do app para consultar o clima."
        )

    async def _reverse_geocode(
        self, client: httpx.AsyncClient, latitude: float, longitude: float
    ) -> str:
        """Descobre o nome da cidade sem impedir a previsão em caso de falha."""
        try:
            response = await client.get(
                REVERSE_GEOCODING_URL,
                params={
                    "latitude": latitude,
                    "longitude": longitude,
                    "localityLanguage": "pt",
                },
                timeout=min(self.timeout_s, 4.0),
            )
            response.raise_for_status()
            data = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            log.debug("Geocodificação reversa indisponível: %s", exc)
            return "sua localização"
        if not isinstance(data, dict):
            return "sua localização"
        place = _join_place(
            data.get("city") or data.get("locality"),
            data.get("principalSubdivision"),
            data.get("countryName"),
        )
        return place or "sua localização"


def format_weather(
    data: dict[str, Any], place: str, intent: WeatherIntent
) -> str:
    current = data.get("current") or {}
    daily = data.get("daily") or {}
    dates = daily.get("time") or []
    index = max(0, min(intent.day_offset, len(dates) - 1 if dates else 0))

    temp = _round(current.get("temperature_2m"))
    feels = _round(current.get("apparent_temperature"))
    current_code = int(_optional_float(current.get("weather_code")) or 0)
    current_desc = _WEATHER_CODES.get(current_code, "tempo estável")

    day_code = int(_optional_float(_at(daily.get("weather_code"), index)) or 0)
    day_desc = _WEATHER_CODES.get(day_code, "tempo estável")
    day_max = _round(_at(daily.get("temperature_2m_max"), index))
    day_min = _round(_at(daily.get("temperature_2m_min"), index))
    probability = _round(_at(daily.get("precipitation_probability_max"), index))
    precipitation = _optional_float(_at(daily.get("precipitation_sum"), index)) or 0.0
    rain_expected = day_code in _RAIN_CODES or probability >= 40 or precipitation >= 0.5

    day_label = {0: "hoje", 1: "amanhã", 2: "depois de amanhã"}.get(index, "hoje")
    day_title = day_label.capitalize()
    now = (
        f"Agora faz {temp} graus em {place}, com sensação de {feels} graus "
        f"e {current_desc}."
    )

    if intent.kind == "rain":
        if rain_expected:
            return (
                f"Sim, há previsão de chuva para {day_label}: "
                f"chance de {probability}% e {day_desc}. {now}"
            )
        return (
            f"Não deve chover {day_label}: "
            f"chance de {probability}% e {day_desc}. {now}"
        )

    if intent.kind == "temperature":
        return (
            f"{now} {day_title}: máxima de {day_max} graus, mínima de {day_min} graus "
            f"e {probability}% de chance de chuva."
        )

    return (
        f"{now} Para {day_label}: máxima de {day_max} graus, mínima de {day_min} graus, "
        f"{probability}% de chance de chuva e {day_desc}."
    )


def _optional_float(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def _round(value: Any) -> int:
    number = _optional_float(value)
    return int(round(number)) if number is not None else 0


def _at(values: Any, index: int) -> Any:
    if isinstance(values, list) and values:
        return values[max(0, min(index, len(values) - 1))]
    return None


def _join_place(*parts: Any) -> str:
    seen: list[str] = []
    for part in parts:
        text = str(part).strip() if part else ""
        text = _COUNTRY_NAMES.get(text, text)
        if text and text not in seen:
            seen.append(text)
    return ", ".join(seen)
