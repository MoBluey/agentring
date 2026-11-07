"""M1 acceptance flow against a real relay, with the phone simulated over HTTP."""

from __future__ import annotations

import asyncio
import json
import threading
import time

import httpx
from typer.testing import CliRunner

from agentring.cli import app
from agentring.config import load_settings
from agentring.relay_client import RelayClient
from agentring.store import Store
from agentring.sync import run_ws

from .conftest import API_KEY

runner = CliRunner()


def cli(*args: str) -> str:
    r = runner.invoke(app, list(args))
    assert r.exit_code == 0, r.output
    return r.output


def simulate_phone_pairing(qr_payload: str) -> dict:
    """What the Android app does after scanning the QR."""
    qr = json.loads(qr_payload)
    r = httpx.post(
        f"{qr['relay_url']}/v1/pair",
        json={"pairing_token": qr["pairing_token"], "device_name": "Test Phone", "platform": "android"},
    )
    assert r.status_code == 201, r.text
    d = r.json()
    d["headers"] = {"authorization": f"Bearer {d['device_jwt']}"}
    d["relay_url"] = qr["relay_url"]
    return d


def test_m1_flow(relay: str, agentring_home):
    out = cli("init", "--relay", relay, "--api-key", API_KEY)
    assert "Connected to relay rly_" in out

    tok = json.loads(cli("pair", "--no-wait", "--json"))
    phone = simulate_phone_pairing(tok["qr_payload"])
    assert phone["agent_name"] == "E2E"
    assert phone["push_config"] is None  # no FCM configured in this test

    status = json.loads(cli("status", "--json"))
    assert status["devices"][0]["name"] == "Test Phone"

    msg_id = json.loads(cli("msg", "hola", "--json"))["msg_id"]

    # Phone sees the message and replies to it.
    msgs = httpx.get(f"{relay}/v1/messages", headers=phone["headers"]).json()["messages"]
    assert [m["text"] for m in msgs] == ["hola"]
    httpx.post(f"{relay}/v1/messages/{msg_id}/read", headers=phone["headers"]).raise_for_status()
    httpx.post(
        f"{relay}/v1/messages",
        headers=phone["headers"],
        json={"text": "¡hola agente!", "reply_to": msg_id, "client_msg_id": "c-1"},
    ).raise_for_status()

    replies = json.loads(cli("replies", "--json"))["replies"]
    assert [r["text"] for r in replies] == ["¡hola agente!"]
    assert replies[0]["reply_to"] == msg_id

    assert "read_at" not in httpx.get(f"{relay}/v1/messages/{replies[0]['msg_id']}", headers=phone["headers"]).json()
    cli("acknowledge-replies", replies[0]["msg_id"], "--json")

    # Already fetched → no new replies; --all still shows it.
    assert json.loads(cli("replies", "--json"))["replies"] == []
    assert len(json.loads(cli("replies", "--all", "--json"))["replies"]) == 1
    assert len(json.loads(cli("replies", "--since", "5m", "--json"))["replies"]) == 1

    # The agent marked it read on the relay → the phone gets a read receipt.
    events = httpx.get(f"{relay}/v1/events", headers=phone["headers"]).json()["events"]
    assert [e["type"] for e in events] == ["msg.new", "msg.delivered", "msg.read", "msg.new", "msg.delivered", "msg.read"]
    assert events[-1]["by"] == "agent"

    # Human-readable output works too.
    assert "¡hola agente!" in cli("replies", "--all")
    assert "Test Phone" in cli("devices")

    # Revocation.
    cli("devices", "revoke", phone["device_id"])
    assert httpx.get(f"{relay}/v1/status", headers=phone["headers"]).status_code == 401


def test_serve_receives_replies_live(relay: str, agentring_home):
    cli("init", "--relay", relay, "--api-key", API_KEY)
    phone = simulate_phone_pairing(json.loads(cli("pair", "--no-wait", "--json"))["qr_payload"])
    s = load_settings()
    store = Store(s.db_path)
    client = RelayClient(s.relay_url, s.api_key)
    seen: list[dict] = []

    loop = asyncio.new_event_loop()
    stop = asyncio.Event()

    def runner_thread():
        asyncio.set_event_loop(loop)
        loop.run_until_complete(run_ws(client, s.api_key, store, seen.append, stop=stop))

    t = threading.Thread(target=runner_thread, daemon=True)
    t.start()
    try:
        # Daemon presence is visible to the phone.
        deadline = time.time() + 5
        while not httpx.get(f"{relay}/v1/status", headers=phone["headers"]).json()["agent_online"]:
            assert time.time() < deadline, "daemon never connected"
            time.sleep(0.05)

        httpx.post(f"{relay}/v1/messages", headers=phone["headers"], json={"text": "live!"}).raise_for_status()
        deadline = time.time() + 5
        while not any(e.get("text") == "live!" for e in seen):
            assert time.time() < deadline, f"no live event: {seen}"
            time.sleep(0.05)
        assert [r["text"] for r in store.replies()] == ["live!"]
        assert store.last_seq >= 1
    finally:
        loop.call_soon_threadsafe(stop.set)
        t.join(5)
        client.close()

    # Offline catch-up: daemon stopped, phone replies, `replies` still sees it.
    httpx.post(f"{relay}/v1/messages", headers=phone["headers"], json={"text": "while offline"}).raise_for_status()
    texts = [r["text"] for r in json.loads(cli("replies", "--json"))["replies"]]
    assert texts == ["live!", "while offline"]
