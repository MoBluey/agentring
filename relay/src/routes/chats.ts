import type { FastifyInstance } from "fastify";
import { type Ctx, requireAuth } from "../context.js";
import { notFound } from "../errors.js";
import { newId } from "../ids.js";
import type { Chat, Schemas } from "../protocol.js";

export const DEFAULT_CHAT = "chat_default";
type Row = Omit<Chat, "caller"> & { caller: string };
const fromRow = (r: Row): Chat => ({ ...r, caller: JSON.parse(r.caller) as Chat["caller"] });
export function getChat(ctx: Ctx, id = DEFAULT_CHAT): Chat {
  const row = ctx.db.prepare("SELECT * FROM chats WHERE chat_id = ?").get(id) as Row | undefined;
  if (!row) throw notFound("Chat");
  return fromRow(row);
}
export function touchChat(ctx: Ctx, id: string): void {
  ctx.db.prepare("UPDATE chats SET updated_at = ? WHERE chat_id = ?").run(Date.now(), id);
  if (id !== DEFAULT_CHAT) ctx.events.emit({ type: "chat.updated", chat: getChat(ctx, id) });
}
export function chatRoutes(app: FastifyInstance, ctx: Ctx): void {
  const short = (maxLength: number, minLength = 0) => ({ type: "string", minLength, maxLength });
  app.post<{ Body: Schemas["PostChatRequest"] }>("/v1/chats", { preHandler: requireAuth(ctx, "agent"), schema: { body: {
    type: "object", additionalProperties: false, required: ["client_session_id"], properties: {
      client_session_id: short(256, 1), title: short(256, 1), description: short(1024),
      caller: { type: "object", additionalProperties: false, properties: { provider: short(64, 1), model: short(128, 1), project: short(256, 1) } },
    },
  } } }, async (req, reply) => {
    const b = req.body;
    const existing = ctx.db.prepare("SELECT * FROM chats WHERE client_session_id = ?").get(b.client_session_id) as Row | undefined;
    const old = existing ? fromRow(existing) : undefined;
    const caller = { ...old?.caller, ...b.caller };
    const title = b.title ?? (b.caller || !old ? `${caller.model || "Unknown model"} · ${caller.project || "Unknown project"}`.slice(0, 256) : old.title);
    const description = b.description ?? (b.caller || !old ? `Provider: ${caller.provider || "unknown"}; model: ${caller.model || "unknown"}; project: ${caller.project || "unknown"}` : old.description);
    const now = Date.now(), id = old?.chat_id ?? newId("chat");
    ctx.db.prepare(`INSERT INTO chats (chat_id, client_session_id, title, description, caller, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(client_session_id) DO UPDATE SET title=excluded.title,
      description=excluded.description, caller=excluded.caller, updated_at=excluded.updated_at`)
      .run(id, b.client_session_id, title, description, JSON.stringify(caller), old?.created_at ?? now, now);
    const chat = getChat(ctx, id);
    ctx.events.emit({ type: "chat.updated", chat });
    return reply.code(201).send(chat);
  });
  app.get("/v1/chats", { preHandler: requireAuth(ctx, "agent", "device") }, async () => ({
    chats: (ctx.db.prepare("SELECT * FROM chats ORDER BY updated_at DESC, chat_id").all() as Row[]).map(fromRow),
  }));
  app.get<{ Params: { chat_id: string } }>("/v1/chats/:chat_id", { preHandler: requireAuth(ctx, "agent", "device") },
    async req => getChat(ctx, req.params.chat_id));
}
