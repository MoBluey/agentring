# Agent session chats and files

Each agent session has one chat. Its caller metadata describes the coding/chat
agent: provider, model and project. It does not select or describe the voice AI
that speaks on a call. Supply verified values; omitted values display as unknown.
A stable session ID resumes the same chat after reconnecting. Updating the model
for that session updates its chat identity; existing calls retain the caller
snapshot from when they started.

## CLI

Use the existing `agentring` executable, including installed provider wrappers.
Create a chat once per agent session, then use its returned `chat_id`:

```sh
agentring chats create --session <stable-session-id> --provider openai --model <verified-model> --project <project> --json
agentring chats list --json
agentring msg "Build finished" --chat <chat_id>
agentring send-file /absolute/path/report.pdf --text "Build report" --chat <chat_id> --json
agentring replies --chat <chat_id> --json
agentring download-file <attachment_id> --destination /absolute/path/new-report.pdf --json
agentring call "Review needed" --chat <chat_id> --ai --provider claude-unmute
```

`--provider` on `chats create` is the caller's provider. `--provider` on `call`
selects the installed voice backend, as before. The example voice provider is an
optional external wrapper; the repository's other voice providers still work.
`AGENTRING_CHAT_ID` supplies the default for `--chat`. With no explicit session ID,
`chats create` accepts `CODEX_THREAD_ID`, `CODEX_SESSION_ID`, or
`AGENTRING_SESSION_ID`. It does not scan session transcripts or guess a model or
project. Explicit `--session` takes priority.

Downloads verify the SHA-256 digest and size before publishing a private file.
They never overwrite an existing destination. If omitted, the destination is
`~/Downloads/<attachment_id>-<original-name>`; passing an existing directory uses
the original filename within it.

## MCP

Start the usual local stdio MCP server with `agentring mcp`. At the beginning of
an agent session call:

```json
{"name":"open_chat","arguments":{"session_id":"stable-session-id","provider":"openai","model":"verified-model","project":"project-name"}}
```

Then `send_message`, `send_file`, `get_replies`, and `call` use that connection's
active chat. Each also accepts an explicit `chat_id` override. `get_replies`
filters by chat before returning each reply once. Fetching does not mark replies
read; call `acknowledge_replies` after the originating agent acknowledges them.
It never consumes another chat's replies. Received replies include `chat_id` and full
`attachments` metadata, even when the message has no text.

Local file tools:

```json
{"name":"send_file","arguments":{"path":"/absolute/path/photo.png","text":"Screenshot"}}
{"name":"download_file","arguments":{"attachment_id":"att_…","destination":"/absolute/path/new-photo.png"}}
```

`agentring mcp-http` exposes `open_chat`, but binding belongs to the authenticated
MCP session (`Mcp-Session-Id`), never a tenant-wide mutable chat. Concurrent
sessions can bind separate chats. Stateless HTTP callers must supply `chat_id`
on subsequent calls. Remote HTTP MCP does not advertise or execute `send_file`
or `download_file`: it cannot access arbitrary server filesystem paths. Remote
clients can upload/download through the authenticated relay REST API and send
uploaded `attachment_ids` with `send_message`.

## REST contract

`protocol/relay.openapi.yaml` is the authoritative contract.

- Agent-only `POST /v1/chats` upserts by `client_session_id`. Optional `caller`
  fields, `title` and `description` update explicit metadata for that session.
  `GET /v1/chats` returns `{chats: [...]}` and `GET /v1/chats/{chat_id}` returns one.
  Persisted `chat.updated` events synchronise session identity over reconnects.
- `POST /v1/attachments?name=report.pdf&media_type=application%2Fpdf&chat_id=...`
  streams raw `application/octet-stream`. Both agent and device credentials work.
  The result has `attachment_id`, `chat_id`, `name`, `media_type`, `size_bytes`,
  `sha256` and `created_at`.
