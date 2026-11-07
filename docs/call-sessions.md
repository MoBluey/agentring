# Explicit new and existing call sessions

Calls-screen “Call Claude” / “Call Codex” create a new provider conversation.
The Call button inside a Messages chat uses that exact existing session.
`callee` selects a configured host integration; it is separate from caller
provider/model/project metadata and from the voice backend name.

## Phone/relay contract

For a new conversation:

```json
{"callee":"codex","session_mode":"new","client_call_id":"stable-device-request-id"}
```

Do not send `chat_id`. The relay allocates a fresh pending chat with unknown
caller metadata. `client_call_id` is required and must be retained across network
retries. A retry returns the same ringing/active call; changing its selection
returns `409 idempotency_conflict`. Retrying a terminal call returns
`409 call_ended`, never creates another provider conversation.

For an existing Messages session:

```json
{"session_mode":"existing","chat_id":"chat_…"}
```

The chat must have a declared `callee` of `claude` or `codex`, registered by its
agent through `open_chat` / `POST /v1/chats`. Optional request `callee` must match.
Unknown or mismatched targets fail closed. The selected chat's
`client_session_id` must remain unchanged. Legacy calls omitting both selection
fields retain their existing default-provider behaviour.

An explicit incoming call cannot become active until the receiving agent binds
an actual prepared provider conversation:

```json
{"session":{"client_session_id":"actual-provider-conversation-id","callee":"codex","caller":{"provider":"openai"}}}
```

Send this to `POST /v1/calls/{id}/accept`. `caller.model` and `caller.project`
are optional verified metadata. Omit unknown values; global new calls do not
inherit a previous chat's project. Missing project displays as unknown. Existing
chat metadata is preserved unless the adapter supplies a verified update.

A new binding cannot reuse a provider conversation ID belonging to another chat.
Existing mode requires the original ID. Repeating an accepted binding is
idempotent; a different binding is rejected. `Call` / `call.invite` include
`callee` and `session_mode`; `call.accept` includes the resolved chat snapshot.
An unavailable host integration declines before agent media join with
`reason` / `end_reason` = `callee_unavailable`.

## Daemon/provider hook

Configure allowed targets explicitly:

```sh
agentring serve --ai --provider claude-unmute --call-provider claude=claude-unmute --call-provider codex=codex-unmute
agentring chats create --session <actual-session-id> --callee codex --provider openai --project <actual-project>
```

Explicit targets use their own provider configuration, rather than inheriting
the legacy provider's `--model` or `--voice` overrides.

The registered provider choice implements:

```python
from agentring.voice.providers import PreparedCall

async def prepare_call(self, call: dict, *, relay) -> PreparedCall:
    # Create/reuse through the real supported host API; do not invent an ID.
    return PreparedCall(
        session={"client_session_id": actual_id, "callee": call["callee"],
                 "caller": {"provider": actual_provider}},
        backend=lambda: BackendBoundToThisConversation(actual_id),
        close=release_call_resources,
    )
```

`call` is a fresh relay response, including the selected chat and mode. The
adapter must durably key real new-session creation by `call_id` so a retry or
listener restart cannot create duplicate provider threads. Existing mode must
attach to the exact selected ID through a supported host route. Never resume an
active coding thread in a separate process, substitute a different conversation,
or silently fall back to another provider.

The daemon calls this hook before acceptance and uses its returned backend
factory for that call only. Missing hooks, mismatched IDs or preparation failures
fail closed. The `close` callback runs after a returned preparation on normal
completion, failed acceptance, timeout or cancellation. A hook cancelled before
returning owns cleanup of resources acquired internally. `close` releases call
resources; it must retain the durable provider conversation for future calls.
Acceptance retries reuse the same prepared binding and never invoke preparation
again within that call attempt. Expired explicit acceptance cannot mutate the pending
chat. The listener rechecks the stop signal and deadline after preparation; a
failure after relay acceptance ends the active call instead of leaving it ringing
or attempting to decline an active call.

This repository supplies routing and binding checks. Real host adapters and
actual phone/audio acceptance remain separate requirements; an empty or mock
factory does not establish working Claude/Codex calls.

## Pending migration

Migration 6 also adds nullable `calls.callee`, `calls.session_mode`,
`calls.client_call_id` and `chats.callee`, with a per-device call idempotency index.
This remains part of the explicit pending migration approval. No live database
or daemon is changed by building this code.
