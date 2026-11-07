"""Answering calls the user makes to the agent (M4, "Call agent" in the app)."""

from __future__ import annotations

import logging
import time
from typing import Any

from ..relay_client import RelayError

log = logging.getLogger("agentring.voice")


def is_incoming_call(event: dict[str, Any], now: float | None = None) -> bool:
    """A `call.invite` from a phone to the agent that still rings (replayed old invites don't count)."""
    if event.get("type") != "call.invite" or event.get("direction") != "user_to_agent":
        return False
    return (event.get("expires_at") or 0) / 1000 > (time.time() if now is None else now)


def accept_incoming(client: Any, event: dict[str, Any]) -> tuple[Any, dict[str, Any]] | None:
    """Accepts an incoming call. Returns what VoiceAgent.run needs, or None if it isn't
    one (or someone else took it first). `client` is a RelayClient."""
    from .session import CallInfo

    if not is_incoming_call(event):
        return None
    try:
        res = client.accept_call(event["call_id"])
    except RelayError as e:
        log.warning("could not accept call %s: %s", event["call_id"], e)
        return None
    call = res.get("call") or {}
    return CallInfo(event["call_id"], event.get("reason") or call.get("reason") or "", "user_to_agent"), res["join"]


async def run_prepared_incoming(client: Any, event: dict[str, Any], build: Any, *, stop: Any = None) -> Any:
    """Prepare a real per-call host session, bind it before accepting, then always close it.

    A hook owns cleanup if cancelled before returning PreparedCall. Once returned,
    BuiltAgent.aclose owns its close callback, including failed acceptance.
    """
    import asyncio
    from .session import CallInfo

    built = None
    preparing = None
    accepted = None
    stopped = asyncio.create_task(stop.wait()) if stop is not None else None
    try:
        call = await asyncio.to_thread(client.get_call, event["call_id"])
        if not is_incoming_call(event) or call.get("state") not in ("ringing", "active"):
            return None
        scoped = client.with_chat(call["chat_id"])
        preparing = asyncio.create_task(build(call, scoped))
        waiters = {preparing, *([stopped] if stopped else [])}
        done, _ = await asyncio.wait(waiters, timeout=max(0, event["expires_at"] / 1000 - time.time()),
                                     return_when=asyncio.FIRST_COMPLETED)
        if preparing not in done:
            if stopped is not None and stopped in done:
                raise asyncio.CancelledError
            raise TimeoutError("Host preparation expired before the call was answered")
        built = preparing.result()
        if built.prepared is None:
            raise ValueError("Explicit calls require a prepared real session")
        if stop is not None and stop.is_set():
            raise asyncio.CancelledError
        if time.time() * 1000 > call.get("expires_at", event["expires_at"]):
            raise TimeoutError("Call expired before acceptance")
        for attempt in range(2):
            try:
                accepted = await asyncio.to_thread(scoped.accept_call, call["call_id"], session=built.prepared.session)
                break
            except RelayError as e:
                if attempt or e.status not in (0, 502, 503, 504):
                    raise
                # Retry the same binding, never prepare another provider conversation.

        info = CallInfo(call["call_id"], call.get("reason", ""), "user_to_agent")
        return await built.agent.run(info, accepted["join"], relay=scoped, stop=stop)
    except asyncio.CancelledError:
        raise
    except Exception as e:
        log.warning("incoming call %s unavailable: %s", event["call_id"], e)
        try:
            if accepted is not None:
                await asyncio.to_thread(client.end_call, event["call_id"])
            else:
                await asyncio.to_thread(client.decline_call, event["call_id"], reason="callee_unavailable")
        except Exception:
            pass  # Already cancelled/expired: never reopen it or fall back to another provider.
        return None
    finally:
        if stopped is not None:
            stopped.cancel()
        if preparing is not None:
            if not preparing.done():
                preparing.cancel()
            outcome = await asyncio.gather(preparing, return_exceptions=True)
            if built is None and outcome and not isinstance(outcome[0], BaseException):
                built = outcome[0]
        if built is not None:
            closing = asyncio.create_task(built.aclose())
            try:
                await asyncio.shield(closing)
            except asyncio.CancelledError:
                await closing
                raise
