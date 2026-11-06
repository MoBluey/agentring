import { createHash } from "node:crypto";
import { constants, chmodSync, lstatSync, mkdirSync, mkdtempSync, readdirSync, rmSync, unlinkSync } from "node:fs";
import { open, rename, unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Readable } from "node:stream";
import type { FastifyInstance } from "fastify";
import type { Principal } from "../auth.js";
import { type Ctx, principal, requireAuth } from "../context.js";
import { ApiError, notFound } from "../errors.js";
import { newId } from "../ids.js";
import type { Attachment } from "../protocol.js";
import { DEFAULT_CHAT, getChat } from "./chats.js";

export const MAX_FILE_BYTES = 100 * 1024 * 1024;
const MAX_STORED_BYTES = 2 * 1024 * 1024 * 1024;
const MAX_UPLOAD_AGE_MS = 24 * 3600 * 1000;
export const ownerOf = (p: Principal): string => p.kind === "agent" ? "agent" : `device:${p.deviceId}`;
type Row = Attachment & { owner: string; msg_id: string | null };
const metadata = (r: Row): Attachment => ({ attachment_id: r.attachment_id, chat_id: r.chat_id, name: r.name,
  media_type: r.media_type, size_bytes: r.size_bytes, sha256: r.sha256, created_at: r.created_at });
export function messageAttachments(ctx: Ctx, msgId: string): Attachment[] {
  return (ctx.db.prepare("SELECT * FROM attachments WHERE msg_id = ? ORDER BY attachment_id").all(msgId) as Row[]).map(metadata);
}

