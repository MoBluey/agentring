# Questions, Markdown and message receipts

Message `text` keeps the exact Markdown source in both directions, including
bold, inline code, fenced code and highlight markers. The relay does not turn
text into HTML or add a format flag. Text, file captions and question prompts
use the same 4,000-character limit and existing `created_at` timestamp.

## Ask a question

A question is an agent message with structured choices. Its `msg_id` is the
question ID. Bind the originating session with `open_chat`, then call the MCP
tool:

```json
{"name":"ask_question","arguments":{"text":"**Deploy this build?**","options":[{"id":"ship","label":"Ship it"},{"id":"hold","label":"Hold"}],"allow_free_text":true,"client_msg_id":"deploy-build-42"}}
```

The tool also accepts an explicit `chat_id`. Use a stable `client_msg_id` for
retries. The same key and content return the existing question; changing the
question under that key returns HTTP 409 `idempotency_conflict`.

CLI equivalent:

```sh
agentring ask-question '**Deploy this build?**' --option 'ship=Ship it' --option 'hold=Hold' --allow-free-text --client-msg-id deploy-build-42 --chat <chat_id> --json
agentring replies --chat <chat_id> --json
agentring acknowledge-replies <reply_msg_id> --chat <chat_id> --json
```

There must be two to eight options, with unique stable ASCII IDs containing
letters, digits, `_` or `-` (maximum 64 characters). Labels contain one to 200
characters. Single-choice is the only selection mode. MCP/CLI questions offer a free-written alternative by default; use
`allow_free_text: false` or CLI `--no-free-text` to disable it. Raw REST messages
default to disabled unless `allow_free_text` is explicitly true. The phone requires an explicit
submission; selecting a choice does not send it automatically.

## Wire format and answers

Agent `POST /v1/messages`:

```json
{"text":"Deploy?","chat_id":"chat_…","client_msg_id":"deploy-42","question":{"options":[{"id":"ship","label":"Ship it"},{"id":"hold","label":"Hold"}],"allow_free_text":true}}
```

A paired device answers through the same endpoint:

```json
{"text":"","chat_id":"chat_…","client_msg_id":"answer-deploy-42","answer":{"question_id":"msg_…","option_id":"ship"}}
```

Alternatively, `answer` can contain `free_text` instead of `option_id`. Exactly
one is required. The relay validates the original question, option and chat;
it derives human-readable message text from the chosen label or exact free
text, and sets `reply_to` to the question ID. An explicit conflicting `reply_to`
is rejected. Only agents originate cards; only paired devices submit answers.
Questions and answers require nonempty `client_msg_id` values.

The first answer is final, including across devices. Retrying the same accepted
answer/key returns it. Reusing that key for another answer returns HTTP 409
`idempotency_conflict`; a fresh answer to an already-answered question returns
HTTP 409 `question_answered`. A stale phone must refresh the card after that
response. Unanswered questions remain available; there is no separate expiry.

`Message` and `msg.new` include optional `question` or `answer` objects.
`get_replies` and CLI replies return `answer` alongside human-readable text and
the originating `chat_id`. They filter by chat before returning each reply once.
Fetching or polling does not mark replies read. Native host steering/injection
is handled by the host bridge, separately from acknowledgement. After the
originating agent has read the reply, call MCP
`acknowledge_replies(msg_ids, chat_id?)` or CLI `acknowledge-replies`. Every ID
must be a user reply in that chat; a mixed-chat batch is rejected before any
read changes. Explicit acknowledgement is idempotent.
A restored question includes `answered_by_msg_id` and `answered_at` after an
answer. The persisted `question.answered` event carries `chat_id`, `question_id`,
`answer_msg_id` and `answered_at`, allowing live and reconnect updates of the
original card. Older text-only messages remain valid.

## Truthful receipts

A sent message has a successful relay response. That proves acceptance only.
`delivered_at` and `read_at` start absent; neither is inferred from sending,
a push being queued, or a plain history GET.

After receiving/storing an opposite-party message, the recipient acknowledges
`POST /v1/messages/{msg_id}/delivered`. The relay sets `delivered_at` once and
emits persisted `msg.delivered` with `msg_id`, `by` and the event timestamp.
Both text and file messages use the same receipt fields.

The existing `/read` endpoint represents actual reading or consumption: the
phone's selected visible chat, or the originating agent explicitly acknowledging
a reply with `acknowledge_replies`. Reading also proves delivery. `/read` emits each
required receipt once, with `msg.delivered` before `msg.read`. Both endpoints
are idempotent, authenticate the recipient party and reject acknowledging your
own message. Tenant isolation remains unchanged.

The daemon acknowledges user messages only after its SQLite mirror stores them.
Failed acknowledgements remain pending and are retried during later catch-up
or reconnect. Merely mirroring another session's message cannot mark it read.
The daemon mirror's existing internal `delivered_at` is its once-per-fetch cursor;
`relay_delivered_at` records the new network receipt, keeping those meanings
separate.

## Migration and rollout

This change adds relay migration 6 after the already-delivered chat/files
migration 5. It adds nullable `question_json`, `answer_json`, `answer_to` and
`delivered_at` columns to messages, plus a unique index allowing one answer per
question. The daemon mirror adds nullable question/answer JSON and
`relay_delivered_at` columns. Existing text, files, calls, pairing and reply
consumption state are retained. No delivery timestamp is backfilled from a
send timestamp.

Migration6 requires separate production approval. Approval for migration 5 does
not cover it. Build and verify the fresh isolated relay/app first, then stop
writers and back up the complete relay data directory and daemon home before
an approved rollout. Rollback uses that backup and the matching prior code;
preserve post-rollout data separately before restoring it. Do not delete schema
version rows or drop columns as a rollback shortcut.

For event-driven host bridges, `GET /v1/messages?from=user&chat_id=...&unread=true`
filters `read_at IS NULL` before ordering and pagination. This avoids unread
replies being hidden behind old read history. False or omitted `unread` preserves
all-history behaviour. Peeking does not acknowledge anything; only explicit
`/read` / `acknowledge_replies` changes that state. No extra migration is needed
for this filter.

Explicit new/existing Claude/Codex call binding is documented in
[call-sessions.md](call-sessions.md).
