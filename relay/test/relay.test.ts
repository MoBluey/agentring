import { readFileSync, readdirSync } from "node:fs";
import { join } from "node:path";
import { afterEach, describe, expect, it } from "vitest";
import WebSocket from "ws";
import { ulid } from "../src/ids.js";
import { buildPayload, signEvent } from "../src/push/dispatcher.js";
import { parseGoogleServices } from "../src/config.js";
import { AGENT, pairDevice, setup } from "./helpers.js";

const PROTOCOL = join(__dirname, "..", "..", "protocol");

let cleanup: Array<() => Promise<unknown>> = [];
afterEach(async () => {
  for (const c of cleanup) await c();
  cleanup = [];
});

async function app(over = {}) {
  const b = await setup(over);
  cleanup.push(() => b.app.close());
  return b;
}

describe("pairing", () => {
  it("issues a single-use token and a working device JWT", async () => {
    const b = await app();
    const t = await b.app.inject({ method: "POST", url: "/v1/pairing-tokens", headers: AGENT });
    expect(t.statusCode).toBe(201);
    const tok = t.json();
    expect(JSON.parse(tok.qr_payload)).toEqual({ v: 1, relay_url: "https://relay.test", pairing_token: tok.pairing_token });

    const pair = (pairing_token: string) =>
      b.app.inject({
        method: "POST",
        url: "/v1/pair",
        payload: { pairing_token, device_name: "Pixel 8", platform: "android", app_version: "0.1.0" },
      });
    const r = await pair(tok.pairing_token);
    expect(r.statusCode).toBe(201);
    const body = r.json();
    expect(body.device_id).toMatch(/^dev_[0-9A-Z]{26}$/);
    expect(body.relay_id).toBe(b.ctx.relayId);
    expect(body.agent_name).toBe("TestAgent");
    expect(body.push_config).toEqual({ provider: "fcm", fcm: expect.objectContaining({ project_id: "p" }) });
    expect(Buffer.from(body.push_secret, "base64url")).toHaveLength(32);

    // single use
    const again = await pair(tok.pairing_token);
    expect(again.statusCode).toBe(401);
    expect(again.json().error.code).toBe("invalid_pairing_token");

    // JWT works
    const fcm = await b.app.inject({
      method: "POST",
      url: "/v1/device/fcm",
      headers: { authorization: `Bearer ${body.device_jwt}` },
      payload: { fcm_token: "new-token" },
    });
    expect(fcm.statusCode).toBe(204);
    const devs = (await b.app.inject({ url: "/v1/devices", headers: AGENT })).json().devices;
    expect(devs).toHaveLength(1);
    expect(devs[0]).toMatchObject({ name: "Pixel 8", has_push_token: true, app_version: "0.1.0" });
  });

  it("rejects expired tokens", async () => {
    const b = await app();
    const tok = (await b.app.inject({ method: "POST", url: "/v1/pairing-tokens", headers: AGENT, payload: { ttl_s: 30 } })).json();
    b.ctx.db.prepare("UPDATE pairing_tokens SET expires_at = 0").run();
    const r = await b.app.inject({
      method: "POST",
      url: "/v1/pair",
      payload: { pairing_token: tok.pairing_token, device_name: "x", platform: "android" },
    });
    expect(r.statusCode).toBe(401);
  });

  it("rate limits /v1/pair", async () => {
    const b = await app();
    const codes: number[] = [];
    for (let i = 0; i < 12; i++) {
      const r = await b.app.inject({
        method: "POST",
        url: "/v1/pair",
        payload: { pairing_token: "pt_nope", device_name: "x", platform: "android" },
      });
      codes.push(r.statusCode);
    }
    expect(codes.slice(0, 10).every((c) => c === 401)).toBe(true);
    expect(codes.at(-1)).toBe(429);
  });

  it("requires auth and separates agent and device endpoints", async () => {
    const b = await app();
    const d = await pairDevice(b);
    expect((await b.app.inject({ url: "/v1/devices" })).statusCode).toBe(401);
    expect((await b.app.inject({ url: "/v1/devices", headers: { authorization: "Bearer ak_wrong" } })).statusCode).toBe(401);
    expect((await b.app.inject({ url: "/v1/devices", headers: d.headers })).statusCode).toBe(403);
    expect((await b.app.inject({ method: "POST", url: "/v1/pairing-tokens", headers: d.headers })).statusCode).toBe(403);
    expect((await b.app.inject({ url: "/v1/status", headers: { authorization: "Bearer eyJ.bad.jwt" } })).statusCode).toBe(401);
  });

  it("revocation is immediate", async () => {
    const b = await app();
    const d = await pairDevice(b);
    expect((await b.app.inject({ url: "/v1/status", headers: d.headers })).statusCode).toBe(200);
    const del = await b.app.inject({ method: "DELETE", url: `/v1/devices/${d.device_id}`, headers: AGENT });
    expect(del.statusCode).toBe(204);
    const r = await b.app.inject({ url: "/v1/status", headers: d.headers });
    expect(r.statusCode).toBe(401);
    expect((await b.app.inject({ url: "/v1/devices", headers: AGENT })).json().devices).toHaveLength(0);
    expect(
      (await b.app.inject({ url: "/v1/devices?include_revoked=true", headers: AGENT })).json().devices[0].revoked_at,
    ).toBeGreaterThan(0);
  });
});

