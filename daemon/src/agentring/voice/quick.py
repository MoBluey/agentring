"""A ready-made voice agent from a few options (what `agentring call --ai` and `serve --ai` use)."""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .agents import AgentsConfig, Consultations
from .providers import PROVIDERS, PreparedCall, VoiceChoice, VoiceConfigError
from .session import VoiceAgent
from .tools import McpClient, Tool, mcp_tools

DEFAULT_INSTRUCTIONS = (
    "You are the user's AI agent talking to them on the phone. Speak like a person on a call: short, "
    "natural sentences, one idea at a time, in the user's language. Never read out ids, links or paths. "
    "Never claim you did or checked something unless a tool told you so in this call."
)


MISSED_CALL = "Missed call: {reason}"


def missed_call_message(relay, call_id: str, reason: str, details: str | None = None) -> str:
    """M4 fallback: what the call was about, as a message the user can answer. Returns msg_id."""
    text = MISSED_CALL.format(reason=reason) + (f"\n\n{details}" if details else "")
    return relay.post_message(text[:4000], reply_to=call_id)["msg_id"]


def read_instructions(value: str | None) -> str:
    """Inline text, or @path to read it from a file."""
    if not value:
        return DEFAULT_INSTRUCTIONS
    if value.startswith("@"):
        return Path(value[1:]).expanduser().read_text(encoding="utf-8")
    return value


@dataclass
class BuiltAgent:
    agent: VoiceAgent
    mcp_clients: list[McpClient]
    consultations: Consultations | None = None
    prepared: PreparedCall | None = None

    async def aclose(self, *, wait_answers_s: float | None = 0) -> None:
        """Closes the MCP clients. Agents' answers still on their way get `wait_answers_s`
        (None = as long as they take) to arrive as messages; then they are stopped."""
        try:
            if self.consultations is not None:
                if wait_answers_s != 0:
                    await self.consultations.drain(wait_answers_s)
                await self.consultations.aclose()
            for c in self.mcp_clients:
                await c.aclose()
        finally:
            if self.prepared is not None and self.prepared.close is not None:
                close, self.prepared.close = self.prepared.close, None
                await close()


async def build_agent(
    voice_ai: VoiceChoice | str,
    *,
    instructions: str | None = None,
    mcp: list[str] | None = None,
    allow: list[str] | None = None,
    agents: AgentsConfig | None = None,
    model: str | None = None,
    voice: str | None = None,
    call: dict | None = None,
    relay: Any = None,
) -> BuiltAgent:
    """A voice AI (`resolve_voice(...)`, or a Gemini API key with `model`/`voice`) with the tools of
    the given MCP servers (only `allow` if given) and of agents.yaml (its MCP servers, and
    `consult_agent` for its agents). Close it when done."""
    if isinstance(voice_ai, str):
        gemini = PROVIDERS["gemini"]
        voice_ai = VoiceChoice(gemini, voice_ai, model or gemini.default_model, voice or gemini.default_voice)
    built = BuiltAgent(None, [])  # type: ignore[arg-type]
    tools: list[Tool] = []
    servers = [(url, allow, None) for url in mcp or []]
    servers += [(m.url, m.tools, m.headers) for m in (agents.mcp if agents else [])]
    try:
        if call and call.get("session_mode"):
            prepare = getattr(voice_ai, "prepare_call", None)
            if not callable(prepare):
                raise VoiceConfigError("Selected provider has no real session preparation hook")
            prepared = await prepare(call, relay=relay)
            if not isinstance(prepared, PreparedCall):
                raise VoiceConfigError("Provider must return PreparedCall")
            built.prepared = prepared
            binding = prepared.session
            if not callable(prepared.backend) or not isinstance(binding, dict) or binding.get("callee") != call.get("callee"):
                raise VoiceConfigError("Provider returned a mismatched call binding")
            session_id = binding.get("client_session_id")
            if not isinstance(session_id, str) or not session_id or session_id.startswith("pending:") or not binding.get("caller", {}).get("provider"):
                raise VoiceConfigError("Provider did not return a real session identity")
            if call["session_mode"] == "existing" and session_id != call["chat"]["client_session_id"]:
                raise VoiceConfigError("Existing call must attach to the selected session")
        for url, only, headers in servers:
            found, client = await mcp_tools(url, allow=only, headers=headers)
            tools += found
            built.mcp_clients.append(client)
        if agents and agents.agents:
            built.consultations = Consultations(agents.agents)
            tools.append(built.consultations.tool())
        text = read_instructions(instructions)
        built.agent = VoiceAgent(built.prepared.backend if built.prepared else voice_ai.factory(), text, tools)
        return built
    except BaseException:
        await asyncio.shield(built.aclose())
        raise
