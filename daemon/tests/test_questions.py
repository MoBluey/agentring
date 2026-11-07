"""Real-relay checks for structured questions, raw Markdown and truthful receipts."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path

import httpx
import pytest
from typer.testing import CliRunner

from agentring.cli import app
from agentring.mcp_server import AgentringTools
from agentring.relay_client import RelayClient, RelayError
from agentring.store import Store
from agentring.sync import catch_up
from .conftest import API_KEY
from .test_e2e import simulate_phone_pairing

OPTIONS = [{"id": "ship", "label": "Ship it"}, {"id": "hold", "label": "Hold"}]


def test_question_mcp_to_device_to_originating_session(relay, tmp_path):
    with RelayClient(relay, API_KEY) as agent:
        phone = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        store = Store(tmp_path / "questions.sqlite")
        tools = AgentringTools(agent, store)
        chat = asyncio.run(tools.open_chat("question-session", provider="openai", model="verified", project="fixture"))
        other = agent.open_chat("other-session")
        prompt = "**Deploy?**\n\n```sh\n./deploy --dry-run\n```\n\n==Review required=="
        asked = asyncio.run(tools.ask_question(prompt, OPTIONS, "ask-1", allow_free_text=True))
        retried = asyncio.run(tools.ask_question(prompt, OPTIONS, "ask-1", allow_free_text=True))
        assert retried["msg_id"] == asked["msg_id"]
        assert asked["text"] == prompt
        assert asked["chat_id"] == chat["chat_id"]
        assert asked["question"] == {"options": OPTIONS, "allow_free_text": True}
        assert "delivered_at" not in asked and "read_at" not in asked
        with RelayClient(relay, phone["device_jwt"]) as device:
            received = device.get_message(asked["msg_id"])
            assert received["text"] == prompt
            device.mark_received(asked["msg_id"])
            assert "read_at" not in agent.get_message(asked["msg_id"])
            answer = {"question_id": asked["msg_id"], "option_id": "ship"}
            sent = device.post_message("", chat_id=chat["chat_id"], answer=answer, client_msg_id="answer-1")
            assert sent["text"] == "Ship it" and sent["reply_to"] == asked["msg_id"]
            assert sent["answer"] == answer
            assert device.post_message("", chat_id=chat["chat_id"], answer=answer, client_msg_id="answer-1")["msg_id"] == sent["msg_id"]
        # Receiving/persisting all chats can prove delivery, but only the originating session consumes the answer.
        assert asyncio.run(tools.get_replies(chat_id=other["chat_id"]))["replies"] == []
        assert "read_at" not in agent.get_message(sent["msg_id"])
        replies = asyncio.run(tools.get_replies())["replies"]
        assert len(replies) == 1 and replies[0]["answer"] == answer
        assert replies[0]["text"] == "Ship it" and replies[0]["chat_id"] == chat["chat_id"]
        assert "read_at" not in agent.get_message(sent["msg_id"])
        asyncio.run(tools.acknowledge_replies([sent["msg_id"]]))
        assert agent.get_message(sent["msg_id"])["read_at"]
        assert asyncio.run(tools.get_replies())["replies"] == []
        updated = agent.get_message(asked["msg_id"])["question"]
        assert updated["answered_by_msg_id"] == sent["msg_id"] and updated["answered_at"] == sent["created_at"]
        catch_up(agent, store)
        local = json.loads(store.db.execute("SELECT question_json FROM messages WHERE msg_id=?", (asked["msg_id"],)).fetchone()[0])
        assert local == updated
        events = agent.list_events()["events"]
        answered = [e for e in events if e["type"] == "question.answered"]
        assert len(answered) == 1 and answered[0]["answer_msg_id"] == sent["msg_id"]
        assert [e for e in events if e["type"] == "msg.new" and e["msg_id"] == asked["msg_id"]][0]["question"]["options"] == OPTIONS
        store.close()
        reopened = Store(tmp_path / "questions.sqlite")
        catch_up(agent, reopened)
        assert reopened.replies(chat_id=chat["chat_id"])[0]["answer"] == answer
        reopened.close()


def test_question_validation_first_answer_wins_and_idempotency(relay):
    with RelayClient(relay, API_KEY) as agent:
        one = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        two = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        a, b = agent.open_chat("a"), agent.open_chat("b")
        asked = agent.ask_question("Pick one", OPTIONS, chat_id=a["chat_id"], client_msg_id="question", allow_free_text=False)
        with RelayClient(relay, one["device_jwt"]) as d1, RelayClient(relay, two["device_jwt"]) as d2:
            def rejected(client, *, status, code=None, **body):
                with pytest.raises(RelayError) as exc:
                    client.post_message(**body)
                assert exc.value.status == status
                if code:
                    assert exc.value.code == code
            common = {"text": "", "chat_id": a["chat_id"], "client_msg_id": "attempt"}
            rejected(d1, status=403, text="spoof", question={"options": OPTIONS}, client_msg_id="spoof")
            rejected(agent, status=403, **common, answer={"question_id": asked["msg_id"], "option_id": "ship"})
            rejected(d1, status=400, **(common | {"chat_id": b["chat_id"]}), answer={"question_id": asked["msg_id"], "option_id": "ship"})
            rejected(d1, status=400, **common, answer={"question_id": asked["msg_id"], "option_id": "unknown"})
            rejected(d1, status=400, **common, answer={"question_id": asked["msg_id"], "free_text": "custom"})
            rejected(d1, status=400, **common, answer={"question_id": asked["msg_id"], "option_id": "ship", "free_text": "both"})
            rejected(d1, status=400, text="", chat_id=a["chat_id"], answer={"question_id": asked["msg_id"], "option_id": "ship"})
            rejected(agent, status=400, text="Duplicate", client_msg_id="dup", question={"options": [OPTIONS[0], OPTIONS[0]]})
            rejected(agent, status=409, code="idempotency_conflict", text="Changed prompt", chat_id=a["chat_id"], client_msg_id="question", question={"options": OPTIONS})
            first = d1.post_message("untrusted display text", **{k:v for k,v in common.items() if k!="text"}, answer={"question_id": asked["msg_id"], "option_id": "hold"})
            assert first["text"] == "Hold"
            rejected(d1, status=409, code="idempotency_conflict", **common, answer={"question_id": asked["msg_id"], "option_id": "ship"})
            rejected(d2, status=409, code="question_answered", **common, answer={"question_id": asked["msg_id"], "option_id": "ship"})
            agent.revoke_device(two["device_id"])
            rejected(d2, status=401, **common, answer={"question_id": asked["msg_id"], "option_id": "ship"})


def test_free_text_and_cli_raw_markdown(relay, agentring_home):
    runner = CliRunner()
    assert runner.invoke(app, ["init", "--relay", relay, "--api-key", API_KEY]).exit_code == 0
    prompt = "**Choose** `safe` or ==custom==\n\n```text\nraw <tag>\n```"
    args = ["ask-question", prompt, "--option", "a=Alpha", "--option", "b=Beta", "--allow-free-text", "--client-msg-id", "cli-question", "--json"]
    result = runner.invoke(app, args)
    assert result.exit_code == 0, result.output
    asked = json.loads(result.output)
    assert asked["text"] == prompt
    assert json.loads(runner.invoke(app, args).output)["msg_id"] == asked["msg_id"]
    with RelayClient(relay, API_KEY) as agent:
        phone = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        with RelayClient(relay, phone["device_jwt"]) as device:
            raw = "Use **my alternative**\n\n`literal source`"
            answer = {"question_id": asked["msg_id"], "free_text": raw}
            reply = device.post_message("", answer=answer, client_msg_id="free-1")
            assert reply["text"] == raw
        got = runner.invoke(app, ["replies", "--json"])
        assert got.exit_code == 0, got.output
        assert json.loads(got.output)["replies"][0]["answer"] == answer
        assert "read_at" not in agent.get_message(reply["msg_id"])
        acked = runner.invoke(app, ["acknowledge-replies", reply["msg_id"], "--json"])
        assert acked.exit_code == 0, acked.output
        assert json.loads(acked.output)["acknowledged"] == [reply["msg_id"]]
        assert "read_at" in agent.get_message(reply["msg_id"])


def test_truthful_receipts_and_failed_ack_retry(relay, tmp_path):
    with RelayClient(relay, API_KEY) as agent:
        phone = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        sent = agent.post_message("Sent only")
        assert "delivered_at" not in sent and "read_at" not in sent
        with pytest.raises(RelayError) as own:
            agent.mark_received(sent["msg_id"])
        assert own.value.status == 404
        with RelayClient(relay, phone["device_jwt"]) as device:
            device.get_message(sent["msg_id"])
            assert "delivered_at" not in agent.get_message(sent["msg_id"])  # GET alone is no receipt.
            device.mark_received(sent["msg_id"])
            once = agent.get_message(sent["msg_id"])
            assert once["delivered_at"] and "read_at" not in once
            device.mark_received(sent["msg_id"])
            device.mark_read(sent["msg_id"])
            device.mark_read(sent["msg_id"])
            read = agent.get_message(sent["msg_id"])
            assert read["delivered_at"] == once["delivered_at"] and read["read_at"] >= read["delivered_at"]
            # A read is sufficient evidence of delivery for an older client.
            legacy = agent.post_message("Legacy read")
            device.mark_read(legacy["msg_id"])
            assert agent.get_message(legacy["msg_id"])["delivered_at"]
            user = device.post_message("Persist before ack")
            with pytest.raises(RelayError):
                device.mark_read(user["msg_id"])
        store = Store(tmp_path / "receipts.sqlite")
        real_ack = agent.mark_received
        def fail(msg_id):
            raise RelayError(503, "unreachable", "fixture acknowledgement failure")
        agent.mark_received = fail
        catch_up(agent, store)
        assert store.replies()[0]["text"] == "Persist before ack"
        assert "delivered_at" not in agent.get_message(user["msg_id"])
        agent.mark_received = real_ack
        catch_up(agent, store)
        received = agent.get_message(user["msg_id"])
        assert received["delivered_at"] and "read_at" not in received
        events = agent.list_events()["events"]
        assert len([e for e in events if e["type"] == "msg.delivered" and e["msg_id"] == sent["msg_id"]]) == 1
        assert len([e for e in events if e["type"] == "msg.read" and e["msg_id"] == sent["msg_id"]]) == 1
        store.close()


def test_simultaneous_devices_cannot_answer_twice(relay):
    from concurrent.futures import ThreadPoolExecutor
    import threading
    with RelayClient(relay, API_KEY) as agent:
        phones = [simulate_phone_pairing(agent.create_pairing_token()["qr_payload"]) for _ in range(2)]
        asked = agent.ask_question("Race", OPTIONS, client_msg_id="race-question")
        barrier = threading.Barrier(2)
        def submit(index):
            with httpx.Client() as http:
                barrier.wait()
                return http.post(f"{relay}/v1/messages", headers=phones[index]["headers"], json={
                    "text": "", "client_msg_id": f"race-{index}",
                    "answer": {"question_id": asked["msg_id"], "option_id": OPTIONS[index]["id"]}})
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(submit, range(2)))
        assert sorted(r.status_code for r in responses) == [201, 409]
        assert [r.json()["error"]["code"] for r in responses if r.status_code == 409] == ["question_answered"]
        answers = [e for e in agent.list_events()["events"] if e["type"] == "question.answered"]
        assert len(answers) == 1


def test_explicit_acknowledgement_is_chat_scoped_and_not_poll_inferred(relay, tmp_path):
    with RelayClient(relay, API_KEY) as agent:
        phone = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        a, b = agent.open_chat("ack-a"), agent.open_chat("ack-b")
        with RelayClient(relay, phone["device_jwt"]) as device:
            ra = device.post_message("Steer session A", chat_id=a["chat_id"])
            rb = device.post_message("Steer session B", chat_id=b["chat_id"])
        store = Store(tmp_path / "ack.sqlite")
        tools = AgentringTools(agent, store, chat_binding={"chat_id": a["chat_id"]})
        fetched = asyncio.run(tools.get_replies())["replies"]
        assert [r["msg_id"] for r in fetched] == [ra["msg_id"]]
        catch_up(agent, store)  # Polling again cannot infer a read from the fetched cursor.
        assert "read_at" not in agent.get_message(ra["msg_id"])
        assert "read_at" not in agent.get_message(rb["msg_id"])
        with pytest.raises(RelayError):
            asyncio.run(tools.acknowledge_replies([ra["msg_id"], rb["msg_id"]]))
        assert "read_at" not in agent.get_message(ra["msg_id"])  # Validate the whole batch before acknowledging.
        result = asyncio.run(tools.acknowledge_replies([ra["msg_id"]]))
        assert result == {"acknowledged": [ra["msg_id"]], "chat_id": a["chat_id"]}
        assert agent.get_message(ra["msg_id"])["read_at"]
        assert "read_at" not in agent.get_message(rb["msg_id"])
        assert asyncio.run(tools.acknowledge_replies([ra["msg_id"]])) == result
        own = agent.post_message("Own", chat_id=a["chat_id"])
        with pytest.raises(RelayError):
            asyncio.run(tools.acknowledge_replies([own["msg_id"]]))
        store.close()


def test_question_semantic_retry_and_changed_kind_conflict(relay):
    with RelayClient(relay, API_KEY) as agent:
        first = agent.post_message("Prompt", client_msg_id="canonical", question={"options": OPTIONS})
        reordered = {"allow_free_text": False, "options": [{"label": option["label"], "id": option["id"]} for option in OPTIONS]}
        assert agent.post_message("Prompt", client_msg_id="canonical", question=reordered)["msg_id"] == first["msg_id"]
        with pytest.raises(RelayError) as conflict:
            agent.post_message("Prompt", client_msg_id="canonical")
        assert conflict.value.code == "idempotency_conflict"
        # The public helper offers a free-written alternative unless explicitly disabled.
        offered = agent.ask_question("Public default", OPTIONS, client_msg_id="free-default")
        assert offered["question"]["allow_free_text"] is True
        phone = simulate_phone_pairing(agent.create_pairing_token()["qr_payload"])
        with RelayClient(relay, phone["device_jwt"]) as device:
            answer = device.post_message("", client_msg_id="canonical-answer", answer={"option_id": "ship", "question_id": first["msg_id"]})
            retry = device.post_message("", client_msg_id="canonical-answer", answer={"question_id": first["msg_id"], "option_id": "ship"})
            assert retry["msg_id"] == answer["msg_id"]
            with pytest.raises(RelayError) as changed:
                device.post_message("Ship it", client_msg_id="canonical-answer")
            assert changed.value.code == "idempotency_conflict"