describe("messages", () => {
  it("agent → user message is stored, logged as an event and pushed signed", async () => {
    const b = await app();
    const d = await pairDevice(b);
    const r = await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "hola" } });
    expect(r.statusCode).toBe(201);
    const msg = r.json();
    expect(msg).toMatchObject({ from: "agent", text: "hola" });

    expect(b.push.sent).toHaveLength(1);
    const { payload, opts, target } = b.push.sent[0]!;
    expect(target.token).toBe("fcm-tok-1");
    expect(opts.highPriority).toBe(true);
    expect(payload.relay_id).toBe(b.ctx.relayId);
    expect(payload.sig).toBe(signEvent(d.push_secret, payload.event!));
    const ev = JSON.parse(payload.event!);
    expect(ev).toMatchObject({ type: "msg.new", msg_id: msg.msg_id, from: "agent", text: "hola" });
    expect(ev.seq).toBeGreaterThan(0);

    const events = (await b.app.inject({ url: "/v1/events?after_seq=0", headers: d.headers })).json();
    expect(events.events.map((e: { type: string }) => e.type)).toEqual(["msg.new"]);
  });

  it("user reply reaches the agent, is idempotent, and is not pushed", async () => {
    const b = await app();
    const d = await pairDevice(b);
    const send = () =>
      b.app.inject({
        method: "POST",
        url: "/v1/messages",
        headers: d.headers,
        payload: { text: "sí, despliega", client_msg_id: "c1" },
      });
    const a = (await send()).json();
    const a2 = (await send()).json();
    expect(a2.msg_id).toBe(a.msg_id);
    expect(a).toMatchObject({ from: "user", device_id: d.device_id });
    expect(b.push.sent).toHaveLength(0);

    const list = (await b.app.inject({ url: "/v1/messages?from=user", headers: AGENT })).json();
    expect(list.messages).toHaveLength(1);
    expect(list.has_more).toBe(false);

    const read = await b.app.inject({ method: "POST", url: `/v1/messages/${a.msg_id}/read`, headers: AGENT });
    expect(read.statusCode).toBe(204);
    await b.app.inject({ method: "POST", url: `/v1/messages/${a.msg_id}/read`, headers: AGENT });
    const ev = (await b.app.inject({ url: "/v1/events", headers: AGENT })).json().events;
    expect(ev.map((e: { type: string }) => e.type)).toEqual(["msg.new", "msg.delivered", "msg.read"]);
    expect(ev[1]).toMatchObject({ msg_id: a.msg_id, by: "agent" });

    // can't mark your own message read
    const own = await b.app.inject({ method: "POST", url: `/v1/messages/${a.msg_id}/read`, headers: d.headers });
    expect(own.statusCode).toBe(404);
  });

  it("paginates with after/limit in creation order", async () => {
    const b = await app();
    for (let i = 0; i < 5; i++) {
      await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: `m${i}` } });
    }
    const p1 = (await b.app.inject({ url: "/v1/messages?limit=3", headers: AGENT })).json();
    expect(p1.messages.map((m: { text: string }) => m.text)).toEqual(["m0", "m1", "m2"]);
    expect(p1.has_more).toBe(true);
    const p2 = (await b.app.inject({ url: `/v1/messages?limit=3&after=${p1.messages[2].msg_id}`, headers: AGENT })).json();
    expect(p2.messages.map((m: { text: string }) => m.text)).toEqual(["m3", "m4"]);
  });

  it("validates input", async () => {
    const b = await app();
    const r = await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "" } });
    expect(r.statusCode).toBe(400);
    expect(r.json().error.code).toBe("invalid_request");
  });

  it("clears the FCM token when the provider reports it unregistered", async () => {
    const b = await app();
    const d = await pairDevice(b);
    b.push.next = { ok: false, unregistered: true, error: "404 UNREGISTERED" };
    await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: "x" } });
    await new Promise((r) => setTimeout(r, 10));
    const row = b.ctx.db.prepare("SELECT fcm_token FROM devices WHERE device_id = ?").get(d.device_id) as {
      fcm_token: string | null;
    };
    expect(row.fcm_token).toBeNull();
  });
});

