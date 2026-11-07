import { mkdtempSync, writeFileSync, readdirSync, symlinkSync, unlinkSync, statSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createHash } from "node:crypto";
import { expect, it } from "vitest";
import { AGENT, pairDevice, setup } from "./helpers.js";
import { buildPayload } from "../src/push/dispatcher.js";

it("persists uploaded bytes, chat identity and pending cleanup across relay restarts", async () => {
  const dir = mkdtempSync(join(tmpdir(), "media-relay-"));
  let b = await setup({ dataDir: dir });
  try {
    const chat = (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT,
      payload: { client_session_id: "stable-session", caller: { provider: "openai", model: "coding-model", project: "repo" } } })).json();
    const upload = await b.app.inject({ method: "POST", url: `/v1/attachments?name=photo.png&media_type=image%2Fpng&chat_id=${chat.chat_id}`,
      headers: { ...AGENT, "content-type": "application/octet-stream" }, payload: Buffer.from([1, 2, 3, 0, 255]) });
    expect(upload.statusCode).toBe(201);
    const attachment = upload.json();
    const sent = await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT,
      payload: { text: "", attachment_ids: [attachment.attachment_id], chat_id: chat.chat_id } });
    expect(sent.statusCode).toBe(201);
    const device = await pairDevice(b);
    const pending = join(dir, "attachments", "att_00000000000000000000000000.part");
    writeFileSync(pending, "interrupted");
    expect(statSync(join(dir, "attachments")).mode & 0o777).toBe(0o700);
    expect(statSync(join(dir, "attachments", attachment.attachment_id)).mode & 0o777).toBe(0o600);
    await b.app.close();
    b = await setup({ dataDir: dir });
    expect(readdirSync(join(dir, "attachments"))).not.toContain(pending.split("/").at(-1));
    const content = await b.app.inject({ url: `/v1/attachments/${attachment.attachment_id}/content`, headers: device.headers });
    expect(content.rawPayload).toEqual(Buffer.from([1, 2, 3, 0, 255]));
    expect(content.headers["x-content-type-options"]).toBe("nosniff");
    expect(content.headers["content-type"]).toBe("application/octet-stream");
    expect(content.headers["content-disposition"]).toContain("attachment;");
    expect(content.headers["cache-control"]).toBe("private, no-store");
    expect(createHash("sha256").update(content.rawPayload).digest("hex")).toBe(attachment.sha256);
    const resumed = (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT,
      payload: { client_session_id: "stable-session", caller: { model: "switched-model" } } })).json();
    expect(resumed.chat_id).toBe(chat.chat_id);
    expect(resumed.caller).toEqual({ provider: "openai", model: "switched-model", project: "repo" });
    expect((await b.app.inject({ url: `/v1/messages?chat_id=${chat.chat_id}`, headers: device.headers })).json().messages[0].attachments).toEqual([attachment]);
    // A replaced blob may never turn into an arbitrary server file read.
    unlinkSync(join(dir, "attachments", attachment.attachment_id));
    symlinkSync("/etc/hosts", join(dir, "attachments", attachment.attachment_id));
    expect((await b.app.inject({ url: `/v1/attachments/${attachment.attachment_id}/content`, headers: AGENT })).statusCode).toBe(404);
  } finally { await b.app.close(); rmSync(dir, { recursive: true, force: true }); }
});

it("snapshots the coding caller in calls, independent of subsequent model changes", async () => {
  const b = await setup();
  try {
    await pairDevice(b);
    const chat = (await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT,
      payload: { client_session_id: "call-session", caller: { provider: "local", model: "coding-model", project: "project-a" } } })).json();
    const called = await b.app.inject({ method: "POST", url: "/v1/calls", headers: AGENT, payload: { reason: "Review", chat_id: chat.chat_id } });
    expect(called.statusCode).toBe(201);
    const call = called.json().call;
    expect(call.chat_id).toBe(chat.chat_id);
    expect(call.chat.caller).toEqual(chat.caller);
    await b.app.inject({ method: "POST", url: "/v1/chats", headers: AGENT, payload: { client_session_id: "call-session", caller: { model: "later-model" } } });
    const got = (await b.app.inject({ url: `/v1/calls/${call.call_id}`, headers: AGENT })).json();
    expect(got.chat.caller.model).toBe("coding-model");
    const events = (await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events;
    expect(events.find((e: { type: string }) => e.type === "call.invite").chat.caller.model).toBe("coding-model");
    expect(events.some((e: { type: string }) => e.type === "chat.updated")).toBe(true);
  } finally { await b.app.close(); }
});

