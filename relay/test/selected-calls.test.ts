import { once } from "node:events";
import WebSocket from "ws";
import { expect, it, vi } from "vitest";
import { AGENT, pairDevice, setup } from "./helpers.js";

it("creates a distinct pending conversation and binds a real session exactly once", async () => {
  const b = await setup();
  await b.app.listen({ host: "127.0.0.1", port: 0 });
  const address = b.app.server.address() as { port: number };
  const agent = new WebSocket(`ws://127.0.0.1:${address.port}/v1/ws`, { headers: AGENT });
  await once(agent, "open");
  try {
    const device = await pairDevice(b);
    const request = { callee: "codex", session_mode: "new", client_call_id: "stable-call" };
    const create = (body = request) => b.app.inject({ method: "POST", url: "/v1/calls", headers: device.headers, payload: body });
    const created = await create();
    expect(created.statusCode).toBe(201);
    const call = created.json().call;
    expect(call.chat_id).not.toBe("chat_default");
    expect(call.chat.client_session_id).toBe(`pending:${call.call_id}`);
    expect(call.chat.caller).toEqual({});
    expect(call.chat.callee).toBe("codex");
    expect((await create()).json().call.call_id).toBe(call.call_id);
    expect((await create({ ...request, callee: "claude" })).statusCode).toBe(409);
    expect((await b.app.inject({ method: "POST", url: `/v1/calls/${call.call_id}/accept`, headers: AGENT })).statusCode).toBe(400);
    const session = { client_session_id: "genuine-provider-fixture-session", callee: "codex", caller: { provider: "openai", model: "verified-fixture" } };
    const accept = () => b.app.inject({ method: "POST", url: `/v1/calls/${call.call_id}/accept`, headers: AGENT, payload: { session } });
    const accepted = await accept();
    expect(accepted.statusCode).toBe(200);
    expect(accepted.json().call.chat.client_session_id).toBe(session.client_session_id);
    expect(accepted.json().call.chat.caller).toEqual(session.caller);
    expect(accepted.json().call.chat.title).toContain("Unknown project");
    expect((await accept()).statusCode).toBe(200);
    expect((await create()).json().call.chat.client_session_id).toBe(session.client_session_id);
    await b.app.inject({ method: "POST", url: `/v1/calls/${call.call_id}/end`, headers: AGENT });
    expect((await create()).json().error.code).toBe("call_ended");
    const again = await create({ ...request, client_call_id: "another-call" });
    expect(again.statusCode).toBe(201);
    expect(again.json().call.chat_id).not.toBe(call.chat_id);
    expect((await b.app.inject({ method: "POST", url: `/v1/calls/${again.json().call.call_id}/accept`, headers: AGENT, payload: { session } })).statusCode).toBe(409);
    await b.app.inject({ method: "POST", url: `/v1/calls/${again.json().call.call_id}/decline`, headers: AGENT, payload: { reason: "callee_unavailable" } });
    expect((await b.app.inject({ url: `/v1/calls/${again.json().call.call_id}`, headers: AGENT })).json().end_reason).toBe("callee_unavailable");
  } finally { agent.close(); await b.app.close(); }
});

