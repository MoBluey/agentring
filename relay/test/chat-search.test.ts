import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { expect, it } from "vitest";
import { AGENT, pairDevice, setup } from "./helpers.js";
import { bindCallChat } from "../src/routes/chats.js";

it("archives idempotently without reopening on metadata, messages or call binding", async () => {
  const directory = mkdtempSync(join(tmpdir(), "chat-archive-"));
  let b = await setup({ dataDir: directory });
  try {
    const device = await pairDevice(b);
    const chat = (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT,
      payload: { client_session_id: "durable-chat", callee: "codex", title: "Useful session", caller: { provider: "Codex", model: "verified" } } })).json();
    const patch = (archived: boolean) => b.app.inject({ method: "PATCH", url: `/v1/chats/${chat.chat_id}`, headers: device.headers, payload: { archived } });
    const first = await patch(true);
    expect(first.statusCode).toBe(200);
    const archived = first.json();
    expect(archived.archived_at).toBeGreaterThan(0);
    expect(archived.updated_at).toBe(archived.archived_at);
    const before = (await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events.length;
    expect((await patch(true)).json()).toEqual(archived);
    expect((await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events.length).toBe(before);
    await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT, payload: { client_session_id: "durable-chat", title: "New title" } });
    const message = (await b.app.inject({ method: "POST", url: "/v1/messages", headers: device.headers, payload: { text: "Still routed", chat_id: chat.chat_id } })).json();
    expect(message.chat_id).toBe(chat.chat_id);
    expect(bindCallChat(b.ctx, chat.chat_id, { client_session_id: "durable-chat", callee: "codex", caller: { provider: "Codex" } }).archived_at).toBe(archived.archived_at);
    expect((await b.app.inject({ url: "/v1/chats", headers: device.headers })).json().chats).toHaveLength(2);
    expect((await b.app.inject({ url: "/v1/chats?status=active", headers: AGENT })).json().chats.map((c: { chat_id: string }) => c.chat_id)).toEqual(["chat_default"]);
    expect((await b.app.inject({ url: "/v1/chats?status=archived&provider=CODEX", headers: device.headers })).json().chats[0].chat_id).toBe(chat.chat_id);
    await b.app.close(); b = await setup({ dataDir: directory });
    expect((await b.app.inject({ url: `/v1/chats/${chat.chat_id}`, headers: AGENT })).json().archived_at).toBe(archived.archived_at);
    expect((await patch(false)).json().archived_at).toBeUndefined();
    expect((await b.app.inject({ url: `/v1/messages/${message.msg_id}`, headers: AGENT })).json().text).toBe("Still routed");
    const events = (await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events;
    expect(events.filter((e: { type: string; chat?: { chat_id: string; archived_at?: number } }) => e.type === "chat.updated" && e.chat?.chat_id === chat.chat_id).at(-1).chat.archived_at).toBeUndefined();
    expect((await b.app.inject({ method: "PATCH", url: `/v1/chats/${chat.chat_id}`, payload: { archived: true } })).statusCode).toBe(401);
    expect((await b.app.inject({ method: "PATCH", url: "/v1/chats/unknown", headers: AGENT, payload: { archived: true } })).statusCode).toBe(404);
    await b.app.inject({ method: "DELETE", url: `/v1/devices/${device.device_id}`, headers: AGENT });
    expect((await patch(true)).statusCode).toBe(401);
    expect((await b.app.inject({ url: "/v1/chats/search?q=routed", headers: device.headers })).statusCode).toBe(401);
  } finally { await b.app.close(); rmSync(directory, { recursive: true, force: true }); }
});

it("searches full server history literally with Unicode snippets, filters and bounded results", async () => {
  const b = await setup();
  try {
    const create = async (id: string, title: string, provider: string) => (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT,
      payload: { client_session_id: id, title, caller: { provider, model: "model-Q", project: "project-R" } } })).json();
    const a = await create("first-session", "Metadata Café 東京", "Códex");
    const other = await create("second-session", "Different session", "Claude Code");
    const send = async (text: string, chat_id = a.chat_id) => (await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text, chat_id } })).json();
    const literal = "%_\\ [literal].*+?^${}()|";
    const oldest = await send("Old server-only " + literal + " end");
    for (let index = 0; index < 505; index++) await send(`Recent filler ${index}`);
    const newest = await send("x".repeat(1800) + " UNICODE CAFÉ 東京 " + "y".repeat(1800));
    await send("Unicode café 東京 older", other.chat_id);
    const search = async (q: string, extras = "") => b.app.inject({ url: "/v1/chats/search?q=" + encodeURIComponent(q) + extras, headers: AGENT });
    const oldMatch = (await search(literal)).json().results;
    expect(oldMatch).toHaveLength(1);
    expect(oldMatch[0].match.msg_id).toBe(oldest.msg_id);
    expect(oldMatch[0].match.snippet).toContain(literal);
    const result = (await search("unicode café 東京", "&provider=CÓDEX")).json().results[0];
    expect(result.match.kind).toBe("message");
    expect(result.match.msg_id).toBe(newest.msg_id);
    expect(result.match.created_at).toBe(newest.created_at);
    expect(result.match.snippet).toContain("UNICODE CAFÉ 東京");
    expect(Array.from(result.match.snippet)).toHaveLength(320);
    expect((await search("café 東京", "&limit=1")).json().results).toHaveLength(1);
    const metadata = (await search("café 東京", "&provider=códex")).json().results[0];
    expect(metadata.match.kind).toBe("metadata");
    expect(metadata.match.snippet).toContain("Café 東京");
    expect(metadata.match.msg_id).toBeUndefined();
    expect((await search("project-R")).json().results).toHaveLength(2);
    expect((await search("first-session")).json().results[0].chat.chat_id).toBe(a.chat_id);
    expect((await search("first-session", "&provider=Code")).json().results).toEqual([]);
    await b.app.inject({ method: "PATCH", url: `/v1/chats/${a.chat_id}`, headers: AGENT, payload: { archived: true } });
    expect((await search(literal, "&status=active")).json().results).toEqual([]);
    expect((await search(literal, "&status=archived&provider=CÓDEX")).json().results[0].chat.chat_id).toBe(a.chat_id);
    const before = (await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events.length;
    await search(literal);
    expect((await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events.length).toBe(before);
    const untouched = (await b.app.inject({ url: `/v1/messages/${oldest.msg_id}`, headers: AGENT })).json();
    expect(untouched.read_at).toBeUndefined(); expect(untouched.delivered_at).toBeUndefined();
    for (const query of ["", " ", "a".repeat(201)]) expect((await search(query)).statusCode).toBe(400);
    for (const extra of ["&limit=0", "&limit=31", "&status=invalid"]) expect((await search("a", extra)).statusCode).toBe(400);
    expect((await b.app.inject({ url: "/v1/chats/search?q=a" })).statusCode).toBe(401);
  } finally { await b.app.close(); }
});