- `POST /v1/messages` takes `text`, `chat_id`, and up to eight unique
  `attachment_ids`. Empty text is valid only with attachments. Files must belong
  to the uploader and the same chat, and may be posted once. Idempotency keys are
  scoped to the chat and sending principal. A reply to an existing message/call
  must stay in its chat.
- `GET /v1/attachments/{attachment_id}` returns metadata;
  `GET /v1/attachments/{attachment_id}/content` streams original bytes.
  Unposted uploads are private to their creating principal. Once posted, access
  follows the relay's existing shared conversation permissions. Revoked devices
  cannot upload or download. Each hub tenant has its own DB and file directory.
- Messages and `msg.new` events carry `chat_id` and attachment metadata.
  `GET /v1/messages?chat_id=...` and `GET /v1/calls?chat_id=...` filter history.
  Calls and `call.invite` include `chat_id` and a caller chat snapshot.
- Omitting `chat_id` preserves the stable `chat_default` legacy conversation.
  Missing attachment arrays on old events mean an empty array. Devices may post
  to a chat but cannot change its agent identity.

A file is capped at **100 MiB**, and each relay/tenant stores at most **2 GiB**.
Quota reservations include uploads in flight. Oversized files fail with HTTP 413;
full storage fails with HTTP 507. No successful message is created on upload
failure. Uploaded files not posted within 24 hours expire. Cleanup runs at startup
and on file operations. Posted files remain available until the operator removes
the relay's data; there is no automatic deletion of conversation attachments.

File bytes live in private `DATA_DIR/attachments/`, outside SQLite. Generated IDs
alone form storage paths; names cannot contain paths or control characters.
Uploads write exclusive private temporary files, check length and digest, then
publish atomically. Interrupted and crash-left partial files are cleaned up.
Downloads use no-follow file opens and force attachment content disposition,
`nosniff` and `private, no-store`. Events and pushes contain metadata, never file
bytes or credentials. Oversized push metadata is reduced and clients fetch the
full message/call through authenticated REST.

## Deployment and rollback

The implementation adds schema migration 5. It creates `chats` and `attachments`,
adds `messages.chat_id`, `calls.chat_id` and `calls.chat`, and scopes the existing
message idempotency index by chat and device. Existing messages/calls belong to
`chat_default`; existing devices, credentials, event history and call state stay
in place. The daemon mirror adds `chat_id` and attachment metadata columns and a
chat mirror table. There is no destructive data rewrite or automatic down migration.

Before an approved live rollout, stop the relay and daemon/MCP processes and
back up the **entire** relay data directory and daemon home, including SQLite
WAL/SHM files if present. Deploy the verified relay build and point the existing
Python provider wrapper at the fork's daemon source (editable installation can
retain its optional provider registration). Restart relay, daemon and MCP
processes, then verify the existing paired device plus a new session's text,
file, reply and call workflow. Do not re-pair or reset the existing official app
as a deployment shortcut.

For a separately registered Android fork package, set
`AGENTRING_ANDROID_PACKAGE=dev.agentring.app.mo` together with its downloaded
`FCM_GOOGLE_SERVICES_FILE`. The default remains `dev.agentring.app`. Existing
FCM service-account credentials can be reused when both apps belong to the same
Firebase project; no new service-account key is needed.

Build checks from the checkout:

```sh
npm --prefix /absolute/path/agentring/relay ci
npm --prefix /absolute/path/agentring/relay run typecheck
npm --prefix /absolute/path/agentring/relay test
npm --prefix /absolute/path/agentring/relay run build
uv run --directory /absolute/path/agentring/daemon --extra voice pytest -q
```

Rollback requires stopping the new processes and restoring the pre-rollout data
backup alongside the old code. Preserve the current data directory separately
first: restoring the backup omits chats, messages and files created after it.
Do not delete migration rows or drop columns to force old code onto a changed DB.

Structured choices, raw Markdown and delivery/read semantics are described in
[questions.md](questions.md). That addition has its own migration and rollout approval.