it("expires abandoned uploads and enforces the total storage bound", async () => {
  const dir = mkdtempSync(join(tmpdir(), "media-expiry-"));
  const b = await setup({ dataDir: dir });
  try {
    const upload = (await b.app.inject({ method: "POST", url: "/v1/attachments?name=old.txt", headers: { ...AGENT, "content-type": "application/octet-stream" }, payload: "old" })).json();
    b.ctx.db.prepare("UPDATE attachments SET created_at = 0 WHERE attachment_id = ?").run(upload.attachment_id);
    expect((await b.app.inject({ url: `/v1/attachments/${upload.attachment_id}`, headers: AGENT })).statusCode).toBe(404);
    expect(readdirSync(join(dir, "attachments"))).not.toContain(upload.attachment_id);
    // Supporting quota invariant without allocating 2 GiB on every suite run.
    b.ctx.db.prepare("INSERT INTO attachments VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
      .run("att_00000000000000000000000001", "full", "application/octet-stream", 2 * 1024 ** 3, "0".repeat(64), Date.now(), "agent", "chat_default", null);
    const rejected = await b.app.inject({ method: "POST", url: "/v1/attachments?name=over.txt", headers: { ...AGENT, "content-type": "application/octet-stream" }, payload: "x" });
    expect(rejected.statusCode).toBe(507);
    expect(readdirSync(join(dir, "attachments"))).toEqual([]);
  } finally { await b.app.close(); rmSync(dir, { recursive: true, force: true }); }
});

it("keeps Unicode attachment and caller metadata under the signed push budget", () => {
  const attachment = { attachment_id: "att_00000000000000000000000001", chat_id: "chat_default", name: "😀".repeat(128), media_type: "application/octet-stream", size_bytes: 1, sha256: "0".repeat(64), created_at: 1 };
  const message = buildPayload("relay", "c2VjcmV0", { type: "msg.new", id: "evt_a", seq: 1, ts: 1, msg_id: "msg_a", from: "agent", text: "😀".repeat(4000), attachments: Array(8).fill(attachment) });
  expect(Buffer.byteLength(message.event)).toBeLessThanOrEqual(3000);
  expect(JSON.parse(message.event).truncated).toBe(true);
  const invite = buildPayload("relay", "c2VjcmV0", { type: "call.invite", id: "evt_a", seq: 1, ts: 1, call_id: "call_a", direction: "agent_to_user", agent_name: "Agent", reason: "😀".repeat(500), priority: "normal", expires_at: 1,
    chat: { chat_id: "chat_default", client_session_id: "😀".repeat(256), title: "😀".repeat(256), description: "😀".repeat(1024), caller: { provider: "😀".repeat(64), model: "😀".repeat(128), project: "😀".repeat(256) }, created_at: 1, updated_at: 1 } });
  expect(Buffer.byteLength(invite.event)).toBeLessThanOrEqual(3000);
  expect(JSON.parse(invite.event).chat.caller.model).toBe("😀".repeat(128));
});

it("fetches full questions instead of exceeding the signed push budget", () => {
  const question = { options: Array.from({ length: 8 }, (_, index) => ({ id: `option_${index}`, label: "😀".repeat(200) })), allow_free_text: true };
  const event = { type: "msg.new" as const, id: "evt_a", seq: 1, ts: 1, msg_id: "msg_a", from: "agent" as const, text: "Question", question };
  const pushed = buildPayload("relay", Buffer.alloc(32).toString("base64url"), event);
  expect(Buffer.byteLength(pushed.event)).toBeLessThanOrEqual(3000);
  expect(JSON.parse(pushed.event).truncated).toBe(true);
  expect(JSON.parse(pushed.event).question).toBeUndefined();
  expect(event.question).toEqual(question);
});
