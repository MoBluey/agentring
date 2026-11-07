"""Route pending phone text only to explicitly selected, owning provider hooks."""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from .mcp_server import AgentringTools
from .sync import acknowledge_receipts, apply_event

log = logging.getLogger("agentring.replies")


async def run_reply_router(client: Any, store: Any, choices: dict[str, Any],
                           stop: asyncio.Event, *, poll_s: float = 2) -> None:
    """Retry unread input on startup/reconnect, serially within each chat.

    An optional choice.reply_to_chat(chat, messages, *, tools) validates durable
    host ownership, journals provider responses across retries, posts its reply,
    and explicitly acknowledges only consumed inputs. The router never marks
    input read. An unowned/busy/failed hook leaves that input pending.
    """
    tasks: dict[str, asyncio.Task] = {}

    async def dispatch(chat: dict, hook: Any) -> None:
        try:
            scoped = client.with_chat(chat["chat_id"])
            messages = await asyncio.to_thread(scoped.list_unread_replies, chat["chat_id"])
            if stop.is_set() or not messages:
                return
            for message in messages:
                apply_event(store, {**message, "type": "msg.new", "ts": message["created_at"]})
            await asyncio.to_thread(acknowledge_receipts, scoped, store)
            if not stop.is_set():
                tools = AgentringTools(scoped, store, chat_binding={"chat_id": chat["chat_id"]})
                await hook(chat, messages, tools=tools)
        except asyncio.CancelledError:
            raise
        except Exception as error:
            log.info("chat %s replies remain pending: %s", chat["chat_id"], error)

    try:
        while not stop.is_set():
            try:
                chats = await asyncio.to_thread(client.list_chats)
                for chat in chats:
                    if stop.is_set():
                        break
                    hook = getattr(choices.get(chat.get("callee")), "reply_to_chat", None)
                    active = tasks.get(chat["chat_id"])
                    if callable(hook) and (active is None or active.done()):
                        tasks[chat["chat_id"]] = asyncio.create_task(dispatch(chat, hook))
            except Exception as error:
                log.info("cannot refresh pending reply chats: %s", error)
            try:
                await asyncio.wait_for(stop.wait(), timeout=poll_s)
            except asyncio.TimeoutError:
                pass
    finally:
        for task in tasks.values():
            task.cancel()
        await asyncio.gather(*tasks.values(), return_exceptions=True)
