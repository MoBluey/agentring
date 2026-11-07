import type { FastifyInstance } from "fastify";
import { partyOf } from "../auth.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, forbidden, notFound } from "../errors.js";
import { newId } from "../ids.js";
import type { Message, Party, Schemas } from "../protocol.js";
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
  delivered_at: number | null;
  question_json: string | null;
  answer_json: string | null;
  answer_to: string | null;
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
    ...(r.delivered_at ? { delivered_at: r.delivered_at } : {}),
    ...questionFields(ctx, r),
    ...(r.read_at ? { read_at: r.read_at } : {}),
  };
}

function normaliseQuestion(question: Schemas["QuestionRequest"]): Schemas["QuestionRequest"] {
  return { options: question.options.map(option => ({ id: option.id, label: option.label })),
    allow_free_text: question.allow_free_text ?? false };
}

function questionFields(ctx: Ctx, r: MessageRow): Pick<Message, "question" | "answer"> {
  const result: Pick<Message, "question" | "answer"> = {};
  if (r.question_json) {
    const question = JSON.parse(r.question_json) as Schemas["Question"];
    const answered = ctx.db.prepare("SELECT msg_id, created_at FROM messages WHERE answer_to = ?").get(r.msg_id) as
      { msg_id: string; created_at: number } | undefined;
    if (answered) { question.answered_by_msg_id = answered.msg_id; question.answered_at = answered.created_at; }
    result.question = question;
  }
  if (r.answer_json) result.answer = JSON.parse(r.answer_json) as Schemas["QuestionAnswer"];
  return result;
}

const ID = { type: "string", maxLength: 64 } as const;