describe("push payload", () => {
  it("matches the shared signature test vector", () => {
    const v = JSON.parse(readFileSync(join(PROTOCOL, "examples/push/signature-vector.json"), "utf8"));
    expect(signEvent(v.push_secret, v.data.event)).toBe(v.data.sig);
  });

  it("truncates long messages under the FCM size cap", () => {
    const text = "ñ😀".repeat(2000);
    const p = buildPayload("rly_x", "c2VjcmV0", {
      type: "msg.new",
      id: "evt_x",
      ts: 1,
      seq: 1,
      msg_id: "msg_x",
      from: "agent",
      text,
    });
    expect(Buffer.byteLength(p.event!)).toBeLessThanOrEqual(3000);
    const ev = JSON.parse(p.event!);
    expect(ev.truncated).toBe(true);
    expect(text.startsWith(ev.text)).toBe(true);
  });

  it("parses google-services.json", () => {
    const gs = {
      project_info: { project_number: "123", project_id: "my-proj" },
      client: [
        {
          client_info: { mobilesdk_app_id: "1:123:android:abc", android_client_info: { package_name: "dev.agentring.app" } },
          api_key: [{ current_key: "AIza" }],
        },
      ],
    };
    expect(parseGoogleServices(gs)).toEqual({
      project_id: "my-proj",
      sender_id: "123",
      application_id: "1:123:android:abc",
      api_key: "AIza",
    });
    expect(() => parseGoogleServices({ ...gs, client: [] })).toThrow(/package name/);
  });
});

describe("ids", () => {
  it("are monotonic within the same millisecond", () => {
    const ids = Array.from({ length: 1000 }, () => ulid(1_700_000_000_000));
    expect([...ids].sort()).toEqual(ids);
    expect(new Set(ids).size).toBe(1000);
  });
});

describe("protocol fixtures", () => {
  it("every event fixture has a known type", () => {
    const known = ["question.answered", "msg.delivered", "chat.updated", "call.invite", "call.ringing", "call.accept", "call.decline", "call.end", "call.missed", "msg.new", "msg.read", "agent.presence", "ws.hello"];
    for (const f of readdirSync(join(PROTOCOL, "examples/events"))) {
      const e = JSON.parse(readFileSync(join(PROTOCOL, "examples/events", f), "utf8"));
      expect(known).toContain(e.type);
    }
  });
});

describe("websocket", () => {
  function connect(url: string, headers: Record<string, string>) {
    const ws = new WebSocket(url, { headers });
    const frames: Array<Record<string, unknown>> = [];
    const waiters: Array<() => void> = [];
    ws.on("message", (d) => {
      frames.push(JSON.parse(d.toString()));
      waiters.splice(0).forEach((w) => w());
    });
    const until = async (pred: () => boolean, ms = 2000) => {
      const end = Date.now() + ms;
      while (!pred()) {
        if (Date.now() > end) throw new Error(`timeout; frames=${JSON.stringify(frames)}`);
        await new Promise<void>((r) => {
          waiters.push(r);
          setTimeout(r, 50);
        });
      }
    };
    const closed = new Promise<number>((r) => ws.on("close", (code) => r(code)));
    return { ws, frames, until, closed };
  }

  it("replays from after_seq, streams live, and reports agent presence", async () => {
    const b = await app();
    await b.app.listen({ port: 0, host: "127.0.0.1" });
    const base = `ws://127.0.0.1:${(b.app.server.address() as { port: number }).port}/v1/ws`;
    const d = await pairDevice(b);
    for (const t of ["a", "b", "c"]) {
      await b.app.inject({ method: "POST", url: "/v1/messages", headers: AGENT, payload: { text: t } });
    }

    const dev = connect(`${base}?after_seq=1`, d.headers);
    await dev.until(() => dev.frames.length >= 4);
    expect(dev.frames.map((f) => f.type)).toEqual(["ws.hello", "msg.new", "msg.new", "agent.presence"]);
    expect(dev.frames[0]).toMatchObject({ relay_id: b.ctx.relayId, last_seq: 3 });
    expect(dev.frames.slice(1, 3).map((f) => f.text)).toEqual(["b", "c"]);
    expect(dev.frames[3]).toMatchObject({ online: false });

    const agent = connect(base, AGENT);
    await agent.until(() => agent.frames.length >= 4);
    await dev.until(() => dev.frames.some((f) => f.type === "agent.presence" && f.online === true));

    await b.app.inject({ method: "POST", url: "/v1/messages", headers: d.headers, payload: { text: "reply" } });
    await agent.until(() => agent.frames.some((f) => f.text === "reply"));

    // A second agent connection replaces the first.
    const agent2 = connect(base, AGENT);
    expect(await agent.closed).toBe(4409);
    await agent2.until(() => agent2.frames.length >= 1);

    agent2.ws.close();
    await dev.until(() => dev.frames.filter((f) => f.type === "agent.presence").at(-1)?.online === false);

    // Revoking closes the device socket.
    await b.app.inject({ method: "DELETE", url: `/v1/devices/${d.device_id}`, headers: AGENT });
    expect(await dev.closed).toBe(4401);
  });

  it("rejects unauthenticated upgrades", async () => {
    const b = await app();
    await b.app.listen({ port: 0, host: "127.0.0.1" });
    const port = (b.app.server.address() as { port: number }).port;
    const ws = new WebSocket(`ws://127.0.0.1:${port}/v1/ws`);
    const status = await new Promise<number | undefined>((r) => ws.on("unexpected-response", (_q, res) => r(res.statusCode)));
    expect(status).toBe(401);
  });
});