it("uses only an existing chat's declared target and exact provider session", async () => {
  const b = await setup();
  await b.app.listen({ host: "127.0.0.1", port: 0 });
  const agent = new WebSocket(`ws://127.0.0.1:${(b.app.server.address() as { port: number }).port}/v1/ws`, { headers: AGENT });
  await once(agent, "open");
  try {
    const device = await pairDevice(b);
    const chat = (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT, payload: {
      client_session_id: "existing-real-fixture", callee: "claude", caller: { provider: "anthropic", model: "verified", project: "actual-project" },
    } })).json();
    const create = (payload: object) => b.app.inject({ method: "POST", url: "/v1/calls", headers: device.headers, payload });
    expect((await create({ session_mode: "existing", chat_id: chat.chat_id, callee: "codex" })).statusCode).toBe(409);
    expect((await create({ session_mode: "existing", chat_id: "chat_default" })).statusCode).toBe(409);
    expect((await create({ session_mode: "new", callee: "claude", chat_id: chat.chat_id, client_call_id: "bad" })).statusCode).toBe(400);
    const call = (await create({ session_mode: "existing", chat_id: chat.chat_id })).json().call;
    expect(call.callee).toBe("claude");
    const accept = (session: object) => b.app.inject({ method: "POST", url: `/v1/calls/${call.call_id}/accept`, headers: AGENT, payload: { session } });
    expect((await accept({ callee: "claude", client_session_id: "different-session", caller: { provider: "anthropic" } })).statusCode).toBe(409);
    const accepted = await accept({ callee: "claude", client_session_id: chat.client_session_id, caller: { provider: "anthropic" } });
    expect(accepted.statusCode).toBe(200);
    expect(accepted.json().call.chat.caller.project).toBe("actual-project");
    expect(accepted.json().call.chat.caller.model).toBe("verified");
    const events = (await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events;
    expect(events.find((event: { type: string }) => event.type === "call.accept").chat.client_session_id).toBe(chat.client_session_id);
  } finally { agent.close(); await b.app.close(); }
});

it("filters unread before pagination so old read history cannot starve host replies", async () => {
  const b = await setup();
  try {
    const device = await pairDevice(b);
    const chat = (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT, payload: { client_session_id: "unread-chat" } })).json();
    for (let index = 0; index < 25; index++) {
      const message = (await b.app.inject({ method: "POST", url: "/v1/messages", headers: device.headers, payload: { text: `history-${index}`, chat_id: chat.chat_id } })).json();
      await b.app.inject({ method: "POST", url: `/v1/messages/${message.msg_id}/read`, headers: AGENT });
    }
    const pending = (await b.app.inject({ method: "POST", url: "/v1/messages", headers: device.headers, payload: { text: "pending", chat_id: chat.chat_id } })).json();
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: device.headers, payload: { text: "other chat" } });
    const page = (await b.app.inject({ url: `/v1/messages?from=user&chat_id=${chat.chat_id}&unread=true&limit=1`, headers: AGENT })).json();
    expect(page.messages.map((message: { msg_id: string }) => message.msg_id)).toEqual([pending.msg_id]);
    expect(page.has_more).toBe(false);
    expect(page.messages[0].read_at).toBeUndefined();
    expect((await b.app.inject({ url: `/v1/messages?from=user&chat_id=${chat.chat_id}&unread=false&limit=1`, headers: AGENT })).json().messages[0].text).toBe("history-0");
  } finally { await b.app.close(); }
});


it("rejects expired explicit acceptance before mutating its pending chat", async () => {
  const b = await setup();
  await b.app.listen({ host: "127.0.0.1", port: 0 });
  const agent = new WebSocket(`ws://127.0.0.1:${(b.app.server.address() as { port: number }).port}/v1/ws`, { headers: AGENT });
  await once(agent, "open");
  try {
    const device = await pairDevice(b);
    const call = (await b.app.inject({ method: "POST", url: "/v1/calls", headers: device.headers,
      payload: { callee: "codex", session_mode: "new", client_call_id: "expired-call" } })).json().call;
    const before = (await b.app.inject({ url: `/v1/chats/${call.chat_id}`, headers: AGENT })).json();
    const clock = vi.spyOn(Date, "now").mockReturnValue(call.created_at + 30_001);
    try {
      const accepted = await b.app.inject({ method: "POST", url: `/v1/calls/${call.call_id}/accept`, headers: AGENT,
        payload: { session: { callee: "codex", client_session_id: "must-not-bind", caller: { provider: "openai" } } } });
      expect(accepted.statusCode, accepted.body).toBe(409);
      expect(accepted.json().error.code).toBe("call_ended");
      expect((await b.app.inject({ url: `/v1/chats/${call.chat_id}`, headers: AGENT })).json()).toEqual(before);
      const after = (await b.app.inject({ url: `/v1/calls/${call.call_id}`, headers: AGENT })).json();
      expect(after.state).toBe("ringing");
      expect(after.chat).toEqual(call.chat);
    } finally { clock.mockRestore(); }
  } finally { agent.close(); await b.app.close(); }
});
