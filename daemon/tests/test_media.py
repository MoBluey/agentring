"""Real-relay coverage for bidirectional media and independent agent sessions."""
from __future__ import annotations
import asyncio
import hashlib
import json
import socket
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from agentring.cli import app
from agentring.mcp_server import AgentringTools, handle_rpc
from agentring.relay_client import RelayClient, RelayError
from agentring.store import Store
from agentring.sync import catch_up
from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing


def test_bidirectional_transfer_chat_scope_and_restart(relay, tmp_path):
    with RelayClient(relay, API_KEY) as agent:
        phone = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        a = agent.open_chat("coding-session-a", provider="openai", model="verified-coding-model", project="project-a")
        b = agent.open_chat("coding-session-b", provider="local", model="local-model", project="project-b")
        switched = agent.open_chat("coding-session-a", model="new-coding-model")
        assert switched["chat_id"] == a["chat_id"]
        assert switched["caller"] == {"provider": "openai", "model": "new-coding-model", "project": "project-a"}
        assert "new-coding-model" in switched["title"]
        original = tmp_path / "all-bytes.bin"
        original.write_bytes(bytes(range(256)) * 8192)
        outbound = agent.send_file(original, "Agent to phone", chat_id=a["chat_id"])
        attachment = outbound["attachments"][0]
        assert attachment["chat_id"] == a["chat_id"]
        with RelayClient(relay, phone["device_jwt"]) as device:
            got = device.download_file(attachment["attachment_id"], tmp_path / "phone-download.bin")
            assert Path(got["path"]).read_bytes() == original.read_bytes()
            inbound = device.send_file(original, "Phone to agent", chat_id=a["chat_id"])
            device.post_message("Other session", chat_id=b["chat_id"])
            with pytest.raises(RelayError) as denied:
                device.open_chat("coding-session-a", model="spoofed")
            assert denied.value.status == 403
        # A fresh daemon mirror catches up through the production event API, including attachments and chats.
        store = Store(tmp_path / "mirror.sqlite")
        catch_up(agent, store)
        assert store.replies(chat_id=a["chat_id"])[0]["attachments"] == inbound["attachments"]
        cursor = store.last_seq
        store.close()
        store = Store(tmp_path / "mirror.sqlite")
        assert store.last_seq == cursor
        catch_up(agent, store)
        tools = AgentringTools(agent, store, local_files=True)
        first = asyncio.run(tools.get_replies(chat_id=a["chat_id"]))["replies"]
        assert [r["text"] for r in first] == ["Phone to agent"]
        assert first[0]["chat_id"] == a["chat_id"]
        assert [r["text"] for r in asyncio.run(tools.get_replies(chat_id=b["chat_id"]))["replies"]] == ["Other session"]
        assert asyncio.run(tools.get_replies(chat_id=a["chat_id"]))["replies"] == []
        result = agent.download_file(inbound["attachments"][0]["attachment_id"], tmp_path / "agent-download.bin")
        assert Path(result["path"]).read_bytes() == original.read_bytes()
        with pytest.raises(FileExistsError):
            agent.download_file(attachment["attachment_id"], result["path"])
        assert not list(tmp_path.glob(".attachment-*.part"))
        store.close()


def test_attachment_auth_ownership_path_and_chat(relay, tmp_path):
    with RelayClient(relay, API_KEY) as agent:
        p1 = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        p2 = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        a = agent.open_chat("session-a")["chat_id"]
        b = agent.open_chat("session-b")["chat_id"]
        file = tmp_path / "note.txt"
        file.write_text("Original bytes")
        with RelayClient(relay, p1["device_jwt"]) as first, RelayClient(relay, p2["device_jwt"]) as second:
            private = first.upload_file(file, chat_id=a)
            for other in (agent, second):
                with pytest.raises(RelayError) as denied:
                    other.get_attachment(private["attachment_id"])
                assert denied.value.status == 404
                with pytest.raises(RelayError) as denied:
                    other.post_message("", chat_id=a, attachment_ids=[private["attachment_id"]])
                assert denied.value.status == 404
            with pytest.raises(RelayError):
                first.post_message("", chat_id=b, attachment_ids=[private["attachment_id"]])
            message = first.post_message("", chat_id=a, attachment_ids=[private["attachment_id"]])
            assert second.get_attachment(private["attachment_id"])["sha256"] == hashlib.sha256(file.read_bytes()).hexdigest()
            with pytest.raises(RelayError):
                first.post_message("reused", chat_id=a, attachment_ids=[private["attachment_id"]])
            response = httpx.get(f"{relay}/v1/attachments/{private['attachment_id']}/content")
            assert response.status_code == 401
            response = httpx.post(f"{relay}/v1/attachments", params={"name": "../../escape"}, headers=p1["headers"] | {"content-type": "application/octet-stream"}, content=b"x")
            assert response.status_code == 400
            response = httpx.post(f"{relay}/v1/messages", headers=p1["headers"], json={"text": "spoof", "chat_id": a, "caller": {"model": "fake"}})
            assert response.status_code == 201  # Fastify strips unknown fields. They never update chat metadata.
            assert agent.get_chat(a)["caller"] == {}
            agent.revoke_device(p1["device_id"])
            with pytest.raises(RelayError) as denied:
                first.get_attachment(private["attachment_id"])
            assert denied.value.status == 401
            # One source key can use an idempotency key in independent session chats.
            ma = agent.post_message("a", chat_id=a, client_msg_id="same-key")
            mb = agent.post_message("b", chat_id=b, client_msg_id="same-key")
            assert ma["msg_id"] != mb["msg_id"]
            assert message["chat_id"] == a


