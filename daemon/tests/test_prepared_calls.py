"""Per-call provider hook plumbing; no model, audio or host process is started."""
import asyncio
import time
from types import SimpleNamespace

import pytest

from agentring.relay_client import RelayError
from agentring.voice.providers import PreparedCall, VoiceConfigError
from agentring.voice.quick import build_agent
from agentring.voice.inbound import run_prepared_incoming


def fixture_call(mode="new"):
    return {"call_id": "call-fixture", "state": "ringing", "callee": "codex", "session_mode": mode,
            "chat_id": "chat-fixture", "chat": {"client_session_id": "pending:call-fixture" if mode == "new" else "actual-session"}}


def fixture_event(seconds=10):
    return {"type": "call.invite", "direction": "user_to_agent", "call_id": "call-fixture", "expires_at": int((time.time()+seconds)*1000)}


class Client:
    def __init__(self, *, fail=False, transient=False):
        self.fail, self.transient = fail, transient
        self.accepts, self.declines, self.ends, self.runs = [], [], [], 0
    def get_call(self, _): return fixture_call()
    def with_chat(self, _): return self
    def accept_call(self, _, *, session):
        self.accepts.append(session)
        if self.fail: raise RelayError(409, "call_ended", "cancelled")
        if self.transient and len(self.accepts)==1: raise RelayError(0, "unreachable", "response lost")
        return {"join": {"livekit_url": "unused", "livekit_token": "unused"}}
    def decline_call(self, _, *, reason): self.declines.append(reason)
    def end_call(self, call_id): self.ends.append(call_id)


class Choice:
    def __init__(self, close, *, mismatch=False): self.close, self.mismatch, self.prepared = close, mismatch, 0
    async def prepare_call(self, call, *, relay):
        self.prepared += 1
        return PreparedCall({"client_session_id": "wrong" if self.mismatch else "actual-session", "callee": "codex", "caller": {"provider": "openai"}}, lambda: object(), self.close)


def test_factory_is_call_bound_and_close_is_idempotent():
    async def run():
        closed=[]
        async def close(): closed.append(True)
        choice=Choice(close)
        built=await build_agent(choice, call=fixture_call(), relay=object())
        assert choice.prepared==1 and built.agent.backend is built.prepared.backend
        await built.aclose();await built.aclose()
        assert closed==[True]
    asyncio.run(run())


def test_missing_or_mismatched_hook_fails_closed_and_closes():
    async def run():
        with pytest.raises(VoiceConfigError):await build_agent(SimpleNamespace(factory=lambda: None),call=fixture_call())
        closed=[]
        async def close():closed.append(True)
        with pytest.raises(VoiceConfigError):await build_agent(Choice(close,mismatch=True),call=fixture_call("existing"))
        assert closed==[True]
    asyncio.run(run())


@pytest.mark.parametrize("fail,transient",[(True,False),(False,True)])
def test_accept_failure_cleanup_and_same_binding_network_retry(fail,transient):
    async def run():
        client=Client(fail=fail,transient=transient);closed=[]
        async def close():closed.append(True)
        choice=Choice(close)
        async def builder(call,scoped):
            built=await build_agent(choice,call=call,relay=scoped)
            async def voice_run(*args,**kwargs):client.runs+=1;return "finished"
            built.agent.run=voice_run
            return built
        result=await run_prepared_incoming(client,fixture_event(),builder)
        assert closed==[True] and choice.prepared==1
        if fail:assert result is None and client.runs==0 and client.declines==["callee_unavailable"]
        else:
            assert result=="finished" and client.runs==1 and len(client.accepts)==2
            assert client.accepts[0] is client.accepts[1]
    asyncio.run(run())


@pytest.mark.parametrize("cancel",[False,True])
def test_expiry_or_cancellation_after_preparation_always_closes(monkeypatch,cancel):
    async def run():
        client=Client();closed=[];waiting=asyncio.Event()
        async def close():closed.append(True)
        async def blocked_mcp(*args,**kwargs):waiting.set();await asyncio.Future()
        import agentring.voice.quick as quick
        monkeypatch.setattr(quick,"mcp_tools",blocked_mcp)
        async def builder(call,scoped):return await build_agent(Choice(close),call=call,relay=scoped,mcp=["unused"])
        task=asyncio.create_task(run_prepared_incoming(client,fixture_event(10 if cancel else .05),builder))
        await waiting.wait()
        if cancel:task.cancel()
        if cancel:
            with pytest.raises(asyncio.CancelledError):await task
        else:assert await task is None
        assert closed==[True] and not client.accepts and client.runs==0
    asyncio.run(run())


@pytest.mark.parametrize("expired", [False, True])
def test_preparation_completion_rechecks_stop_and_deadline(expired):
    async def run():
        client = Client(); closed = []; stop = asyncio.Event()
        async def close(): closed.append(True)
        event = fixture_event()
        async def builder(call, scoped):
            built = await build_agent(Choice(close), call=call, relay=scoped)
            if expired: event["expires_at"] = int(time.time() * 1000) - 1
            else: stop.set()
            return built
        if expired:
            assert await run_prepared_incoming(client, event, builder, stop=stop) is None
        else:
            with pytest.raises(asyncio.CancelledError):
                await run_prepared_incoming(client, event, builder, stop=stop)
        assert closed == [True] and not client.accepts
    asyncio.run(run())


def test_failure_after_acceptance_ends_call_and_closes():
    async def run():
        client = Client(); closed = []
        async def close(): closed.append(True)
        async def builder(call, scoped):
            built = await build_agent(Choice(close), call=call, relay=scoped)
            async def failed_run(*args, **kwargs): raise RuntimeError("backend construction failed")
            built.agent.run = failed_run
            return built
        assert await run_prepared_incoming(client, fixture_event(), builder) is None
        assert closed == [True] and client.ends == ["call-fixture"] and not client.declines
    asyncio.run(run())
