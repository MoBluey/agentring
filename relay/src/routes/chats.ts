import type { FastifyInstance } from "fastify";
import { type Ctx, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import { newId } from "../ids.js";
import type { Chat, Schemas } from "../protocol.js";

export const DEFAULT_CHAT = "chat_default";
type Row = Omit<Chat, "caller" | "callee" | "archived_at"> & { caller: string; callee: Chat["callee"] | null; archived_at: number | null };
const fromRow = ({ callee, archived_at, ...r }: Row): Chat => ({ ...r, caller: JSON.parse(r.caller) as Chat["caller"],
  ...(callee ? { callee } : {}), ...(archived_at !== null ? { archived_at } : {}) });

type Filters = { status?: "all" | "active" | "archived"; provider?: string };
const filterProperties = {
  status: { type: "string", enum: ["all", "active", "archived"], default: "all" },
  provider: { type: "string", minLength: 1, maxLength: 64 },
};
function filters(query: Filters): { sql: string; values: string[] } {
  const conditions: string[] = [], values: string[] = [];
  if (query.status === "active") conditions.push("c.archived_at IS NULL");
  if (query.status === "archived") conditions.push("c.archived_at IS NOT NULL");
  if (query.provider !== undefined) {
    conditions.push("chat_fold(json_extract(c.caller, '$.provider')) = chat_fold(?)");
    values.push(query.provider);
  }
  return { sql: conditions.length ? conditions.join(" AND ") : "1", values };
}
function pattern(query: string): RegExp {
  const special = "\\^$.*+?()[]{}|";
  return new RegExp(Array.from(query, char => special.includes(char) ? "\\" + char : char).join(""), "iu");
}
function metadata(chat: Chat): string {
  return [chat.title, chat.description, chat.caller.provider, chat.caller.model, chat.caller.project, chat.client_session_id]
    .map(value => value ?? "").join("\n");
}
function snippet(text: string, match: RegExpExecArray): string {
  const start = Math.max(0, Array.from(text.slice(0, match.index)).length - 60);
  return Array.from(text).slice(start, start + 320).join("");
}
const METADATA = "c.title || char(10) || c.description || char(10) || COALESCE(json_extract(c.caller, '$.provider'), '') || char(10) || COALESCE(json_extract(c.caller, '$.model'), '') || char(10) || COALESCE(json_extract(c.caller, '$.project'), '') || char(10) || c.client_session_id";

export function getChat(ctx: Ctx, id = DEFAULT_CHAT): Chat {
  const row = ctx.db.prepare("SELECT * FROM chats WHERE chat_id = ?").get(id) as Row | undefined;
  if (!row) throw notFound("Chat");
  return fromRow(row);
}
export function touchChat(ctx: Ctx, id: string): void {
  ctx.db.prepare("UPDATE chats SET updated_at = ? WHERE chat_id = ?").run(Date.now(), id);
  if (id !== DEFAULT_CHAT) ctx.events.emit({ type: "chat.updated", chat: getChat(ctx, id) });
}
export function pendingCallChat(ctx: Ctx, callee: NonNullable<Chat["callee"]>, callId: string): Chat {
  const id = newId("chat"), now = Date.now();
  ctx.db.prepare("INSERT INTO chats (chat_id, client_session_id, title, description, caller, created_at, updated_at, callee) VALUES (?, ?, ?, ?, '{}', ?, ?, ?)")
    .run(id, `pending:${callId}`, `New ${callee === "codex" ? "Codex" : "Claude"} call`, "Waiting for a real provider session", now, now, callee);
  return getChat(ctx, id);
}

export function bindCallChat(ctx: Ctx, id: string, binding: Schemas["CallSessionBinding"]): Chat {
  const other = ctx.db.prepare("SELECT chat_id FROM chats WHERE client_session_id = ? AND chat_id != ?").get(binding.client_session_id, id);
  if (other) throw new ApiError(409, "invalid_state", "Provider session already belongs to another chat");
  const caller = { ...getChat(ctx, id).caller, ...binding.caller };
  const title = `${caller.model || "Unknown model"} · ${caller.project || "Unknown project"}`.slice(0, 256);
  const description = `Provider: ${caller.provider}; model: ${caller.model || "unknown"}; project: ${caller.project || "unknown"}`;
  ctx.db.prepare("UPDATE chats SET client_session_id = ?, caller = ?, title = ?, description = ?, updated_at = ?, callee = ? WHERE chat_id = ?")
    .run(binding.client_session_id, JSON.stringify(caller), title, description, Date.now(), binding.callee, id);
  touchChat(ctx, id);
  return getChat(ctx, id);
}

export function chatRoutes(app: FastifyInstance, ctx: Ctx): void {
  // SQLite's built-in lower()/NOCASE only fold ASCII. Match Unicode literally,
  // using the same expression for selection and snippet offsets.
  ctx.db.function("chat_fold", { deterministic: true }, value => typeof value === "string" ? value.toLowerCase() : "");
  ctx.db.function("chat_match", { deterministic: true }, (value, query) =>
    typeof value === "string" && typeof query === "string" ? Number(pattern(query).exec(value) !== null) : 0);
  const short = (maxLength: number, minLength = 0) => ({ type: "string", minLength, maxLength });
  app.post<{ Body: Schemas["PostChatRequest"] }>("/v1/chats", { preHandler: requireAuth(ctx, "agent"), schema: { body: {
    type: "object", additionalProperties: false, required: ["client_session_id"], properties: {
      callee: { type: "string", enum: ["claude", "codex"] },
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
    ctx.db.prepare(`INSERT INTO chats (chat_id, client_session_id, title, description, caller, created_at, updated_at, callee)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(client_session_id) DO UPDATE SET title=excluded.title,
      description=excluded.description, caller=excluded.caller, updated_at=excluded.updated_at, callee=COALESCE(excluded.callee, chats.callee)`)
      .run(id, b.client_session_id, title, description, JSON.stringify(caller), old?.created_at ?? now, now, b.callee ?? null);
    const chat = getChat(ctx, id);
    ctx.events.emit({ type: "chat.updated", chat });
    return reply.code(201).send(chat);
  });
  app.get<{ Querystring: Filters }>("/v1/chats", { preHandler: requireAuth(ctx, "agent", "device"),
    schema: { querystring: { type: "object", additionalProperties: false, properties: filterProperties } },
  }, async req => {
    const where = filters(req.query);
    return { chats: (ctx.db.prepare(`SELECT c.* FROM chats c WHERE ${where.sql} ORDER BY c.updated_at DESC, c.chat_id`)
      .all(...where.values) as Row[]).map(fromRow) };
  });
  app.get<{ Querystring: Filters & { q: string; limit?: number } }>("/v1/chats/search", {
    preHandler: requireAuth(ctx, "agent", "device"), schema: { querystring: {
      type: "object", additionalProperties: false, required: ["q"], properties: {
        ...filterProperties, q: { type: "string", minLength: 1, maxLength: 200 },
        limit: { type: "integer", minimum: 1, maximum: 30, default: 15 },
      },
    } },
  }, async req => {
    const { q, limit = 15 } = req.query;
    if (!q.trim()) throw new ApiError(400, "invalid_request", "Search query must contain text");
    const where = filters(req.query);
    const rows = ctx.db.prepare(`SELECT c.*, m.msg_id AS match_msg_id, m.text AS match_text, m.created_at AS match_created_at
      FROM chats c LEFT JOIN messages m ON m.msg_id = (
        SELECT msg_id FROM messages WHERE chat_id = c.chat_id AND chat_match(text, ?) = 1
        ORDER BY created_at DESC, msg_id DESC LIMIT 1
      ) WHERE ${where.sql} AND (chat_match(${METADATA}, ?) = 1 OR m.msg_id IS NOT NULL)
      ORDER BY c.updated_at DESC, c.chat_id LIMIT ?`).all(q, ...where.values, q, limit) as
        Array<Row & { match_msg_id: string | null; match_text: string | null; match_created_at: number | null }>;
    const results: Schemas["ChatSearchResult"][] = rows.map(row => {
      const { match_msg_id, match_text, match_created_at, ...rest } = row;
      const chat = fromRow(rest), text = metadata(chat), metadataMatch = pattern(q).exec(text);
      return { chat, match: metadataMatch ? { kind: "metadata", snippet: snippet(text, metadataMatch) } : {
        kind: "message", snippet: snippet(match_text!, pattern(q).exec(match_text!)!), msg_id: match_msg_id!, created_at: match_created_at!,
      } };
    });
    return { results };
  });
  app.patch<{ Params: { chat_id: string }; Body: { archived: boolean } }>("/v1/chats/:chat_id", {
    preHandler: requireAuth(ctx, "agent", "device"), schema: {
      params: { type: "object", required: ["chat_id"], properties: { chat_id: short(64, 1) } },
      body: { type: "object", additionalProperties: false, required: ["archived"], properties: { archived: { type: "boolean" } } },
    },
  }, async req => {
    const chat = getChat(ctx, req.params.chat_id);
    if (req.body.archived !== (chat.archived_at !== undefined)) {
      const now = Date.now();
      ctx.db.prepare("UPDATE chats SET archived_at = ?, updated_at = ? WHERE chat_id = ?")
        .run(req.body.archived ? now : null, now, chat.chat_id);
      const updated = getChat(ctx, chat.chat_id);
      ctx.events.emit({ type: "chat.updated", chat: updated });
      return updated;
    }
    return chat;
  });
  app.get<{ Params: { chat_id: string } }>("/v1/chats/:chat_id", { preHandler: requireAuth(ctx, "agent", "device") },
    async req => getChat(ctx, req.params.chat_id));
}
