import type { FastifyInstance } from "fastify";
import { partyOf } from "../auth.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import { newId } from "../ids.js";
import type { Message, Party } from "../protocol.js";
import { checkMessage } from "../quota.js";
import { messageAttachments, ownerOf } from "./attachments.js";
import { DEFAULT_CHAT, getChat, touchChat } from "./chats.js";

interface MessageRow {
  chat_id: string;
  msg_id: string;
  sender: Party;
  text: string;
  reply_to: string | null;
  client_msg_id: string | null;
  device_id: string | null;
  created_at: number;
  read_at: number | null;
}

function fromRow(ctx: Ctx, r: MessageRow): Message {
  return {
    msg_id: r.msg_id,
    from: r.sender,
    text: r.text,
    chat_id: r.chat_id,
    attachments: messageAttachments(ctx, r.msg_id),
    ...(r.reply_to ? { reply_to: r.reply_to } : {}),
    ...(r.client_msg_id ? { client_msg_id: r.client_msg_id } : {}),
    ...(r.device_id ? { device_id: r.device_id } : {}),
    created_at: r.created_at,
    ...(r.read_at ? { read_at: r.read_at } : {}),
  };
}

const ID = { type: "string", maxLength: 64 } as const;

export function messageRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { db, events } = ctx;
  const auth = requireAuth(ctx, "agent", "device");
  const get = (id: string) =>
    db.prepare("SELECT * FROM messages WHERE msg_id = ?").get(id) as unknown as MessageRow | undefined;

  app.post<{ Body: { text: string; reply_to?: string; client_msg_id?: string; chat_id?: string; attachment_ids?: string[] } }>(
    "/v1/messages",
    {
      preHandler: auth,
      schema: {
        body: {
          type: "object",
          additionalProperties: false,
          required: ["text"],
          properties: {
            text: { type: "string", maxLength: 4000 },
            attachment_ids: { type: "array", maxItems: 8, uniqueItems: true, items: ID },
            chat_id: ID,
            reply_to: ID,
            client_msg_id: ID,
          },
        },
      },
    },
    async (req, reply) => {
      const p = principal(req);
      const from = partyOf(p);
      const { text, reply_to, client_msg_id, chat_id = DEFAULT_CHAT, attachment_ids = [] } = req.body;

      getChat(ctx, chat_id);
      if (client_msg_id) {
        const existing = db
          .prepare("SELECT * FROM messages WHERE sender = ? AND chat_id = ? AND COALESCE(device_id, '') = ? AND client_msg_id = ?")
          .get(from, chat_id, p.kind === "device" ? p.deviceId : "", client_msg_id) as unknown as MessageRow | undefined;
        if (existing) return reply.code(201).send(fromRow(ctx, existing));
      }
      if (!text.length && !attachment_ids.length) throw new ApiError(400, "invalid_request", "A message needs text or attachments");
      if (reply_to) {
        const target = db.prepare("SELECT chat_id FROM messages WHERE msg_id = ? UNION ALL SELECT chat_id FROM calls WHERE call_id = ?").get(reply_to, reply_to) as { chat_id: string } | undefined;
        if (target && target.chat_id !== chat_id) throw new ApiError(400, "invalid_request", "Reply belongs to another chat");
      }
      for (const id of attachment_ids) {
        const attachment = db.prepare("SELECT owner, msg_id, chat_id, created_at FROM attachments WHERE attachment_id = ?").get(id) as { owner: string; msg_id: string | null; chat_id: string; created_at: number } | undefined;
        if (!attachment || attachment.owner !== ownerOf(p) || attachment.msg_id !== null || attachment.chat_id !== chat_id || attachment.created_at < Date.now() - 24 * 3600 * 1000) throw notFound("Unposted attachment");
      }
      checkMessage(db, ctx.limits());

      const row: MessageRow = {
        chat_id,
        msg_id: newId("msg"),
        sender: from,
        text,
        reply_to: reply_to ?? null,
        client_msg_id: client_msg_id ?? null,
        device_id: p.kind === "device" ? p.deviceId : null,
        created_at: Date.now(),
        read_at: null,
      };
      db.exec("BEGIN");
      try {
        db.prepare(
          `INSERT INTO messages (msg_id, sender, text, reply_to, client_msg_id, device_id, created_at, chat_id)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
        ).run(row.msg_id, row.sender, row.text, row.reply_to, row.client_msg_id, row.device_id, row.created_at, chat_id);
        for (const id of attachment_ids) db.prepare("UPDATE attachments SET msg_id = ? WHERE attachment_id = ?").run(row.msg_id, id);
        db.exec("COMMIT");
      } catch (e) { db.exec("ROLLBACK"); throw e; }
      touchChat(ctx, chat_id);
      events.emit({
        type: "msg.new",
        msg_id: row.msg_id,
        from,
        text,
        chat_id,
        attachments: messageAttachments(ctx, row.msg_id),
        ...(reply_to ? { reply_to } : {}),
      });
      return reply.code(201).send(fromRow(ctx, row));
    },
  );

  app.get<{ Querystring: { after?: string; since?: number; from?: Party; limit?: number; chat_id?: string } }>(
    "/v1/messages",
    {
      preHandler: auth,
      schema: {
        querystring: {
          type: "object",
          properties: {
            chat_id: ID,
            after: ID,
            since: { type: "integer", minimum: 0 },
            from: { type: "string", enum: ["agent", "user"] },
            limit: { type: "integer", minimum: 1, maximum: 500, default: 100 },
          },
        },
      },
    },
    async (req) => {
      const { after, since, from, limit = 100, chat_id } = req.query;
      const where: string[] = [];
      const args: Array<string | number> = [];
      if (chat_id) { where.push("chat_id = ?"); args.push(chat_id); }
      if (after) {
        where.push("msg_id > ?");
        args.push(after);
      }
      if (since !== undefined) {
        where.push("created_at >= ?");
        args.push(since);
      }
      if (from) {
        where.push("sender = ?");
        args.push(from);
      }
      const rows = db
        .prepare(
          `SELECT * FROM messages ${where.length ? "WHERE " + where.join(" AND ") : ""} ORDER BY msg_id LIMIT ?`,
        )
        .all(...args, limit + 1) as unknown as MessageRow[];
      return { messages: rows.slice(0, limit).map(r => fromRow(ctx, r)), has_more: rows.length > limit };
    },
  );

  app.get<{ Params: { msg_id: string } }>("/v1/messages/:msg_id", { preHandler: auth }, async (req) => {
    const row = get(req.params.msg_id);
    if (!row) throw notFound("Message");
    return fromRow(ctx, row);
  });

  app.post<{ Params: { msg_id: string } }>("/v1/messages/:msg_id/read", { preHandler: auth }, async (req, reply) => {
    const by = partyOf(principal(req));
    const row = get(req.params.msg_id);
    // You can only mark the other party's messages as read.
    if (!row || row.sender === by) throw notFound("Message");
    const res = db
      .prepare("UPDATE messages SET read_at = ? WHERE msg_id = ? AND read_at IS NULL")
      .run(Date.now(), row.msg_id);
    if (res.changes === 1) events.emit({ type: "msg.read", msg_id: row.msg_id, by });
    return reply.code(204).send();
  });
}
