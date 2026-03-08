import asyncio
import json
import time
from dataclasses import dataclass
from typing import Any

import aiohttp

from .config import Config
from .signature import sign_body


@dataclass
class BackendApiError(Exception):
    message: str
    status: int | None = None
    details: Any = None

    def __str__(self) -> str:
        if self.status is not None:
            return f"{self.message} (status={self.status}, details={self.details})"
        return self.message


class BackendClient:
    """HTTP + WebSocket client for A-Dex backend API."""

    def __init__(self, config: Config) -> None:
        self.config = config
        self.events: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._session: aiohttp.ClientSession | None = None
        self._ws_task: asyncio.Task[None] | None = None
        self._stop_event = asyncio.Event()
        self._reconnect_attempt = 0

    async def start(self) -> None:
        timeout = aiohttp.ClientTimeout(total=15)
        self._session = aiohttp.ClientSession(timeout=timeout)
        self._stop_event.clear()
        self._ws_task = asyncio.create_task(self._ws_loop())

    async def stop(self) -> None:
        self._stop_event.set()

        if self._ws_task:
            self._ws_task.cancel()
            try:
                await self._ws_task
            except asyncio.CancelledError:
                pass

        if self._session:
            await self._session.close()

    def _signed_headers(self, raw_body: str) -> dict[str, str]:
        timestamp = str(int(time.time() * 1000))
        signature = sign_body(self.config.bot_hmac_secret, timestamp, raw_body)
        headers = {
            "x-adex-timestamp": timestamp,
            "x-adex-signature": signature,
        }
        if self.config.bot_ws_token:
            headers["x-adex-bot-token"] = self.config.bot_ws_token
        if raw_body:
            headers["content-type"] = "application/json"
        return headers

    async def post(self, path: str, body: dict[str, Any]) -> Any:
        return await self._request("POST", path, body=body)

    async def delete(self, path: str, body: dict[str, Any]) -> Any:
        return await self._request("DELETE", path, body=body)

    async def get(self, path: str, params: dict[str, Any]) -> Any:
        return await self._request("GET", path, body={}, params=params)

    async def get_capabilities(self) -> dict[str, Any]:
        data = await self.get("/api/v1/capabilities", {})
        if not isinstance(data, dict):
            raise BackendApiError("Invalid capabilities response shape", details=data)
        return data

    async def get_media(self, media_id: str) -> tuple[str, bytes]:
        if not self._session:
            raise BackendApiError("Backend client not started")

        path = f"/api/v1/media/{media_id}"
        headers = self._signed_headers("")
        url = f"{self.config.backend_base_url}{path}"

        async with self._session.get(url, headers=headers) as response:
            payload = await response.read()
            if response.status >= 400:
                raise BackendApiError("Failed to download media", status=response.status, details=payload.decode("utf-8", errors="ignore"))
            content_type = response.headers.get("content-type", "application/octet-stream")
            return content_type, payload

    async def _request(
        self,
        method: str,
        path: str,
        body: dict[str, Any],
        params: dict[str, Any] | None = None,
    ) -> Any:
        if not self._session:
            raise BackendApiError("Backend client not started")

        raw_body = ""
        request_data: str | None = None

        if method.upper() not in {"GET", "HEAD"}:
            # Keep signed payload byte-for-byte identical to HTTP request body.
            raw_body = json.dumps(body or {}, separators=(",", ":"), ensure_ascii=False)
            request_data = raw_body

        headers = self._signed_headers(raw_body)
        url = f"{self.config.backend_base_url}{path}"

        async with self._session.request(method, url, data=request_data, params=params, headers=headers) as response:
            text = await response.text()
            data: Any
            try:
                data = json.loads(text) if text else {}
            except json.JSONDecodeError:
                data = {"raw": text}

            if response.status >= 400:
                raise BackendApiError("Backend request failed", status=response.status, details=data)
            return data

    async def _ws_loop(self) -> None:
        if not self._session:
            raise BackendApiError("Backend client not started")

        while not self._stop_event.is_set():
            try:
                async with self._session.ws_connect(self.config.backend_ws_url, heartbeat=25) as ws:
                    self._reconnect_attempt = 0
                    await ws.send_json({"type": "bot.subscribe", "token": self.config.bot_ws_token})

                    async for msg in ws:
                        if self._stop_event.is_set():
                            return

                        if msg.type != aiohttp.WSMsgType.TEXT:
                            continue

                        try:
                            payload = json.loads(msg.data)
                        except json.JSONDecodeError:
                            continue

                        if payload.get("type") in {"bot.command_result", "bot.device_status", "bot.device_event"}:
                            await self.events.put(payload)

            except asyncio.CancelledError:
                raise
            except Exception:
                # Backoff reconnect on transient network/server failures.
                pass

            self._reconnect_attempt += 1
            delay = min(30, 2 ** min(6, self._reconnect_attempt))
            try:
                await asyncio.wait_for(self._stop_event.wait(), timeout=delay)
            except asyncio.TimeoutError:
                continue
