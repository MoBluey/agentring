"""Serve routing against a real relay; no model or phone call is started."""
import asyncio
from types import SimpleNamespace

from typer.testing import CliRunner

from agentring.relay_client import RelayClient
from agentring.reply_router import run_reply_router
from agentring.store import Store
from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing


async def until(predicate, timeout=5):
    async def wait():
        while not predicate():
            await asyncio.sleep(.01)
    await asyncio.wait_for(wait(), timeout)


def test_pending_startup_retry_is_scoped_serial_and_does_not_consume_unowned(relay, tmp_path):
    with RelayClient(relay, API_KEY) as client:
        phone = simulate_phone_pairing(client.create_pairing_token()["qr_payload"])
        owned = client.open_chat("owned-router", callee="codex")
        desktop = client.open_chat("desktop-router", callee="codex")
        disabled = client.open_chat("disabled-router", callee="claude")
        with RelayClient(relay, phone["device_jwt"]) as device:
            pending = device.post_message("startup input", chat_id=owned["chat_id"])
            untouched = device.post_message("desktop input", chat_id=desktop["chat_id"])
            other = device.post_message("unconfigured input", chat_id=disabled["chat_id"])
        store = Store(tmp_path / "router.sqlite")
        async def run():
            stop = asyncio.Event(); attempts = []; generated = {}; active = 0; maximum = 0
            class Choice:
                async def reply_to_chat(self, chat, messages, *, tools):
                    nonlocal active, maximum
                    if chat["chat_id"] != owned["chat_id"]:
                        raise ValueError("not bridge-owned")
                    active += 1; maximum = max(maximum, active)
                    try:
                        attempts.append(messages[0]["msg_id"])
                        if len(attempts) == 1:
                            raise ValueError("voice owns session; retry later")
                        for message in messages:
                            key = message["msg_id"]
                            generated.setdefault(key, "one provider response")
                            await asyncio.sleep(.04)
                            await asyncio.to_thread(tools.client.post_message, generated[key],
                                                    chat_id=chat["chat_id"], reply_to=key, client_msg_id=key)
                            if len(attempts) == 2:
                                raise OSError("ack failed after durable response and post")
                            await tools.acknowledge_replies([key], chat_id=chat["chat_id"])
                    finally:
                        active -= 1
            original = client.list_chats
            polls = 0
            def reconnect():
                nonlocal polls
                polls += 1
                if polls == 1: raise OSError("offline")
                return original()
            client.list_chats = reconnect
            task = asyncio.create_task(run_reply_router(client, store, {"codex": Choice()}, stop, poll_s=.01))
            try:
                await until(lambda: len(attempts) >= 3)
                await until(lambda: client.get_message(pending["msg_id"]).get("read_at") is not None)
            finally:
                stop.set(); await task
            assert maximum == 1 and len(generated) == 1 and len(attempts) == 3
            replies = client._request("GET", "/v1/messages", params={"chat_id": owned["chat_id"], "from": "agent"})["messages"]
            assert len(replies) == 1 and replies[0]["reply_to"] == pending["msg_id"]
            assert "read_at" not in client.get_message(untouched["msg_id"])
            assert "read_at" not in client.get_message(other["msg_id"])
        try: asyncio.run(run())
        finally: store.close()


def test_stop_cancels_and_joins_inflight_hook(relay, tmp_path):
    with RelayClient(relay, API_KEY) as client:
        phone = simulate_phone_pairing(client.create_pairing_token()["qr_payload"])
        chat = client.open_chat("cancel-router", callee="codex")
        with RelayClient(relay, phone["device_jwt"]) as device:
            message = device.post_message("wait", chat_id=chat["chat_id"])
        store = Store(tmp_path / "cancel.sqlite")
        async def run():
            stop = asyncio.Event(); started = asyncio.Event(); closed = []
            class Choice:
                async def reply_to_chat(self, chat, messages, *, tools):
                    started.set()
                    try: await asyncio.Future()
                    finally: closed.append(True)
            task = asyncio.create_task(run_reply_router(client, store, {"codex": Choice()}, stop, poll_s=.01))
            await asyncio.wait_for(started.wait(), 5)
            stop.set(); await asyncio.wait_for(task, 5)
            assert closed == [True]
            assert "read_at" not in client.get_message(message["msg_id"])
        try: asyncio.run(run())
        finally: store.close()


def test_serve_invokes_only_explicit_selected_choice_hook(relay, agentring_home, monkeypatch):
    import agentring.cli as cli
    from agentring.voice import providers
    runner = CliRunner()
    assert runner.invoke(cli.app, ["init", "--relay", relay, "--api-key", API_KEY]).exit_code == 0
    with RelayClient(relay, API_KEY) as client:
        phone = simulate_phone_pairing(client.create_pairing_token()["qr_payload"])
        chat = client.open_chat("serve-router", callee="codex")
        with RelayClient(relay, phone["device_jwt"]) as device:
            message = device.post_message("actual serve path", chat_id=chat["chat_id"])
        selected = []; closed = []
        class Choice:
            async def reply_to_chat(self, chat, messages, *, tools):
                for row in messages:
                    await asyncio.to_thread(tools.client.post_message, "routed acknowledgement", chat_id=chat["chat_id"], reply_to=row["msg_id"], client_msg_id=row["msg_id"])
                    await tools.acknowledge_replies([row["msg_id"]], chat_id=chat["chat_id"])
        def resolve(name, *args): selected.append(name); return Choice()
        async def build(*args, **kwargs):
            async def close(): closed.append(True)
            return SimpleNamespace(agent=SimpleNamespace(tools=[]), aclose=close)
        async def ws(client, key, store, on_event, *, stop):
            await until(lambda: client.get_message(message["msg_id"]).get("read_at") is not None)
            stop.set()
        monkeypatch.setattr(providers, "resolve_voice", resolve)
        monkeypatch.setattr(cli, "_build_ai", build)
        monkeypatch.setattr(cli, "_signals", lambda stop: None)
        monkeypatch.setattr(cli, "run_ws", ws)
        result = runner.invoke(cli.app, ["serve", "--ai", "--call-provider", "codex=selected-fixture"])
        assert result.exit_code == 0, result.output
        assert selected == ["selected-fixture"] and closed == [True]
        assert client.get_message(message["msg_id"])["read_at"]
