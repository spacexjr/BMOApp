"""Ações autorizadas. Nenhum comando de shell é executado."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from .wakeword import normalize_text

ALLOWED_ACTIONS = {"get_time", "get_date"}

_TIME_PATTERNS = [
    r"\bque horas (sao|e)\b",
    r"\bque hora (e|sao)\b",
    r"\bqual (e )?(a )?hora\b",
    r"\bme (diga|fala|informe) (a )?hora\b",
    r"\bhoras agora\b",
    r"\bwhat time is it\b",
    r"\bcurrent time\b",
]

_DATE_PATTERNS = [
    r"\bque dia (e )?(hoje|e hoje)\b",
    r"\bqual (e )?(a )?data( de hoje)?\b",
    r"\bdata de hoje\b",
    r"\bwhat('?s| is) the date\b",
    r"\btoday'?s date\b",
]


@dataclass
class ActionResult:
    text: str
    action: str


def _now_text() -> str:
    now = datetime.now()
    return f"Agora são {now.strftime('%H:%M')}."


def _today_text() -> str:
    now = datetime.now()
    dias = [
        "segunda-feira",
        "terça-feira",
        "quarta-feira",
        "quinta-feira",
        "sexta-feira",
        "sábado",
        "domingo",
    ]
    meses = [
        "janeiro",
        "fevereiro",
        "março",
        "abril",
        "maio",
        "junho",
        "julho",
        "agosto",
        "setembro",
        "outubro",
        "novembro",
        "dezembro",
    ]
    return f"Hoje é {dias[now.weekday()]}, {now.day} de {meses[now.month - 1]} de {now.year}."


def detect_local_intent(text: str) -> ActionResult | None:
    """Responde localmente pedidos simples de hora/data, sem LLM."""
    normalized = normalize_text(text)
    if any(re.search(pattern, normalized) for pattern in _TIME_PATTERNS):
        return ActionResult(_now_text(), "get_time")
    if any(re.search(pattern, normalized) for pattern in _DATE_PATTERNS):
        return ActionResult(_today_text(), "get_date")
    return None


def parse_authorized_action(text: str, allowed: list[str] | None = None) -> ActionResult | None:
    """Interpreta um JSON de ação retornado pelo LLM, se autorizado.

    A lista de comandos permitidos é explícita. Qualquer outro comando é
    rejeitado e o texto original é devolvido ao usuário.
    """
    allowed_set = set(allowed or ALLOWED_ACTIONS)
    candidate = text.strip()
    if not candidate.startswith("{"):
        match = re.search(r"\{[^{}]{1,400}\}", candidate)
        if not match:
            return None
        candidate = match.group(0)
    try:
        payload: Any = json.loads(candidate)
    except json.JSONDecodeError:
        return None
    if not isinstance(payload, dict):
        return None
    action = payload.get("action")
    if action not in allowed_set:
        return None
    if action == "get_time":
        return ActionResult(_now_text(), "get_time")
    if action == "get_date":
        return ActionResult(_today_text(), "get_date")
    return None
