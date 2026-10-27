"""Resposta de descoberta do backend na rede local (UDP broadcast)."""

from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from .protocol import PROTOCOL_VERSION

log = logging.getLogger("bmo.discovery")

REQUEST = b"BMO_DISCOVER_V1"


class _DiscoveryProtocol(asyncio.DatagramProtocol):
    def __init__(self, config: dict[str, Any]) -> None:
        self.config = config
        self.transport: asyncio.DatagramTransport | None = None

    def connection_made(self, transport: asyncio.BaseTransport) -> None:
        self.transport = transport  # type: ignore[assignment]

    def datagram_received(self, data: bytes, addr: tuple[str, int]) -> None:
        if data.strip() != REQUEST:
            return
        payload = {
            "service": "bmo-backend",
            "name": "BMO no PC",
            "protocol": PROTOCOL_VERSION,
            "port": int(self.config["server"]["port"]),
            "requires_token": True,
        }
        if self.transport is not None:
            self.transport.sendto(json.dumps(payload).encode("utf-8"), addr)
            log.info("Descoberta respondida para %s:%s", addr[0], addr[1])


async def start_discovery(config: dict[str, Any]) -> asyncio.DatagramTransport | None:
    if not bool(config["server"].get("discovery_enabled", True)):
        return None
    loop = asyncio.get_running_loop()
    try:
        transport, _ = await loop.create_datagram_endpoint(
            lambda: _DiscoveryProtocol(config),
            local_addr=("0.0.0.0", int(config["server"].get("discovery_port", 8766))),
            allow_broadcast=True,
        )
        log.info("Descoberta UDP ativa na porta %s", config["server"].get("discovery_port", 8766))
        return transport
    except OSError as exc:
        log.warning("Não foi possível abrir a descoberta UDP: %s", exc)
        return None