export function messageRoutes(app: FastifyInstance, ctx: Ctx): void {
  const { db, events } = ctx;
  const auth = requireAuth(ctx, "agent", "device");
  const get = (id: string) =>
    db.prepare("SELECT * FROM messages WHERE msg_id = ?").get(id) as unknown as MessageRow | undefined;

  app.post<{ Body: Schemas["PostMessageRequest"] }>(
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
            question: { type: "object", additionalProperties: false, required: ["options"], properties: {
              options: { type: "array", minItems: 2, maxItems: 8, items: { type: "object", additionalProperties: false,
                required: ["id", "label"], properties: { id: { type: "string", pattern: "^[A-Za-z0-9_-]{1,64}$" },
                  label: { type: "string", minLength: 1, maxLength: 200 } } } },
              allow_free_text: { type: "boolean", default: false },
            } },
            answer: { type: "object", additionalProperties: false, required: ["question_id"], properties: {
              question_id: ID, option_id: { type: "string", pattern: "^[A-Za-z0-9_-]{1,64}$" },
              free_text: { type: "string", minLength: 1, maxLength: 4000 },
            } },
            reply_to: ID,
            client_msg_id: ID,
          },
        },
      },
    },
    async (req, reply) => {
      const p = principal(req);
      const from = partyOf(p);
      let { text, reply_to } = req.body;
      const { client_msg_id, chat_id = DEFAULT_CHAT, attachment_ids = [], question: questionInput, answer: answerInput } = req.body;
      const question = questionInput ? normaliseQuestion(questionInput) : undefined;
      if (question && answerInput) throw new ApiError(400, "invalid_request", "A message cannot ask and answer a question");
      if (question && p.kind !== "agent") throw forbidden("Only the agent may ask a question");
      if (answerInput && p.kind !== "device") throw forbidden("Only a paired device may answer a question");
      if ((question || answerInput) && !client_msg_id) throw new ApiError(400, "invalid_request", "Questions and answers require client_msg_id");
      if (question && (!text.trim() || new Set(question.options.map(o => o.id)).size !== question.options.length ||
          question.options.some(o => !o.label.trim()))) throw new ApiError(400, "invalid_request", "Question needs a prompt and unique labelled options");
      let answer: Schemas["QuestionAnswer"] | undefined;
      if (answerInput) {
        const hasOption = answerInput.option_id !== undefined, hasText = answerInput.free_text !== undefined;
        if (hasOption === hasText || (hasText && !answerInput.free_text?.trim())) throw new ApiError(400, "invalid_request", "Choose one option or write one answer");
        answer = { question_id: answerInput.question_id, ...(hasOption ? { option_id: answerInput.option_id! } : { free_text: answerInput.free_text! }) };
      }

      getChat(ctx, chat_id);
      if (client_msg_id) {
        const existing = db
          .prepare("SELECT * FROM messages WHERE sender = ? AND chat_id = ? AND COALESCE(device_id, '') = ? AND client_msg_id = ?")
          .get(from, chat_id, p.kind === "device" ? p.deviceId : "", client_msg_id) as unknown as MessageRow | undefined;
        if (existing) {
          if ((question || answer || existing.question_json || existing.answer_json) &&
              ((existing.question_json ? JSON.stringify(normaliseQuestion(JSON.parse(existing.question_json) as Schemas["QuestionRequest"])) : null) !== (question ? JSON.stringify(question) : null) ||
              existing.answer_json !== (answer ? JSON.stringify(answer) : null) ||
              (question && existing.text !== text))) throw new ApiError(409, "idempotency_conflict", "client_msg_id was used for another question or answer");
          return reply.code(201).send(fromRow(ctx, existing));
        }
      }
      if (answer) {
        const asked = get(answer.question_id);
        if (!asked || !asked.question_json || asked.sender !== "agent") throw notFound("Question");
        if (asked.chat_id !== chat_id) throw new ApiError(400, "invalid_request", "Answer belongs to another chat");
        if (reply_to && reply_to !== answer.question_id) throw new ApiError(400, "invalid_request", "reply_to must identify the question");
        if (db.prepare("SELECT 1 FROM messages WHERE answer_to = ?").get(answer.question_id)) throw new ApiError(409, "question_answered", "This question already has an answer");
        const askedQuestion = JSON.parse(asked.question_json) as Schemas["QuestionRequest"];
        if (answer.option_id !== undefined) {
          const option = askedQuestion.options.find(o => o.id === answer!.option_id);
          if (!option) throw new ApiError(400, "invalid_request", "Unknown question option");
          text = option.label;
        } else {
          if (!askedQuestion.allow_free_text) throw new ApiError(400, "invalid_request", "Free-written answers are not enabled");
          text = answer.free_text!;
        }
        reply_to = answer.question_id;
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
        delivered_at: null,
        question_json: question ? JSON.stringify(question) : null,
        answer_json: answer ? JSON.stringify(answer) : null,
        answer_to: answer?.question_id ?? null,
      };
      db.exec("BEGIN");
      try {
        db.prepare(
          `INSERT INTO messages (msg_id, sender, text, reply_to, client_msg_id, device_id, created_at, chat_id, question_json, answer_json, answer_to)
           VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
        ).run(row.msg_id, row.sender, row.text, row.reply_to, row.client_msg_id, row.device_id, row.created_at, chat_id, row.question_json, row.answer_json, row.answer_to);
        for (const id of attachment_ids) db.prepare("UPDATE attachments SET msg_id = ? WHERE attachment_id = ?").run(row.msg_id, id);
        db.exec("COMMIT");
      } catch (e) {
        db.exec("ROLLBACK");
        if (answer && String(e).includes("messages.answer_to")) throw new ApiError(409, "question_answered", "This question already has an answer");
        throw e;
      }
      touchChat(ctx, chat_id);
      events.emit({
        type: "msg.new",
        msg_id: row.msg_id,
        from,
        text,
        chat_id,
        attachments: messageAttachments(ctx, row.msg_id),
        ...questionFields(ctx, row),
        ...(reply_to ? { reply_to } : {}),
      });
      if (answer) events.emit({ type: "question.answered", chat_id, question_id: answer.question_id,
        answer_msg_id: row.msg_id, answered_at: row.created_at });
      return reply.code(201).send(fromRow(ctx, row));
    },
  );

  app.get<{ Querystring: { after?: string; since?: number; from?: Party; limit?: number; chat_id?: string; unread?: boolean } }>(
    "/v1/messages",
    {
      preHandler: auth,
      schema: {
        querystring: {
          type: "object",
          properties: {
            unread: { type: "boolean" },
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
      const { after, since, from, limit = 100, chat_id, unread } = req.query;
      const where: string[] = [];
      const args: Array<string | number> = [];
      if (unread === true) where.push("read_at IS NULL");
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

  for (const receipt of ["delivered", "read"] as const) {
    app.post<{ Params: { msg_id: string } }>(`/v1/messages/:msg_id/${receipt}`, { preHandler: auth }, async (req, reply) => {
      const by = partyOf(principal(req));
      const row = get(req.params.msg_id);
      if (!row || row.sender === by) throw notFound("Message");
      const delivered = db.prepare("UPDATE messages SET delivered_at = ? WHERE msg_id = ? AND delivered_at IS NULL")
        .run(Date.now(), row.msg_id);
      if (delivered.changes === 1) events.emit({ type: "msg.delivered", msg_id: row.msg_id, by });
      if (receipt === "read") {
        const read = db.prepare("UPDATE messages SET read_at = ? WHERE msg_id = ? AND read_at IS NULL").run(Date.now(), row.msg_id);
        if (read.changes === 1) events.emit({ type: "msg.read", msg_id: row.msg_id, by });
      }
      return reply.code(204).send();
    });
  }
}
