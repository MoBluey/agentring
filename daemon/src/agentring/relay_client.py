"""HTTP client for the relay REST API (protocol/relay.openapi.yaml)."""

from __future__ import annotations

import copy
import hashlib
import mimetypes
import os
import re
import stat
import tempfile
from pathlib import Path
from typing import Any

import httpx

from . import __version__


class RelayError(Exception):
    def __init__(self, status: int, code: str, message: str):
        super().__init__(f"{code}: {message} (HTTP {status})")
        self.status = status
        self.code = code


class RelayClient:
    def __init__(self, relay_url: str, api_key: str, *, timeout: float = 15.0, transport: httpx.BaseTransport | None = None, chat_id: str | None = None):
        self.relay_url = relay_url.rstrip("/")
        self.chat_id = chat_id or os.environ.get("AGENTRING_CHAT_ID")
        self._auth_state = {"unauthorized": False}
        self._http = httpx.Client(
            base_url=self.relay_url,
            headers={"authorization": f"Bearer {api_key}", "user-agent": f"agentring-daemon/{__version__}"},
            timeout=timeout,
            transport=transport,
        )

    @property
    def unauthorized(self) -> bool:
        return self._auth_state["unauthorized"]

    @unauthorized.setter
    def unauthorized(self, value: bool) -> None:
        self._auth_state["unauthorized"] = value

    def with_chat(self, chat_id: str) -> RelayClient:
        view = copy.copy(self)
        view.chat_id = chat_id
        return view

    def close(self) -> None:
        self._http.close()

    def __enter__(self) -> RelayClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def _request(self, method: str, path: str, **kw: Any) -> Any:
        try:
            r = self._http.request(method, path, **kw)
        except httpx.HTTPError as e:
            raise RelayError(0, "unreachable", f"cannot reach relay at {self.relay_url}: {e}") from e
        if r.status_code >= 400:
            try:
                err = r.json()["error"]
                code, message = str(err.get("code", "error")), str(err.get("message", r.text))
            except (ValueError, KeyError, TypeError, AttributeError):
                code, message = "error", r.text[:300]
            # The key no longer opens this relay: rotated or revoked (401), or, on a hosted relay,
            # the account was disabled (403 tenant_disabled) or deleted (404 "Tenant not found").
            # The MCP HTTP server checks this to stop accepting the key.
            if r.status_code == 401 or code == "tenant_disabled" or (r.status_code == 404 and message == "Tenant not found"):
                self.unauthorized = True
            raise RelayError(r.status_code, code, message)
        if r.status_code == 204 or not r.content:
            return None
        return r.json()

    # system
    def status(self) -> dict:
        return self._request("GET", "/v1/status")

    # pairing / devices
    def create_pairing_token(self, ttl_s: int | None = None) -> dict:
        return self._request("POST", "/v1/pairing-tokens", json={"ttl_s": ttl_s} if ttl_s else {})

    def list_devices(self, include_revoked: bool = False) -> list[dict]:
        params = {"include_revoked": "true"} if include_revoked else None
        return self._request("GET", "/v1/devices", params=params)["devices"]

    def revoke_device(self, device_id: str) -> None:
        self._request("DELETE", f"/v1/devices/{device_id}")

    # agent session chats and file transfers
    def open_chat(self, session_id: str, *, provider: str | None = None, model: str | None = None,
                  project: str | None = None, title: str | None = None, description: str | None = None) -> dict:
        body: dict[str, Any] = {"client_session_id": session_id}
        caller = {k: v for k, v in {"provider": provider, "model": model, "project": project}.items() if v is not None}
        if caller:
            body["caller"] = caller
        if title is not None:
            body["title"] = title
        if description is not None:
            body["description"] = description
        return self._request("POST", "/v1/chats", json=body)

    def list_chats(self) -> list[dict]:
        return self._request("GET", "/v1/chats")["chats"]

    def get_chat(self, chat_id: str) -> dict:
        return self._request("GET", f"/v1/chats/{chat_id}")

    def upload_file(self, path: str | Path, *, chat_id: str | None = None, media_type: str | None = None) -> dict:
        path = Path(path).expanduser()
        with path.open("rb") as source:
            info = os.fstat(source.fileno())
            if not stat.S_ISREG(info.st_mode):
                raise ValueError("Upload source must be a regular file")
            if info.st_size > 100 * 1024 * 1024:
                raise ValueError("File exceeds 100 MiB")
            digest = hashlib.sha256()
            def chunks():
                while data := source.read(64 * 1024):
                    digest.update(data)
                    yield data
            params = {"name": path.name, "media_type": media_type or mimetypes.guess_type(path.name)[0] or "application/octet-stream"}
            chat_id = chat_id if chat_id is not None else self.chat_id
            if chat_id is not None:
                params["chat_id"] = chat_id
            result = self._request("POST", "/v1/attachments", params=params, content=chunks(), timeout=120,
                                   headers={"content-type": "application/octet-stream", "content-length": str(info.st_size)})
            if result["size_bytes"] != info.st_size or result["sha256"] != digest.hexdigest():
                raise ValueError("Uploaded file checksum or size does not match")
            return result

    def get_attachment(self, attachment_id: str) -> dict:
        if not re.fullmatch(r"att_[0-9A-HJKMNP-TV-Z]{26}", attachment_id):
            raise ValueError("Invalid attachment ID")
        return self._request("GET", f"/v1/attachments/{attachment_id}")

    def download_file(self, attachment_id: str, destination: str | Path | None = None) -> dict:
        meta = self.get_attachment(attachment_id)
        safe_name = Path(meta["name"].replace("\\", "/")).name
        if safe_name in ("", ".", "..") or any(ord(c) < 32 for c in safe_name):
            raise ValueError("Invalid attachment filename")
        if destination is None:
            destination = Path.home() / "Downloads" / f"{attachment_id}-{safe_name}"
        target = Path(destination).expanduser().absolute()
        if target.is_dir():
            target /= safe_name
        if target.exists() or target.is_symlink():
            raise FileExistsError(f"Destination already exists: {target}")
        target.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(prefix=".attachment-", suffix=".part", dir=target.parent)
        digest = hashlib.sha256()
        count = 0
        try:
            with os.fdopen(fd, "wb") as output:
                with self._http.stream("GET", f"/v1/attachments/{attachment_id}/content", timeout=120) as response:
                    if response.status_code != 200:
                        response.read()
                        if response.status_code == 401:
                            self.unauthorized = True
                        raise RelayError(response.status_code, "download_failed", response.text[:300])
                    for data in response.iter_bytes(64 * 1024):
                        count += len(data)
                        if count > meta["size_bytes"] or count > 100 * 1024 * 1024:
                            raise ValueError("Download exceeds declared size")
                        digest.update(data)
                        output.write(data)
                output.flush()
                os.fsync(output.fileno())
            if count != meta["size_bytes"] or digest.hexdigest() != meta["sha256"]:
                raise ValueError("Downloaded file checksum or size does not match")
            os.link(temporary, target)  # atomic, exclusive publication; never overwrite a user file
        finally:
            os.unlink(temporary)
        return {"attachment": meta, "path": str(target)}

    def send_file(self, path: str | Path, text: str = "", *, chat_id: str | None = None) -> dict:
        attachment = self.upload_file(path, chat_id=chat_id)
        return self.post_message(text, chat_id=attachment["chat_id"], attachment_ids=[attachment["attachment_id"]])

    # messages
    def post_message(self, text: str, *, reply_to: str | None = None, client_msg_id: str | None = None,
                     chat_id: str | None = None, attachment_ids: list[str] | None = None) -> dict:
        body: dict[str, Any] = {"text": text}
        chat_id = chat_id if chat_id is not None else self.chat_id
        if chat_id is not None:
            body["chat_id"] = chat_id
        if attachment_ids is not None:
            body["attachment_ids"] = attachment_ids
        if reply_to:
            body["reply_to"] = reply_to
        if client_msg_id:
            body["client_msg_id"] = client_msg_id
        return self._request("POST", "/v1/messages", json=body)

    def get_message(self, msg_id: str) -> dict:
        return self._request("GET", f"/v1/messages/{msg_id}")

    def mark_read(self, msg_id: str) -> None:
        self._request("POST", f"/v1/messages/{msg_id}/read")

    # calls
    def create_call(
        self,
        reason: str,
        *,
        priority: str = "normal",
        agent_name: str | None = None,
        device_ids: list[str] | None = None,
        chat_id: str | None = None,
    ) -> dict:
        """Ring every paired phone, or only `device_ids` (a targeted call)."""
        body: dict[str, Any] = {"reason": reason, "priority": priority}
        chat_id = chat_id if chat_id is not None else self.chat_id
        if chat_id is not None:
            body["chat_id"] = chat_id
        if agent_name:
            body["agent_name"] = agent_name
        if device_ids is not None:
            body["device_ids"] = list(device_ids)
        return self._request("POST", "/v1/calls", json=body)

    def get_call(self, call_id: str) -> dict:
        return self._request("GET", f"/v1/calls/{call_id}")

    def accept_call(self, call_id: str) -> dict:
        return self._request("POST", f"/v1/calls/{call_id}/accept")

    def end_call(self, call_id: str) -> dict:
        return self._request("POST", f"/v1/calls/{call_id}/end")

    # events
    def list_events(self, after_seq: int = 0, limit: int = 500) -> dict:
        return self._request("GET", "/v1/events", params={"after_seq": after_seq, "limit": limit})

    def ws_url(self, after_seq: int) -> str:
        base = self.relay_url.replace("https://", "wss://", 1).replace("http://", "ws://", 1)
        return f"{base}/v1/ws?after_seq={after_seq}"
