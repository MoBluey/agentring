# Session archive and search

`PATCH /v1/chats/{chat_id}` with `{"archived":true}` archives a chat;
`{"archived":false}` restores it. Agent credentials and paired devices have the
same chat access as before. Archive never deletes history, stops a provider
conversation, changes reply routing or marks messages read.

`Chat.archived_at` is the archive timestamp in milliseconds, omitted while active.
Changing archive state updates `updated_at` and emits the existing `chat.updated`
event. Repeating the same state changes neither timestamp nor event log. Agent
metadata upserts, call bindings and new messages preserve archive state. Their
normal `updated_at` changes do not change `archived_at`. Sync/reconnect receives
the full chat snapshot through the existing event log.

`GET /v1/chats?status=active|archived|all&provider=Codex` filters sessions. Omitted
status remains `all` for existing clients. Provider is an exact raw
`caller.provider` label ignoring case, independent of `callee`; clients can
derive dropdown options from the unfiltered chat list.

`GET /v1/chats/search?q=deploy&status=all&provider=Codex&limit=15` returns:

```json
{"results":[{"chat":{"chat_id":"chat_example"},"match":{"kind":"message","snippet":"Please deploy this version","msg_id":"msg_example","created_at":1794012345000}}]}
```

The example abbreviates the full `Chat` object. Query is a literal, case-insensitive
substring of session title, description, provider, model, project, session ID or
any message text in server history. `%`, `_`, backslashes and regex punctuation
have no wildcard meaning. Unicode case matching uses JavaScript Unicode
ignore-case semantics. Query length is 1–200 characters; whitespace-only queries
are rejected. Limit is 1–30 (default 15).

Results contain one entry per chat, ordered by chat `updated_at` descending then
chat ID. A metadata match takes precedence; otherwise the newest matching message
(by `created_at`, then message ID) supplies `msg_id`, `created_at` and the snippet.
Snippets preserve original text/case and contain at most 320 Unicode characters
around the match. They are plain text, never executable HTML. Use message IDs to
open the relevant position, including messages outside the cached/latest page.
Search has no receipt or read side effects.

Migration 7 adds only nullable `chats.archived_at`; existing chats remain active.
There is no new dependency or external indexing service. Database rollback must
use a consistent pre-migration backup and matching previously verified runtime.
Do not run this migration against a live database without separate approval.