def test_oversized_and_interrupted_upload_cleanup(relay, tmp_path):
    parsed = httpx.URL(relay)
    def connect():
        return socket.create_connection((parsed.host, parsed.port), timeout=3)
    with connect() as s:
        s.sendall((f"POST /v1/attachments?name=large.bin HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {API_KEY}\r\nContent-Type: application/octet-stream\r\nContent-Length: {100 * 1024 * 1024 + 1}\r\nConnection: close\r\n\r\n").encode())
        assert b"413" in s.recv(4096).split(b"\r\n")[0]
    with connect() as s:
        s.sendall((f"POST /v1/attachments?name=interrupted.bin HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {API_KEY}\r\nContent-Type: application/octet-stream\r\nContent-Length: 100000\r\n\r\npartial").encode())
    import time
    deadline = time.monotonic() + 3
    folder = tmp_path / "relay-data" / "attachments"
    while time.monotonic() < deadline and list(folder.glob("*.part")):
        time.sleep(0.05)
    assert not list(folder.glob("*.part"))
    with RelayClient(relay, API_KEY) as client:
        assert client.status()["relay_id"]


def test_cli_and_stdio_tools(relay, agentring_home, tmp_path):
    runner = CliRunner()
    assert runner.invoke(app, ["init", "--relay", relay, "--api-key", API_KEY]).exit_code == 0
    made = runner.invoke(app, ["chats", "create", "--session", "cli-session", "--provider", "local", "--model", "actual", "--project", "fixture", "--json"])
    assert made.exit_code == 0, made.output
    chat = json.loads(made.output)
    source = tmp_path / "cli.txt"
    source.write_text("CLI attachment")
    sent = runner.invoke(app, ["send-file", str(source), "--chat", chat["chat_id"], "--text", "Caption", "--json"])
    assert sent.exit_code == 0, sent.output
    message = json.loads(sent.output)
    dest = tmp_path / "cli-download.txt"
    got = runner.invoke(app, ["download-file", message["attachments"][0]["attachment_id"], "--destination", str(dest), "--json"])
    assert got.exit_code == 0, got.output
    assert dest.read_bytes() == source.read_bytes()
    with RelayClient(relay, API_KEY) as client:
        store = Store(":memory:")
        tools = AgentringTools(client, store, local_files=True)
        opened = asyncio.run(tools.open_chat("stdio-session", provider="openai", model="explicit-model", project="fixture"))
        file_message = asyncio.run(tools.send_file(str(source)))
        assert file_message["chat_id"] == opened["chat_id"]
        remote = AgentringTools(client, store)
        listed = asyncio.run(handle_rpc(remote, {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}))["result"]["tools"]
        assert "send_file" not in {t["name"] for t in listed}
        denied = asyncio.run(handle_rpc(remote, {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "send_file", "arguments": {"path": str(source)}}}))
        assert denied["error"]["code"] == -32602
        with pytest.raises(Exception, match="stdio only"):
            asyncio.run(remote.download_file(message["attachments"][0]["attachment_id"]))
        store.close()


def test_hub_attachments_remain_tenant_private(hub, tmp_path):
    from .test_hub_e2e import new_tenant
    a, b = new_tenant(hub), new_tenant(hub)
    source = tmp_path / "private.txt"
    source.write_text("tenant-a-only")
    with RelayClient(a["tenant"]["base_url"], a["api_key"]) as first, RelayClient(b["tenant"]["base_url"], b["api_key"]) as second:
        message = first.send_file(source)
        attachment_id = message["attachments"][0]["attachment_id"]
        assert first.get_attachment(attachment_id)["name"] == source.name
        with pytest.raises(RelayError) as denied:
            second.get_attachment(attachment_id)
        assert denied.value.status == 404
        response = httpx.get(f"{a['tenant']['base_url']}/v1/attachments/{attachment_id}/content", headers={"authorization": "Bearer " + b["api_key"]})
        assert response.status_code == 401