/** Each tenant owns its directory and DB. Storage paths use generated IDs only. */
export function attachmentRoutes(app: FastifyInstance, ctx: Ctx): void {
  const ephemeral = ctx.cfg.dataDir === ":memory:";
  const dir = ephemeral ? mkdtempSync(join(tmpdir(), "agentring-files-")) : join(ctx.cfg.dataDir, "attachments");
  mkdirSync(dir, { recursive: true, mode: 0o700 });
  if (!lstatSync(dir).isDirectory() || lstatSync(dir).isSymbolicLink()) throw new Error("Unsafe attachment directory");
  chmodSync(dir, 0o700);
  const path = (id: string) => {
    if (!/^att_[0-9A-HJKMNP-TV-Z]{26}$/.test(id)) throw notFound("Attachment");
    return join(dir, id);
  };
  const cleanup = () => {
    const expired = ctx.db.prepare("SELECT attachment_id FROM attachments WHERE msg_id IS NULL AND created_at < ?")
      .all(Date.now() - MAX_UPLOAD_AGE_MS) as { attachment_id: string }[];
    for (const r of expired) {
      try { unlinkSync(path(r.attachment_id)); } catch (e) { if ((e as NodeJS.ErrnoException).code !== "ENOENT") throw e; }
      ctx.db.prepare("DELETE FROM attachments WHERE attachment_id = ?").run(r.attachment_id);
    }
  };
  cleanup();
  // A crash can leave an incomplete upload or a renamed blob without its DB commit.
  for (const name of readdirSync(dir)) {
    if (/^att_[0-9A-HJKMNP-TV-Z]{26}(\.part)?$/.test(name) &&
      (name.endsWith(".part") || !ctx.db.prepare("SELECT 1 FROM attachments WHERE attachment_id = ?").get(name))) unlinkSync(join(dir, name));
  }
  let reserved = 0;
  const auth = requireAuth(ctx, "agent", "device");
  app.addContentTypeParser("application/octet-stream", (_req, payload, done) => done(null, payload));
  app.post<{ Querystring: { name: string; media_type?: string; chat_id?: string }; Body: Readable }>("/v1/attachments", {
    onRequest: async req => {
      await auth(req);
      if (req.headers["content-type"]?.split(";")[0]?.trim() !== "application/octet-stream") throw new ApiError(415, "invalid_request", "Use application/octet-stream");
    }, schema: { querystring: { type: "object", additionalProperties: false, required: ["name"], properties: {
      name: { type: "string", minLength: 1, maxLength: 128 }, chat_id: { type: "string", maxLength: 64 },
      media_type: { type: "string", maxLength: 127, default: "application/octet-stream" },
    } } },
  }, async (req, reply) => {
    cleanup();
    const { name, media_type = "application/octet-stream", chat_id = DEFAULT_CHAT } = req.query;
    getChat(ctx, chat_id);
    if (name === "." || name === ".." || /[\\/\x00-\x1f\x7f]/.test(name) ||
      !/^[a-zA-Z0-9!#$&^_.+-]+\/[a-zA-Z0-9!#$&^_.+-]+$/.test(media_type)) throw new ApiError(400, "invalid_request", "Invalid filename or media type");
    const declared = req.headers["content-length"] === undefined ? undefined : Number(req.headers["content-length"]);
    if (declared !== undefined && (!Number.isSafeInteger(declared) || declared < 0)) throw new ApiError(400, "invalid_request", "Invalid content length");
    if (declared !== undefined && declared > MAX_FILE_BYTES) throw new ApiError(413, "invalid_request", "File exceeds 100 MiB");
    const reservation = declared ?? MAX_FILE_BYTES;
    const used = (ctx.db.prepare("SELECT COALESCE(SUM(size_bytes), 0) AS n FROM attachments").get() as { n: number }).n;
    if (used + reserved + reservation > MAX_STORED_BYTES) throw new ApiError(507, "quota_exceeded", "Attachment storage is full (2 GiB)");
    reserved += reservation;
    const id = newId("att"), destination = path(id), temporary = `${destination}.part`;
    let completed = false;
    try {
      const file = await open(temporary, constants.O_CREAT | constants.O_EXCL | constants.O_WRONLY | constants.O_NOFOLLOW, 0o600);
      const hash = createHash("sha256");
      let size = 0;
      try {
        for await (const chunk of req.body) {
          const bytes = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
          size += bytes.length;
          if (size > MAX_FILE_BYTES || size > reservation) throw new ApiError(413, "invalid_request", "File exceeds allowed size");
          hash.update(bytes);
          await file.writeFile(bytes);
        }
        if (req.raw.aborted || (declared !== undefined && size !== declared)) throw new ApiError(400, "invalid_request", "Incomplete upload");
        await file.sync();
      } finally { await file.close(); }
      await auth(req); // Revocation while the upload was in flight must take effect.
      await rename(temporary, destination);
      const result: Attachment = { attachment_id: id, chat_id, name, media_type, size_bytes: size, sha256: hash.digest("hex"), created_at: Date.now() };
      ctx.db.prepare("INSERT INTO attachments (attachment_id, chat_id, name, media_type, size_bytes, sha256, created_at, owner) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
        .run(id, chat_id, name, media_type, size, result.sha256, result.created_at, ownerOf(principal(req)));
      completed = true;
      return reply.code(201).send(result);
    } finally {
      reserved -= reservation;
      await unlink(temporary).catch((e: NodeJS.ErrnoException) => { if (e.code !== "ENOENT") throw e; });
      if (!completed) await unlink(destination).catch((e: NodeJS.ErrnoException) => { if (e.code !== "ENOENT") throw e; });
    }
  });
  const get = (id: string, p: Principal): Row => {
    path(id); cleanup();
    const row = ctx.db.prepare("SELECT * FROM attachments WHERE attachment_id = ?").get(id) as Row | undefined;
    if (!row || (!row.msg_id && row.owner !== ownerOf(p))) throw notFound("Attachment");
    return row;
  };
  app.get<{ Params: { attachment_id: string } }>("/v1/attachments/:attachment_id", { onRequest: auth }, async req => metadata(get(req.params.attachment_id, principal(req))));
  app.get<{ Params: { attachment_id: string } }>("/v1/attachments/:attachment_id/content", { onRequest: auth }, async (req, reply) => {
    const row = get(req.params.attachment_id, principal(req));
    const file = await open(path(row.attachment_id), constants.O_RDONLY | constants.O_NOFOLLOW).catch((e: NodeJS.ErrnoException) => {
      if (e.code === "ENOENT" || e.code === "ELOOP") throw notFound("Attachment");
      throw e;
    });
    const stat = await file.stat();
    if (!stat.isFile() || stat.size !== row.size_bytes) { await file.close(); throw notFound("Attachment"); }
    return reply.header("content-type", "application/octet-stream").header("content-length", row.size_bytes)
      .header("x-content-type-options", "nosniff").header("cache-control", "private, no-store")
      .header("content-disposition", `attachment; filename*=UTF-8''${encodeURIComponent(row.name)}`).send(file.createReadStream());
  });
  if (ephemeral) app.addHook("onClose", async () => { rmSync(dir, { recursive: true, force: true }); });
}
